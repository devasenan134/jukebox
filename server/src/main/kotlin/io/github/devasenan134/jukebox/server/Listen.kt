package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a listen-together session is playing: a shared queue, which song in it, where in the song
 * (at [updatedAt], server time) and whether it's playing. [queue] is left out of updates that
 * don't change it; [queueId] says which queue [index] refers to.
 */
@Serializable
data class ListenState(
    val queue: List<SongRef>? = null,
    val queueId: String,
    val index: Int,
    val positionMs: Long,
    val playing: Boolean,
    val updatedAt: Long = 0,
)

// Listen-together events from the app.
@Serializable @SerialName("listenStart")
data class ListenStart(val conversationId: Long, val state: ListenState) : ClientEvent

@Serializable @SerialName("listenJoin")
data class ListenJoin(val conversationId: Long) : ClientEvent

@Serializable @SerialName("listenLeave")
data class ListenLeave(val conversationId: Long) : ClientEvent

@Serializable @SerialName("listenUpdate")
data class ListenUpdate(val conversationId: Long, val state: ListenState) : ClientEvent

// ...and to the app.

/**
 * Who is listening together in a chat, and who owns (controls) the session. Sent to every member;
 * an empty list means the session ended.
 */
@Serializable @SerialName("listenSession")
data class ListenSessionEvent(val conversationId: Long, val listeners: List<Long>, val owner: Long? = null) : Event

/** The session's playback changed (by [by]), or you just joined. [serverTime] lets the app work out where the song is now. */
@Serializable @SerialName("listenState")
data class ListenStateEvent(val conversationId: Long, val state: ListenState, val by: Long, val serverTime: Long) : Event

/** A message changed after it was sent (a song request was accepted or declined). */
@Serializable @SerialName("messageUpdated")
data class MessageUpdatedEvent(val message: MessageDto) : Event

/** [mode]: "next" plays the song after the current one, "now" skips to it. */
@Serializable data class SongRequestBody(val song: SongRef, val mode: String = "next")
@Serializable data class SongRequestAnswer(val accept: Boolean)

/**
 * Listen together (a "jam"): members of a chat play the same music in sync. Whoever starts the
 * session owns it: only they can play, pause, skip, seek or change the queue. Everyone else can ask
 * for a song with a request in the chat, which the owner accepts or declines.
 *
 * Sessions live in memory only. A session ends when its owner leaves, or stays offline for longer
 * than [ownerGraceMs] (a dropped connection shouldn't end the jam).
 */
