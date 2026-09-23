package io.github.devasenan134.isaipetti.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SocialException(message: String, val code: Int = 0) : Exception(message)

/** HTTP calls to the companion server (isaipetti-social): accounts, invites, friends and chat. */
class SocialApi(
    private val http: OkHttpClient,
    /** The friends server this talks to (typed in at login); null when none is set. */
    private val baseUrl: () -> String?,
    private val token: () -> String?,
) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    suspend fun login(credentials: Credentials): SessionResponse =
        post("/auth/login", LoginBody(credentials.username, credentials.salt, credentials.token), authenticated = false)

    suspend fun signup(inviteCode: String, username: String, password: String, displayName: String): SessionResponse =
        post("/auth/signup", SignupBody(inviteCode, username, password, displayName), authenticated = false)

    suspend fun logout() = post<Unit, Unit>("/auth/logout", Unit)
    suspend fun logoutOthers() = post<Unit, Unit>("/auth/logout-others", Unit)
    suspend fun rename(displayName: String): SocialUser = send("PATCH", "/me", json.encodeToString(RenameBody.serializer(), RenameBody(displayName)), SocialUser.serializer())

    suspend fun registerDevice(pushToken: String) = post<DeviceBody, Unit>("/devices", DeviceBody(pushToken))
    suspend fun unregisterDevice(pushToken: String) = post<DeviceBody, Unit>("/devices/remove", DeviceBody(pushToken))

    /** Files a bug report; the server turns it into a public GitHub issue. */
    suspend fun reportBug(title: String, description: String, deviceInfo: String?): BugReport =
        post("/bug-reports", BugBody(title, description, deviceInfo))

    suspend fun createInvite(): Invite = post("/invites", Unit)
    suspend fun invites(): List<Invite> = get("/invites")

    suspend fun friends(): List<Friend> = get("/friends")
    suspend fun friendRequests(): FriendRequests = get("/friends/requests")
    suspend fun addFriend(username: String): AddFriendResponse = post("/friends/requests", UsernameBody(username))
    suspend fun acceptFriend(userId: Long) = post<Unit, Unit>("/friends/requests/$userId/accept", Unit)
    suspend fun declineFriend(userId: Long) = post<Unit, Unit>("/friends/requests/$userId/decline", Unit)
    suspend fun removeFriend(userId: Long) = send<Unit>("DELETE", "/friends/$userId", null)

    suspend fun conversations(): List<Conversation> = get("/conversations")
    suspend fun openDm(userId: Long): Conversation = post("/conversations/dm", UserIdBody(userId))
    suspend fun createGroup(name: String, memberIds: List<Long>): Conversation = post("/conversations/group", GroupBody(name, memberIds))
    suspend fun messages(conversationId: Long, before: Long? = null): List<ChatMessage> =
        get("/conversations/$conversationId/messages" + (before?.let { "?before=$it" } ?: ""))
    suspend fun sendMessage(conversationId: Long, body: String, song: SongRef? = null): ChatMessage =
        post("/conversations/$conversationId/messages", MessageBody(body, song))
    suspend fun deleteConversation(conversationId: Long) = send<Unit>("DELETE", "/conversations/$conversationId", null)
    suspend fun markRead(conversationId: Long, messageId: Long) =
        post<ReadBody, Unit>("/conversations/$conversationId/read", ReadBody(messageId))

    /** WebSocket address for live events. */
    fun eventsUrl(): String? = token()?.let { t -> baseUrl()?.let { it.replaceFirst("http", "ws") + "/ws?token=$t" } }

    /** This server's Firebase settings for push notifications, or null if it has none. */
    suspend fun pushConfig(): PushConfig? = try {
        get("/push/config")
    } catch (e: SocialException) {
        if (e.code == 404) null else throw e
    }

    private suspend inline fun <reified T> get(path: String): T = send("GET", path, null, serializer<T>())

    private suspend inline fun <reified B, reified T> post(path: String, body: B, authenticated: Boolean = true): T =
        send("POST", path, if (body is Unit) "" else json.encodeToString(serializer<B>(), body), serializer<T>(), authenticated)

    private suspend fun <T> send(
        method: String,
        path: String,
        body: String?,
        responseSerializer: KSerializer<T>? = null,
        authenticated: Boolean = true,
    ): T = withContext(Dispatchers.IO) {
        val server = baseUrl() ?: throw SocialException("No friends server set. Add one when you log in")
        val builder = Request.Builder().url(server + path)
        if (authenticated) builder.header("Authorization", "Bearer ${token() ?: throw SocialException("Not connected to friends", 401)}")
        builder.method(method, body?.toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                throw SocialException(message ?: "Friends server error (${response.code})", response.code)
            }
            @Suppress("UNCHECKED_CAST")
            if (responseSerializer == null || responseSerializer == serializer<Unit>() || text.isBlank()) Unit as T
            else json.decodeFromString(responseSerializer, text)
        }
    }

    @Serializable private data class LoginBody(val username: String, val salt: String, val token: String)
    @Serializable private data class SignupBody(val inviteCode: String, val username: String, val password: String, val displayName: String)
    @Serializable private data class UsernameBody(val username: String)
    @Serializable private data class UserIdBody(val userId: Long)
    @Serializable private data class GroupBody(val name: String, val memberIds: List<Long>)
    @Serializable private data class MessageBody(val body: String, val song: SongRef?)
    @Serializable private data class ReadBody(val messageId: Long)
    @Serializable private data class RenameBody(val displayName: String)
    @Serializable private data class DeviceBody(val token: String)
    @Serializable private data class BugBody(val title: String, val description: String, val deviceInfo: String?)
}
