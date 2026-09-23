package io.github.devasenan134.isaipetti.social

import android.content.Context
import android.util.Log
import io.github.devasenan134.isaipetti.push.PushSetup
import io.github.devasenan134.isaipetti.data.AppStateUpdate
import io.github.devasenan134.isaipetti.data.ChatMessage
import io.github.devasenan134.isaipetti.data.ClientEvent
import io.github.devasenan134.isaipetti.data.ConversationRemovedEvent
import io.github.devasenan134.isaipetti.data.Conversation
import io.github.devasenan134.isaipetti.data.Friend
import io.github.devasenan134.isaipetti.data.FriendAddedEvent
import io.github.devasenan134.isaipetti.data.FriendRemovedEvent
import io.github.devasenan134.isaipetti.data.FriendRequestEvent
import io.github.devasenan134.isaipetti.data.FriendRequests
import io.github.devasenan134.isaipetti.data.ListenSessionEvent
import io.github.devasenan134.isaipetti.data.ListenStateEvent
import io.github.devasenan134.isaipetti.data.MessageEvent
import io.github.devasenan134.isaipetti.data.NowPlayingUpdate
import io.github.devasenan134.isaipetti.data.PresenceEvent
import io.github.devasenan134.isaipetti.data.SessionStore
import io.github.devasenan134.isaipetti.data.SocialApi
import io.github.devasenan134.isaipetti.data.SocialEvent
import io.github.devasenan134.isaipetti.data.SocialException
import io.github.devasenan134.isaipetti.data.SocialSession
import io.github.devasenan134.isaipetti.data.SocialUser
import io.github.devasenan134.isaipetti.data.SongRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume

/**
 * Everything friends-related the UI needs, kept up to date live.
 *
 * The app stays connected to the friends server while it's on screen or playing music
 * (so friends see you online and what you're listening to), and disconnects 30 seconds
 * after both stop. If the connection drops, it reconnects by itself.
 */
@OptIn(FlowPreview::class)
class Social(private val context: Context, private val session: SessionStore, private val http: OkHttpClient) {
    val api = SocialApi(http, { session.credentials.value?.socialServer }) { session.social.value?.token }

    /** For signing up, before the friends server is saved with the login. */
    fun apiFor(server: String) = SocialApi(http, { server }) { null }
    private val wsClient = http.newBuilder().pingInterval(java.time.Duration.ofSeconds(25)).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    enum class Status { Offline, Connecting, Online, Unavailable }

    private val _status = MutableStateFlow(Status.Offline)
    val status: StateFlow<Status> = _status

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends

