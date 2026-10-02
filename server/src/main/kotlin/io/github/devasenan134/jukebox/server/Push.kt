package io.github.devasenan134.jukebox.server

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/** What the app needs to start Firebase for this server's project. None of it is secret; it just isn't built into the app. */
@Serializable
data class PushConfig(val projectId: String, val appId: String, val apiKey: String, val senderId: String) {
    companion object {
        /** Reads the Android app's settings from a google-services.json file. */
        fun fromGoogleServices(file: String, packageName: String = "io.github.devasenan134.jukebox"): PushConfig {
            val root = Json.parseToJsonElement(File(file).readText()).jsonObject
            val project = root["project_info"]!!.jsonObject
            val clients = root["client"]!!.jsonArray.map { it.jsonObject }
            val client = clients.firstOrNull {
                it["client_info"]?.jsonObject?.get("android_client_info")?.jsonObject?.get("package_name")?.jsonPrimitive?.content == packageName
            } ?: clients.first()
            return PushConfig(
                projectId = project["project_id"]!!.jsonPrimitive.content,
                appId = client["client_info"]!!.jsonObject["mobilesdk_app_id"]!!.jsonPrimitive.content,
                apiKey = client["api_key"]!!.jsonArray.first().jsonObject["current_key"]!!.jsonPrimitive.content,
                senderId = project["project_number"]!!.jsonPrimitive.content,
            )
        }
    }
}

/** Delivers one push notification to one phone. */
interface PushSender {
    enum class Result { Sent, InvalidToken, Failed }

    suspend fun send(deviceToken: String, data: Map<String, String>): Result
}

/** Used when no Firebase key is configured: notifications are simply skipped. */
object NoPush : PushSender {
    override suspend fun send(deviceToken: String, data: Map<String, String>) = PushSender.Result.Failed
}

/**
 * Sends through Firebase Cloud Messaging (HTTP v1 API), logging in with the project's
 * service-account key. We send "data" messages and let the app build the notification,
 * so it can group by chat and open the right screen when tapped.
 */
class FcmSender(keyFile: String) : PushSender {
    private val log = LoggerFactory.getLogger("push")
    private val credentials = File(keyFile).inputStream().use {
        GoogleCredentials.fromStream(it).createScoped("https://www.googleapis.com/auth/firebase.messaging")
    }
    private val projectId = (credentials as ServiceAccountCredentials).projectId
    private val http = HttpClient(CIO)

    override suspend fun send(deviceToken: String, data: Map<String, String>): PushSender.Result {
        val accessToken = withContext(Dispatchers.IO) {
            credentials.refreshIfExpired()
            credentials.accessToken?.tokenValue ?: error("Firebase login returned no access token")
        }
        val body = buildJsonObject {
            putJsonObject("message") {
                put("token", deviceToken)
                putJsonObject("data") { data.forEach { (k, v) -> put(k, v) } }
                // High priority wakes the phone for chat messages; give up after a day.
                putJsonObject("android") {
                    put("priority", "HIGH")
                    put("ttl", "86400s")
                }
            }
        }
        val response = http.post("https://fcm.googleapis.com/v1/projects/$projectId/messages:send") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (response.status.isSuccess()) return PushSender.Result.Sent
        val text = response.bodyAsText()
        // The app was uninstalled, the token was replaced, or it belongs to another Firebase project
        // (an old app version from before this server's project): forget this phone.
        if (response.status == HttpStatusCode.NotFound || "UNREGISTERED" in text || "INVALID_ARGUMENT" in text || "SENDER_ID_MISMATCH" in text) {
            return PushSender.Result.InvalidToken
        }
        log.warn("FCM send failed (${response.status.value}): ${text.take(300)}")
        return PushSender.Result.Failed
    }
}

/** Remembers each phone's push token and notifies people who don't have the app on screen. */
class Push(private val db: Db, private val sender: PushSender) {
    suspend fun register(userId: Long, token: String) = db.tx {
        // A phone that switches accounts moves its token to the new user.
        update("INSERT OR REPLACE INTO devices (token, user_id, updated_at) VALUES (?, ?, ?)", token, userId, now())
    }

    suspend fun unregister(userId: Long, token: String) = db.tx {
        update("DELETE FROM devices WHERE token = ? AND user_id = ?", token, userId)
    }

