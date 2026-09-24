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

    /** Sets your profile picture (a JPEG the app already cropped and shrank). Returns you, with its new version. */
    suspend fun setAvatar(jpeg: ByteArray): SocialUser = sendImage("PUT", "/me/avatar", jpeg, SocialUser.serializer())
    suspend fun removeAvatar(): SocialUser = send("DELETE", "/me/avatar", null, SocialUser.serializer())

    /** Where [user]'s profile picture is (needs the Authorization header from [authHeader]); null if they have none. */
    fun avatarUrl(user: SocialUser): String? = user.avatar?.let { v -> baseUrl()?.let { "$it/users/${user.id}/avatar?v=$v" } }
    fun authHeader(): String? = token()?.let { "Bearer $it" }

    /** A group's photo; anyone in the group can change it. */
    suspend fun setGroupPicture(conversationId: Long, jpeg: ByteArray): Conversation =
        sendImage("PUT", "/conversations/$conversationId/picture", jpeg, Conversation.serializer())
    suspend fun removeGroupPicture(conversationId: Long): Conversation = send("DELETE", "/conversations/$conversationId/picture", null, Conversation.serializer())
    fun groupPictureUrl(c: Conversation): String? = c.picture?.let { v -> baseUrl()?.let { "$it/conversations/${c.id}/picture?v=$v" } }

    /** A cover for a playlist you made; the friends server stores it in Navidrome. */
    suspend fun setPlaylistCover(playlistId: String, jpeg: ByteArray) = sendImage<Unit>("PUT", "/playlists/${enc(playlistId)}/cover", jpeg, null)
    suspend fun removePlaylistCover(playlistId: String) = send<Unit>("DELETE", "/playlists/${enc(playlistId)}/cover", null)

    suspend fun registerDevice(pushToken: String) = post<DeviceBody, Unit>("/devices", DeviceBody(pushToken))
    suspend fun unregisterDevice(pushToken: String) = post<DeviceBody, Unit>("/devices/remove", DeviceBody(pushToken))

    /** Your liked playlists, saved with your account on the friends server (Navidrome can't like playlists). */
    suspend fun likedPlaylists(): List<Playlist> = get<List<PlaylistBody>>("/likes/playlists").map { Playlist(it.id, it.name, coverArt = it.coverArt, songCount = it.songCount) }
    suspend fun likePlaylist(playlist: Playlist) =
        send<Unit>("PUT", "/likes/playlists", json.encodeToString(PlaylistBody.serializer(), PlaylistBody(playlist.id, playlist.name, playlist.coverArt, playlist.songCount)))
    /** How many other people liked each of these playlists (for showing likes on your own). */
    suspend fun playlistLikeCounts(ids: List<String>): Map<String, Int> =
        if (ids.isEmpty()) emptyMap() else get("/likes/playlists/counts?ids=${ids.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") }}")
    suspend fun unlikePlaylist(id: String) = send<Unit>("DELETE", "/likes/playlists/${java.net.URLEncoder.encode(id, "UTF-8")}", null)

    // Mixes, playlists and stations by Isai Pettai.
    suspend fun mixes(): HomeMixes = get("/mixes")
    suspend fun mix(id: String): Mix = get("/mixes/${enc(id)}")

    /** Search that forgives spelling, over songs, movies, artists, composers, lyricists and actors. */
    suspend fun search(query: String): LibrarySearchResults = get("/search?q=${enc(query)}")
    suspend fun person(id: String): PersonPage = get("/search/people/${enc(id)}")
    suspend fun followedMixes(): List<Mix> = get("/mixes/followed")
    suspend fun followMix(id: String) = send<Unit>("PUT", "/mixes/${enc(id)}/follow", "")
    suspend fun unfollowMix(id: String) = send<Unit>("DELETE", "/mixes/${enc(id)}/follow", null)
    /** The next songs of a station, leaving out [exclude] (what it already played). */
    suspend fun radio(id: String, exclude: List<String>, count: Int = 25): Mix = post("/mixes/radio", RadioBody(id, exclude, count))
    /** Songs that would fit a playlist made of [songIds]; [page] 1, 2... for more. */
    suspend fun recommend(songIds: List<String>, count: Int = 10, page: Int = 0): List<MixSong> =
        post("/mixes/recommend", RecommendBody(songIds, count, page))
    /** What was played and skipped, so mixes can learn. */
    suspend fun recordPlays(events: List<PlayEvent>) = post<PlaysBody, Unit>("/plays", PlaysBody(events))

    /** Sends a bug report or a feature request ([kind] "bug" or "feature"); the server turns it into a public GitHub issue. */
    suspend fun sendFeedback(kind: String, title: String, description: String, deviceInfo: String?): BugReport =
        post("/bug-reports", FeedbackBody(title, description, deviceInfo, kind))

    suspend fun createInvite(): Invite = post("/invites", Unit)
    suspend fun invites(): List<Invite> = get("/invites")

    /** While listening together: ask the session's owner to play [song] next, or now if [playNow] (it shows in the chat). */
    suspend fun requestSong(conversationId: Long, song: SongRef, playNow: Boolean): ChatMessage =
        post("/conversations/$conversationId/listen/requests", SongRequestBody(song, if (playNow) "now" else "next"))

    /** The session's owner accepts or declines a song request. */
    suspend fun answerRequest(conversationId: Long, messageId: Long, accept: Boolean): ChatMessage =
        post("/conversations/$conversationId/listen/requests/$messageId", SongRequestAnswer(accept))
    /** Whether you're a Navidrome admin (who can see everyone's listening stats). */
    suspend fun adminAccess(): AdminAccess = get("/admin/access")
    suspend fun listeningStats(timeZone: String): ListeningStats =
        get("/admin/stats?tz=" + java.net.URLEncoder.encode(timeZone, "UTF-8"))
    /** Deletes an unused invite: its code stops working. */
    suspend fun deleteInvite(code: String) = send<Unit>("DELETE", "/invites/${enc(code)}", null)

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
    /** Sends a message, which can be a reply to message [replyTo] of the same chat. */
    suspend fun sendMessage(conversationId: Long, body: String, song: SongRef? = null, replyTo: Long? = null): ChatMessage =
        post("/conversations/$conversationId/messages", MessageBody(body, song, replyTo))
    suspend fun deleteConversation(conversationId: Long) = send<Unit>("DELETE", "/conversations/$conversationId", null)
    /**
     * Sends a photo, GIF or sticker ([bytes] as JPEG, PNG, WebP or GIF, [width] × [height] pixels),
     * with an optional caption, as a reply if [replyTo] is set.
     */
    suspend fun sendImage(
        conversationId: Long, bytes: ByteArray, mime: String, kind: String, width: Int, height: Int, caption: String = "", replyTo: Long? = null,
    ): ChatMessage {
        val query = "kind=$kind&width=$width&height=$height&caption=${enc(caption)}" + (replyTo?.let { "&replyTo=$it" } ?: "")
        return send("POST", "/conversations/$conversationId/images?$query", null, ChatMessage.serializer(), bytes = bytes, mime = mime)
    }

    /** A message's picture file and its type (for saving it to the phone). */
    suspend fun downloadImage(message: ChatMessage): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val url = imageUrl(message) ?: throw SocialException("No friends server set")
        val request = Request.Builder().url(url).header("Authorization", authHeader() ?: throw SocialException("Not connected to friends", 401)).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SocialException("Couldn't get the picture (${response.code})", response.code)
            response.body.bytes() to (response.header("Content-Type") ?: "image/jpeg")
        }
    }

    /** Where a message's picture is (needs the Authorization header from [authHeader]). */
    fun imageUrl(message: ChatMessage): String? = baseUrl()?.let { "$it/conversations/${message.conversationId}/messages/${message.id}/image" }

    /** Changes the text of your own message, or deletes it for everyone. */
    suspend fun editMessage(conversationId: Long, messageId: Long, body: String): ChatMessage =
        send("PATCH", "/conversations/$conversationId/messages/$messageId", json.encodeToString(EditBody.serializer(), EditBody(body)), ChatMessage.serializer())
    suspend fun deleteMessage(conversationId: Long, messageId: Long): ChatMessage =
        send("DELETE", "/conversations/$conversationId/messages/$messageId", null, ChatMessage.serializer())

    /** Reacts to a message with an emoji (replacing your earlier one), or with null takes your reaction away. */
    suspend fun react(conversationId: Long, messageId: Long, emoji: String?): ChatMessage {
        val path = "/conversations/$conversationId/messages/$messageId/reaction"
        return if (emoji == null) send("DELETE", path, null, ChatMessage.serializer())
        else send("PUT", path, json.encodeToString(ReactBody.serializer(), ReactBody(emoji)), ChatMessage.serializer())
    }

    /** Pins a message to the top of the chat for [hours] (24, 168 or 720), or unpins it. */
    suspend fun pin(conversationId: Long, messageId: Long, hours: Int): Conversation = post("/conversations/$conversationId/pins", PinBody(messageId, hours))
    suspend fun unpin(conversationId: Long, messageId: Long): Conversation =
        send("DELETE", "/conversations/$conversationId/pins/$messageId", null, Conversation.serializer())

    /** The group's owner renames it. */
    suspend fun renameGroup(conversationId: Long, name: String): Conversation =
        send("PUT", "/conversations/$conversationId/name", json.encodeToString(NameBody.serializer(), NameBody(name)), Conversation.serializer())
    /** The group's owner adds friends to it. */
    suspend fun addMembers(conversationId: Long, userIds: List<Long>): Conversation = post("/conversations/$conversationId/members", UserIdsBody(userIds))
    /** The group's owner takes someone out of it. */
    suspend fun removeMember(conversationId: Long, userId: Long): Conversation =
        send("DELETE", "/conversations/$conversationId/members/$userId", null, Conversation.serializer())
    /** Which of a chat's members have the app open right now. */
    suspend fun onlineMembers(conversationId: Long): List<Long> = get("/conversations/$conversationId/online")
    suspend fun leaveGroup(conversationId: Long) = post<Unit, Unit>("/conversations/$conversationId/leave", Unit)
    suspend fun deleteForEveryone(conversationId: Long) = send<Unit>("DELETE", "/conversations/$conversationId/everyone", null)
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

    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8")

    private suspend inline fun <reified T> get(path: String): T = send("GET", path, null, serializer<T>())

    private suspend inline fun <reified B, reified T> post(path: String, body: B, authenticated: Boolean = true): T =
        send("POST", path, if (body is Unit) "" else json.encodeToString(serializer<B>(), body), serializer<T>(), authenticated)

    /** Like [send], but the body is a picture instead of JSON. */
    private suspend fun <T> sendImage(method: String, path: String, jpeg: ByteArray, responseSerializer: KSerializer<T>?): T =
        send(method, path, null, responseSerializer, bytes = jpeg)

    private suspend fun <T> send(
        method: String,
        path: String,
        body: String?,
        responseSerializer: KSerializer<T>? = null,
        authenticated: Boolean = true,
        bytes: ByteArray? = null,
        mime: String = "image/jpeg",
    ): T = withContext(Dispatchers.IO) {
        val server = baseUrl() ?: throw SocialException("No friends server set. Add one when you log in")
        val builder = Request.Builder().url(server + path)
        if (authenticated) builder.header("Authorization", "Bearer ${token() ?: throw SocialException("Not connected to friends", 401)}")
        builder.method(method, bytes?.toRequestBody(mime.toMediaType()) ?: body?.toRequestBody("application/json".toMediaType()))
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
    @Serializable private data class UserIdsBody(val userIds: List<Long>)
    @Serializable private data class NameBody(val name: String)
    @Serializable private data class PinBody(val messageId: Long, val hours: Int)
    @Serializable private data class EditBody(val body: String)
    @Serializable private data class ReactBody(val emoji: String)
    @Serializable private data class MessageBody(val body: String, val song: SongRef?, val replyTo: Long? = null)
    @Serializable private data class ReadBody(val messageId: Long)
    @Serializable private data class RenameBody(val displayName: String)
    @Serializable private data class DeviceBody(val token: String)
    @Serializable private data class PlaylistBody(val id: String, val name: String = "", val coverArt: String? = null, val songCount: Int = 0)
    @Serializable private data class RadioBody(val id: String, val exclude: List<String>, val count: Int)
    @Serializable private data class RecommendBody(val songIds: List<String>, val count: Int, val page: Int)
    @Serializable private data class PlaysBody(val events: List<PlayEvent>)
    @Serializable private data class FeedbackBody(val title: String, val description: String, val deviceInfo: String?, val kind: String)

    @Serializable private data class SongRequestBody(val song: SongRef, val mode: String)
    @Serializable private data class SongRequestAnswer(val accept: Boolean)
}