class ListenTogether(
    private val hub: Hub,
    private val membersOf: suspend (Long) -> List<Long>,
    private val scope: CoroutineScope,
    private val ownerGraceMs: Long = 60_000,
    private val requestCooldownMs: Long = 10_000,
) {
    private class Session(var state: ListenState, val listeners: MutableSet<Long>, val owner: Long) {
        /** When the owner went offline (reconnecting within the grace period keeps the session). */
        var ownerAwaySince: Long? = null
    }

    private val sessions = mutableMapOf<Long, Session>() // guarded by synchronized(sessions)

    /** Called when a chat's session ends (its unanswered song requests expire). */
    var onEnded: suspend (conversationId: Long) -> Unit = {}

    /** Called when someone starts a new session in a chat (for push notifications), at most every 30 minutes per chat. */
    var onStarted: suspend (userId: Long, conversationId: Long) -> Unit = { _, _ -> }
    private val lastStarted = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** When each listener last asked for a song, so nobody floods the owner with requests. */
    private val lastRequested = mutableMapOf<Long, Long>() // guarded by synchronized(sessions)

    fun listeners(conversationId: Long): List<Long> = synchronized(sessions) { sessions[conversationId]?.listeners?.toList().orEmpty() }

    /** Who controls the session in a chat, or null if there's none. */
    fun owner(conversationId: Long): Long? = synchronized(sessions) { sessions[conversationId]?.owner }

    suspend fun handle(userId: Long, event: ClientEvent) {
        when (event) {
            is ListenStart -> join(userId, event.conversationId, startWith = event.state)
            is ListenJoin -> join(userId, event.conversationId, startWith = null)
            is ListenLeave -> leave(userId, event.conversationId)
            is ListenUpdate -> update(userId, event.conversationId, event.state)
            else -> Unit
        }
    }

    /** Joins a session, starting it (as its owner) with [startWith] if there isn't one yet. You can be in one session at a time. */
    private suspend fun join(userId: Long, conversationId: Long, startWith: ListenState?) {
        if (userId !in membersOf(conversationId) || (startWith?.queue?.size ?: 0) > MAX_QUEUE) return
        leaveAll(userId, except = conversationId)
        var started = false
        val state = synchronized(sessions) {
            val session = sessions[conversationId]
                ?: startWith?.let { Session(it.copy(queue = it.queue.orEmpty(), updatedAt = now()), mutableSetOf(), owner = userId) }
                    ?.also { sessions[conversationId] = it; started = true }
                ?: return
            session.listeners += userId
            if (session.owner == userId) session.ownerAwaySince = null // the owner is back
            session.state
        }
        hub.send(listOf(userId), ListenStateEvent(conversationId, state, by = userId, serverTime = now()))
        announce(conversationId)
        // Reconnects and quick restarts shouldn't notify everyone again.
        if (started) {
            val t = now()
            val last = lastStarted.put(conversationId, t)
            if (last == null || t - last > NOTIFY_EVERY_MS) onStarted(userId, conversationId)
        }
    }

    /** Leaves a session. When the owner leaves, the session ends for everyone. */
    private suspend fun leave(userId: Long, conversationId: Long) {
        var ended = false
        val changed = synchronized(sessions) {
            val session = sessions[conversationId] ?: return
            if (session.owner == userId || session.listeners == setOf(userId)) {
                sessions.remove(conversationId)
                ended = true
                true
            } else {
                session.listeners.remove(userId)
            }
        }
        if (ended) {
            hub.send(membersOf(conversationId), ListenSessionEvent(conversationId, emptyList()))
            onEnded(conversationId)
        } else if (changed) {
            announce(conversationId)
        }
    }

    /** Someone left a chat: they're out of its session. */
    suspend fun leftChat(userId: Long, conversationId: Long) = leave(userId, conversationId)

    /** A chat was deleted: its session ends for everyone. */
    suspend fun ended(conversationId: Long, members: List<Long>) {
        val had = synchronized(sessions) { sessions.remove(conversationId) != null }
        if (had) hub.send(members, ListenSessionEvent(conversationId, emptyList()))
    }

    /** Leaves every session (the user joined another one). */
    suspend fun leaveAll(userId: Long, except: Long? = null) {
        val ids = synchronized(sessions) { sessions.filter { (id, s) -> id != except && userId in s.listeners }.keys.toList() }
        ids.forEach { leave(userId, it) }
    }

    /**
     * The user went offline on every device. Listeners just drop out. An owner's session waits
     * [ownerGraceMs] for them to reconnect before it ends.
     */
    suspend fun wentOffline(userId: Long) {
        val (owned, joined) = synchronized(sessions) {
            val mine = sessions.filter { (_, s) -> userId in s.listeners }
            val owned = mine.filter { (_, s) -> s.owner == userId }
            owned.values.forEach { it.ownerAwaySince = now() }
            owned.keys.toList() to (mine.keys - owned.keys).toList()
        }
        joined.forEach { leave(userId, it) }
        for (id in owned) {
            scope.launch {
                delay(ownerGraceMs)
                val stillAway = synchronized(sessions) {
                    sessions[id]?.let { it.owner == userId && it.ownerAwaySince != null } ?: false
                }
                if (stillAway) leave(userId, id)
            }
        }
    }

    /** Only the owner's changes count; everyone else's player just follows. */
    private suspend fun update(userId: Long, conversationId: Long, update: ListenState) {
        if ((update.queue?.size ?: 0) > MAX_QUEUE) return
        val (others, state) = synchronized(sessions) {
            val session = sessions[conversationId]?.takeIf { it.owner == userId && userId in it.listeners } ?: return
            // An update without a queue must refer to the current one.
            val queue = update.queue ?: session.state.queue.takeIf { update.queueId == session.state.queueId } ?: return
            session.state = update.copy(queue = queue, updatedAt = now())
            (session.listeners - userId) to update.copy(updatedAt = session.state.updatedAt)
        }
        hub.send(others, ListenStateEvent(conversationId, state, by = userId, serverTime = now()))
    }

    /**
     * A listener may ask for a song; the owner can't (they just add it). One request every
     * [requestCooldownMs] per listener, so a burst of swipes can't flood the owner's chat.
     */
    fun requireRequester(userId: Long, conversationId: Long) = synchronized(sessions) {
        val session = sessions[conversationId] ?: throw ApiError(HttpStatusCode.Conflict, "Nobody is listening together in this chat")
        if (userId !in session.listeners) throw ApiError(HttpStatusCode.Forbidden, "Join the listening session first")
        if (session.owner == userId) throw ApiError(HttpStatusCode.BadRequest, "It's your session: add the song to the queue")
        val t = now()
        val wait = (lastRequested[userId] ?: 0) + requestCooldownMs - t
        if (wait > 0) throw ApiError(HttpStatusCode.TooManyRequests, "Wait ${(wait + 999) / 1000} s before asking again")
        lastRequested[userId] = t
    }

    /** Only the session's owner answers song requests. */
    fun requireOwner(userId: Long, conversationId: Long) = synchronized(sessions) {
        val session = sessions[conversationId] ?: throw ApiError(HttpStatusCode.Conflict, "The listening session has ended")
        if (session.owner != userId) throw ApiError(HttpStatusCode.Forbidden, "Only the person who started the session can answer requests")
    }

    private companion object {
        const val MAX_QUEUE = 5_000
        const val NOTIFY_EVERY_MS = 30 * 60 * 1000L
    }

    private suspend fun announce(conversationId: Long) {
        hub.send(membersOf(conversationId), ListenSessionEvent(conversationId, listeners(conversationId), owner(conversationId)))
    }
}