    suspend fun notify(userIds: Collection<Long>, data: Map<String, String>) {
        if (userIds.isEmpty()) return
        val tokens = db.tx {
            userIds.toSet().flatMap { id -> query("SELECT token FROM devices WHERE user_id = ?", id) { it.getString(1) } }
        }
        for (token in tokens) {
            val result = runCatching { sender.send(token, data) }.getOrDefault(PushSender.Result.Failed)
            if (result == PushSender.Result.InvalidToken) db.tx { update("DELETE FROM devices WHERE token = ?", token) }
        }
    }

    /** A new message, for people who don't have the app open. Those it @mentions are told so first thing. */
    suspend fun newMessage(message: MessageDto, conversation: ConversationDto, recipients: List<Long>) {
        val (mentioned, others) = recipients.partition { it in message.mentions }
        if (others.isNotEmpty()) notify(others, messageData(message, conversation))
        if (mentioned.isNotEmpty()) {
            val data = messageData(message, conversation)
            notify(mentioned, data + ("body" to "Mentioned you: " + data["body"]))
        }
    }

    private fun messageData(message: MessageDto, conversation: ConversationDto) = mapOf(
        "type" to "message",
        "conversationId" to message.conversationId.toString(),
        "title" to if (conversation.kind == "group") conversation.name.orEmpty() else message.sender.displayName,
        "sender" to message.sender.displayName,
        "isGroup" to (conversation.kind == "group").toString(),
        "body" to when {
            message.image != null -> imageLabel(message.image.kind) + if (message.body.isNotBlank()) " – ${message.body}" else ""
            message.voiceMs != null -> "🎤 Voice message (${clockTime(message.voiceMs)})"
            else -> message.song?.let { "♪ ${it.title}" + it.clipLabel() + if (message.body.isNotBlank()) " – ${message.body}" else "" } ?: message.body
        },
    )

    private fun imageLabel(kind: String) = when (kind) {
        "gif" -> "GIF"
        "sticker" -> "Sticker"
        else -> "📷 Photo"
    }

    private fun SongRef.clipLabel() = if (isClip) " (${clockTime(clipStartMs!!)}–${clockTime(clipEndMs!!)})" else ""

    /** "Alice is listening together in Gang. Tap to join", for chat members who don't have the app open. */
    suspend fun listenStarted(starterId: Long, conversationId: Long, recipients: List<Long>) {
        if (recipients.isEmpty()) return
        val (starter, groupName) = db.tx {
            val name = queryOne("SELECT display_name FROM users WHERE id = ?", starterId) { it.getString(1) } ?: return@tx null
            name to queryOne("SELECT name FROM conversations WHERE id = ? AND kind = 'group'", conversationId) { it.getString(1) }
        } ?: return
        notify(
            recipients,
            mapOf(
                "type" to "listen",
                "conversationId" to conversationId.toString(),
                "title" to (groupName ?: starter),
                "body" to if (groupName != null) "$starter started listening together. Tap to join" else "Started listening together. Tap to join",
            ),
        )
    }

    suspend fun friendRequest(from: UserDto, to: Long) = notify(
        listOf(to),
        mapOf("type" to "friendRequest", "title" to "Friend request", "body" to "${from.displayName} (@${from.username}) wants to be friends"),
    )

    suspend fun friendAccepted(by: UserDto, to: Long) = notify(
        listOf(to),
        mapOf("type" to "friendAccepted", "title" to "New friend", "body" to "${by.displayName} accepted your friend request"),
    )

    /** Tells the admins someone asked for music that isn't in the library. */
    suspend fun musicRequested(adminIds: List<Long>, by: UserDto, label: String) = notify(
        adminIds,
        mapOf("type" to "musicRequest", "title" to "New request", "body" to "${by.displayName} asked for $label"),
    )

    /** The music someone asked for is in the library now: tapping opens its movie. */
    suspend fun musicReady(userIds: List<Long>, label: String, albumId: String) = notify(
        userIds,
        mapOf("type" to "musicReady", "albumId" to albumId, "title" to "It's in the library", "body" to "♪ $label is ready to play"),
    )

    suspend fun musicDeclined(userIds: List<Long>, label: String, note: String?) = notify(
        userIds,
        mapOf("type" to "musicDeclined", "title" to "Couldn't get $label", "body" to (note ?: "It couldn't be found this time. Sorry!")),
    )
}