    private val _requests = MutableStateFlow(FriendRequests())
    val requests: StateFlow<FriendRequests> = _requests

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations

    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)

    /** New messages as they arrive, for the open chat screen. */
    val messages: SharedFlow<ChatMessage> = _messages

    /** The chat currently on screen; its new messages are marked read right away. */
    var openConversationId: Long? = null

    val me: SocialUser? get() = session.social.value?.user

    /** Listening together in chats. */
    val listen = ListenTogether(send = ::sendEvent, me = { me?.id })

    private val foreground = MutableStateFlow(false)
    private val playing = MutableStateFlow(false)
    private var nowPlaying: SongRef? = null
    private var socket: WebSocket? = null

    init {
        scope.launch {
            combine(foreground, playing, session.credentials) { fg, pl, creds -> creds?.socialServer != null && (fg || pl) }
                .distinctUntilChanged()
                .debounce { stayConnected -> if (stayConnected) 0 else 30_000 }
                .collectLatest { stayConnected -> if (stayConnected) stayConnected() else _status.value = Status.Offline }
        }
    }

    fun setForeground(value: Boolean) {
        foreground.value = value
        sendEvent(AppStateUpdate(value))
    }

    private var pushToken: String? = null
    private var registeredToken: String? = null

    /** Firebase's address for this phone (it can change); registered with the server once logged in. */
    fun onPushToken(token: String) {
        pushToken = token
        scope.launch { registerDevice() }
    }

    private suspend fun registerDevice() {
        // Start Firebase with this server's project first (its settings aren't built into the app).
        quietly { api.pushConfig()?.let { PushSetup.start(context, it) } }
        val token = pushToken ?: fetchPushToken() ?: return
        pushToken = token
        if (session.social.value == null || token == registeredToken) return
        quietly {
            api.registerDevice(token)
            registeredToken = token
        }
    }

    private suspend fun fetchPushToken(): String? {
        // Builds without a Firebase project (no google-services.json) simply have no notifications.
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return null
        return suspendCancellableCoroutine { continuation ->
            messaging.token.addOnCompleteListener { task ->
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }

    /** Logs out of the friends server and stops notifications to this phone. */
    suspend fun logout() {
        listen.leave()
        quietly { pushToken?.let { api.unregisterDevice(it) } }
        quietly { api.logout() }
        registeredToken = null
        pushToken = null
        PushSetup.forget(context)
    }

    /** Called by the playback service whenever the song or play/pause changes. */
    fun onPlayback(song: SongRef?, isPlaying: Boolean) {
        playing.value = isPlaying
        val shown = song.takeIf { isPlaying }
        if (shown == nowPlaying) return
        nowPlaying = shown
        sendEvent(NowPlayingUpdate(shown))
    }

    /** Reloads friends, requests and chats from the server. */
    fun refresh() {
        scope.launch { refreshFriends() }
        scope.launch { refreshRequests() }
        scope.launch { refreshConversations() }
    }

    fun refreshConversationsSoon() {
        scope.launch { refreshConversations() }
    }

    fun markRead(conversationId: Long, messageId: Long) {
        _conversations.update { list -> list.map { if (it.id == conversationId) it.copy(unread = 0) else it } }
        scope.launch { quietly { api.markRead(conversationId, messageId) } }
    }

    private val _removed = MutableSharedFlow<Long>(extraBufferCapacity = 8)

    /** Chats deleted for everyone (by their owner), so an open chat screen can close. */
    val removed: SharedFlow<Long> = _removed

    /** Leaves a group chat. */
    suspend fun leaveGroup(conversationId: Long) {
        if (listen.joined.value == conversationId) listen.leave()
        api.leaveGroup(conversationId)
        _conversations.update { list -> list.filter { it.id != conversationId } }
    }

    /** Deletes a group and its messages for every member (only its owner can). */
    suspend fun deleteForEveryone(conversationId: Long) {
        api.deleteForEveryone(conversationId)
        _conversations.update { list -> list.filter { it.id != conversationId } }
    }

    /** Deletes a chat you can't message in anymore. It disappears for you only. */
    suspend fun deleteConversation(conversationId: Long) {
        api.deleteConversation(conversationId)
        _conversations.update { list -> list.filter { it.id != conversationId } }
    }

    /** Connect, and keep reconnecting with growing pauses until told to stop. */
    private suspend fun stayConnected() {
        var backoff = 2_000L
        while (true) {
            _status.value = Status.Connecting
            try {
                ensureLoggedIn()
                refreshAll()
                registerDevice()
                runSocket()
                backoff = 2_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Friends server unreachable: ${e.message}")
                _status.value = Status.Unavailable
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }

    /** Logs in to the friends server with the saved Navidrome login, if not done yet. */
    private suspend fun ensureLoggedIn() {
        if (session.social.value != null) return
        val credentials = session.credentials.value ?: throw SocialException("Not logged in")
        val response = api.login(credentials)
        session.saveSocial(SocialSession(response.sessionToken, response.user))
    }

    private suspend fun refreshAll() {
        try {
            refreshFriends(throwErrors = true)
        } catch (e: SocialException) {
            if (e.code == 401) session.saveSocial(null) // session expired; next attempt logs in again
            throw e
        }
        refreshRequests()
        refreshConversations()
    }

    /** Opens the WebSocket and suspends until it closes. */
    private suspend fun runSocket() {
        val url = api.eventsUrl() ?: return
        suspendCancellableCoroutine { continuation ->
            val ws = wsClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch {
                        _status.value = Status.Online
                        sendEvent(AppStateUpdate(foreground.value))
                        nowPlaying?.let { sendEvent(NowPlayingUpdate(it)) }
                        listen.onConnected()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = runCatching { api.json.decodeFromString(SocialEvent.serializer(), text) }.getOrNull() ?: return
                    scope.launch { handle(event) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            })
            socket = ws
            continuation.invokeOnCancellation { ws.close(1000, "bye") }
        }
        socket = null
    }

    private suspend fun handle(event: SocialEvent) {
        when (event) {
            is PresenceEvent -> _friends.update { list ->
                list.map { if (it.user.id == event.userId) it.copy(online = event.online, nowPlaying = event.nowPlaying) else it }.sortedForDisplay()
            }
            is MessageEvent -> {
                _messages.emit(event.message)
                if (event.message.conversationId == openConversationId) markRead(event.message.conversationId, event.message.id)
                refreshConversations()
            }
            is FriendRequestEvent -> refreshRequests()
            is ConversationRemovedEvent -> {
                _conversations.update { list -> list.filter { it.id != event.conversationId } }
                _removed.emit(event.conversationId)
            }
            is ListenSessionEvent -> listen.handle(event)
            is ListenStateEvent -> listen.handle(event)
            is FriendAddedEvent, is FriendRemovedEvent -> {
                refreshFriends()
                refreshRequests()
            }
        }
    }

    private fun sendEvent(event: ClientEvent) {
        socket?.send(api.json.encodeToString(ClientEvent.serializer(), event))
    }

    private suspend fun refreshFriends(throwErrors: Boolean = false) {
        if (throwErrors) _friends.value = api.friends().sortedForDisplay()
        else quietly { _friends.value = api.friends().sortedForDisplay() }
    }

    private suspend fun refreshRequests() = quietly { _requests.value = api.friendRequests() }
    private suspend fun refreshConversations() = quietly {
        _conversations.value = api.conversations()
        listen.onConversations(_conversations.value)
    }

    private fun List<Friend>.sortedForDisplay() =
        sortedWith(compareByDescending<Friend> { it.online }.thenBy { it.user.displayName.lowercase() })

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Friends request failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "Social"
    }
}
