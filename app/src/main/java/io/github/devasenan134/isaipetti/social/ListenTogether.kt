package io.github.devasenan134.isaipetti.social

import android.os.SystemClock
import io.github.devasenan134.isaipetti.data.ClientEvent
import io.github.devasenan134.isaipetti.data.Conversation
import io.github.devasenan134.isaipetti.data.ListenJoin
import io.github.devasenan134.isaipetti.data.ListenLeave
import io.github.devasenan134.isaipetti.data.ListenSessionEvent
import io.github.devasenan134.isaipetti.data.ListenStart
import io.github.devasenan134.isaipetti.data.ListenState
import io.github.devasenan134.isaipetti.data.ListenStateEvent
import io.github.devasenan134.isaipetti.data.ListenUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Listen together (a "jam"): everyone in a chat's session hears the same music. Whoever started it
 * owns it and is the only one who can play, pause, skip, seek or change the queue; the others can
 * ask for a song with a request in the chat.
 *
 * This keeps track of the sessions (for the chat screens) and which one we're in. The playback
 * service does the actual syncing: it applies [remote] to the player and reports our own changes
 * through [update].
 */
class ListenTogether(private val send: (ClientEvent) -> Unit, private val me: () -> Long?) {
    /** A state from the session, with where the song should be right when it arrived. */
    data class Remote(val state: ListenState, val positionMs: Long, val receivedAt: Long) {
        /** Where the song should be now. */
        fun positionNow(): Long = positionMs + if (state.playing) SystemClock.elapsedRealtime() - receivedAt else 0
    }

    private val _sessions = MutableStateFlow<Map<Long, List<Long>>>(emptyMap())

    /** Chat id -> who is listening together there. */
    val sessions: StateFlow<Map<Long, List<Long>>> = _sessions

    private val _owners = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /** Chat id -> who controls the session there. */
    val owners: StateFlow<Map<Long, Long>> = _owners

    private val _joined = MutableStateFlow<Long?>(null)

    /** The chat whose session we're in, if any. */
    val joined: StateFlow<Long?> = _joined

    private val _remote = MutableStateFlow<Remote?>(null)

    /** The latest playback state from the session, for the player to follow. */
    val remote: StateFlow<Remote?> = _remote

    /** The player's current state as a session state. Set by the playback service while it runs. */
    var snapshot: (() -> ListenState)? = null

    // Until the server confirms our join, session lists without us are from before it.
    private var confirmed = false

    /** Whether we own the session we're in (false if we're not in one). */
    fun isOwner(): Boolean = _joined.value?.let { _owners.value[it] == me() } ?: false

    /** In someone else's session: the music follows them and our controls are off. */
    fun isListener(): Boolean = _joined.value?.let { id -> _owners.value[id]?.let { it != me() } } ?: false

    /** Starts a session in a chat with what we're playing now (or joins the one already there). */
    fun start(conversationId: Long) {
        _joined.value = conversationId
        // We own it unless someone else already started one there (the server's answer corrects this).
        if (conversationId !in _owners.value) me()?.let { m -> _owners.update { it + (conversationId to m) } }
        confirmed = false
        send(ListenStart(conversationId, currentState()))
    }

    fun join(conversationId: Long) {
        _joined.value = conversationId
        confirmed = false
        send(ListenJoin(conversationId))
    }

    /** Leaves the session. The music keeps playing, just not in sync anymore. */
    fun leave() {
        val id = _joined.value ?: return
        send(ListenLeave(id))
        _joined.value = null
        _remote.value = null
    }

    /** Tells the others about a change we made (play/pause, skip, seek or a new queue). */
    fun update(state: ListenState) {
        _joined.value?.let { send(ListenUpdate(it, state)) }
    }

    /**
     * After reconnecting, get back into our session. The owner restarts it with their music if it
     * ended meanwhile; a listener only rejoins (a session that ended stays ended).
     */
    fun onConnected() {
        _joined.value?.let {
            confirmed = false
            send(if (isOwner()) ListenStart(it, currentState()) else ListenJoin(it))
        }
    }

    fun onConversations(conversations: List<Conversation>) {
        _sessions.value = conversations.filter { it.listeners.isNotEmpty() }.associate { it.id to it.listeners }
        _owners.value = conversations.mapNotNull { c -> c.listenOwner?.takeIf { c.listeners.isNotEmpty() }?.let { c.id to it } }.toMap()
    }

    fun handle(event: ListenSessionEvent) {
        _sessions.update { if (event.listeners.isEmpty()) it - event.conversationId else it + (event.conversationId to event.listeners) }
        _owners.update { if (event.listeners.isEmpty() || event.owner == null) it - event.conversationId else it + (event.conversationId to event.owner) }
        if (event.conversationId == _joined.value && confirmed && me() !in event.listeners) {
            _joined.value = null
            _remote.value = null
        }
    }

    fun handle(event: ListenStateEvent) {
        if (event.conversationId != _joined.value) return
        confirmed = true
        val state = event.state
        val elapsed = if (state.playing) (event.serverTime - state.updatedAt).coerceAtLeast(0) else 0
        _remote.value = Remote(state, state.positionMs + elapsed, SystemClock.elapsedRealtime())
    }

    private fun currentState() = snapshot?.invoke() ?: ListenState(emptyList(), newQueueId(), index = 0, positionMs = 0, playing = false)

    companion object {
        fun newQueueId(): String = java.util.UUID.randomUUID().toString()
    }
}
