package io.github.devasenan134.jukebox.server

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Live events the server pushes to the app over the WebSocket. The JSON has a "type" field. */
@Serializable
sealed interface Event

@Serializable @SerialName("presence")
data class PresenceEvent(val userId: Long, val online: Boolean, val nowPlaying: SongRef? = null) : Event

@Serializable @SerialName("message")
data class MessageEvent(val message: MessageDto) : Event

@Serializable @SerialName("friendRequest")
data class FriendRequestEvent(val from: UserDto) : Event

@Serializable @SerialName("friendAdded")
data class FriendAddedEvent(val friend: FriendDto) : Event

@Serializable @SerialName("friendRemoved")
data class FriendRemovedEvent(val userId: Long) : Event

/** Something about a chat changed that isn't a new message (a pin was removed): fetch it again. */
@Serializable @SerialName("conversationUpdated")
data class ConversationUpdatedEvent(val conversationId: Long) : Event

/** [userId] is typing in a chat (sent every few seconds while they type; the app shows it for a little while). */
@Serializable @SerialName("typing")
data class TypingEvent(val conversationId: Long, val userId: Long) : Event

/** [userId] has read a chat up to [messageId]. */
@Serializable @SerialName("read")
data class ReadEvent(val conversationId: Long, val userId: Long, val messageId: Long) : Event

/** A group was deleted for everyone. */
@Serializable @SerialName("conversationRemoved")
data class ConversationRemovedEvent(val conversationId: Long) : Event

// --- Realtime Gateway Device & Remote Control events (Milestone 3) ---

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val type: String, // "web", "android", "desktop", "other"
    val isCurrent: Boolean = false,
    val isActive: Boolean = false,
    val playing: Boolean = false,
    val song: SongRef? = null,
    val positionMs: Long = 0,
    val volume: Float = 1f,
    val lastSeen: Long = 0,
)

@Serializable
data class DevicePlaybackState(
    val song: SongRef? = null,
    val queue: List<SongRef>? = null,
    val queueId: String? = null,
    val index: Int = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val volume: Float = 1f,
    val updatedAt: Long = 0,
)

/** The list of connected devices for the current user and which one is active. */
@Serializable @SerialName("devices")
data class DevicesEvent(
    val activeDeviceId: String?,
    val devices: List<DeviceDto>,
) : Event

/** A remote control command sent to this device from another device of the same user. */
@Serializable @SerialName("remoteCommand")
data class RemoteCommandEvent(
    val commandId: String,
    val action: String, // "play", "pause", "next", "previous", "seek", "volume", "sync"
    val positionMs: Long? = null,
    val volume: Float? = null,
    val song: SongRef? = null,
    val queue: List<SongRef>? = null,
    val index: Int? = null,
    val byDeviceId: String? = null,
) : Event

/** Transferred playback from another device: start playing this state. */
@Serializable @SerialName("transferPlayback")
data class TransferPlaybackEvent(
    val state: DevicePlaybackState,
    val fromDeviceId: String,
) : Event

/** Events the app sends to the server. */
@Serializable
sealed interface ClientEvent

@Serializable @SerialName("nowPlaying")
data class NowPlayingUpdate(val song: SongRef? = null) : ClientEvent

/** You're typing in a chat (at most every few seconds). */
@Serializable @SerialName("typing")
data class TypingUpdate(val conversationId: Long) : ClientEvent

/** Whether the app is on screen. Push notifications go to people who don't have it open. */
@Serializable @SerialName("appState")
data class AppStateUpdate(val visible: Boolean) : ClientEvent

/** This device's playback changed (Milestone 3). */
@Serializable @SerialName("devicePlayback")
data class DevicePlaybackUpdate(val playback: DevicePlaybackState) : ClientEvent

/** Send a remote playback command to one of your other devices (Milestone 3). */
@Serializable @SerialName("remoteCommand")
data class RemoteCommand(
    val targetDeviceId: String,
    val action: String, // "play", "pause", "next", "previous", "seek", "volume"
    val positionMs: Long? = null,
    val volume: Float? = null,
    val song: SongRef? = null,
    val queue: List<SongRef>? = null,
    val index: Int? = null,
) : ClientEvent

/** Transfer playback from one device to another (Milestone 3). */
@Serializable @SerialName("transferPlayback")
data class TransferPlayback(
    val toDeviceId: String,
) : ClientEvent

/** Manually set which device is the active player (Milestone 3). */
@Serializable @SerialName("setActiveDevice")
data class SetActiveDevice(
    val deviceId: String,
) : ClientEvent

val eventJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Realtime Gateway: tracks connected devices, what they are playing, and delivers events.
 * A user counts as online while at least one device has the WebSocket open.
 */
class Hub(private val friendsOf: suspend (Long) -> List<Long>) {
    data class DeviceSession(
        val deviceId: String,
        val userId: Long,
        val deviceName: String,
        val clientType: String,
        val session: WebSocketSession,
        val isExplicitDevice: Boolean,
        var visible: Boolean = false,
        var playback: DevicePlaybackState? = null,
        val connectedAt: Long = System.currentTimeMillis(),
        var lastSeen: Long = System.currentTimeMillis(),
    )

    private val userDevices = ConcurrentHashMap<Long, ConcurrentHashMap<String, DeviceSession>>()
    private val activeDevices = ConcurrentHashMap<Long, String>()
    private val nowPlaying = ConcurrentHashMap<Long, SongRef>()
    private val lock = Mutex()

    fun isOnline(userId: Long) = userDevices[userId]?.isNotEmpty() == true

    /** True if one of the user's devices has the app on screen right now. */
    fun isVisible(userId: Long) = userDevices[userId]?.values?.any { it.visible } == true

    fun nowPlaying(userId: Long): SongRef? = nowPlaying[userId]

    fun activeDeviceId(userId: Long): String? = activeDevices[userId]

    private fun devicesList(userId: Long, forDeviceId: String?): List<DeviceDto> {
        val active = activeDevices[userId]
        return userDevices[userId]?.values?.map { d ->
            DeviceDto(
                id = d.deviceId,
                name = d.deviceName,
                type = d.clientType,
                isCurrent = d.deviceId == forDeviceId,
                isActive = d.deviceId == active,
                playing = d.playback?.playing == true,
                song = d.playback?.song,
                positionMs = d.playback?.positionMs ?: 0L,
                volume = d.playback?.volume ?: 1f,
                lastSeen = d.lastSeen,
            )
        }.orEmpty()
    }

    private suspend fun pushDevices(userId: Long) {
        val devices = userDevices[userId]?.values ?: return
        val active = activeDevices[userId]
        val textCache = mutableMapOf<String, String>()
        for (d in devices) {
            if (!d.isExplicitDevice) continue // Only push DevicesEvent to clients that opted into device awareness
            val event = DevicesEvent(activeDeviceId = active, devices = devicesList(userId, d.deviceId))
            val text = textCache.getOrPut(d.deviceId) { eventJson.encodeToString(Event.serializer(), event) }
            runCatching { d.session.send(Frame.Text(text)) }
        }
    }

    suspend fun connected(
        userId: Long,
        session: WebSocketSession,
        deviceId: String = "legacy-${System.identityHashCode(session)}",
        deviceName: String = "Device",
        clientType: String = "other",
        isExplicitDevice: Boolean = false,
    ) {
        val cameOnline = lock.withLock {
            val map = userDevices.getOrPut(userId) { ConcurrentHashMap() }
            val dev = DeviceSession(deviceId, userId, deviceName, clientType, session, isExplicitDevice)
            map[deviceId] = dev
            if (activeDevices[userId] == null) {
                activeDevices[userId] = deviceId
            }
            map.size == 1
        }
        if (cameOnline) announcePresence(userId)
        pushDevices(userId)
    }

    suspend fun disconnected(userId: Long, session: WebSocketSession, deviceId: String? = null) {
        val wentOffline = lock.withLock {
            val map = userDevices[userId] ?: return
            val key = deviceId ?: map.entries.firstOrNull { it.value.session == session }?.key ?: return
            map.remove(key)
            if (activeDevices[userId] == key) {
                val nextActive = map.values.firstOrNull { it.playback?.playing == true }?.deviceId
                    ?: map.keys.firstOrNull()
                if (nextActive != null) {
                    activeDevices[userId] = nextActive
                } else {
                    activeDevices.remove(userId)
                }
            }
            if (map.isEmpty()) {
                userDevices.remove(userId)
                activeDevices.remove(userId)
                nowPlaying.remove(userId)
                true
            } else {
                false
            }
        }
        if (wentOffline) announcePresence(userId) else pushDevices(userId)
    }

    suspend fun handle(userId: Long, session: WebSocketSession, event: ClientEvent, deviceId: String? = null) {
        val dev = userDevices[userId]?.let { map ->
            (deviceId?.let { map[it] } ?: map.values.firstOrNull { it.session == session })
        }
        dev?.lastSeen = System.currentTimeMillis()

        when (event) {
            is AppStateUpdate -> {
                dev?.visible = event.visible
            }
            is NowPlayingUpdate -> {
                if (event.song == null) {
                    nowPlaying.remove(userId)
                    dev?.playback = dev?.playback?.copy(song = null, playing = false)
                } else {
                    nowPlaying[userId] = event.song
                    dev?.playback = (dev?.playback ?: DevicePlaybackState()).copy(song = event.song, playing = true)
                    dev?.let { activeDevices[userId] = it.deviceId }
                }
                announcePresence(userId)
                pushDevices(userId)
            }
            is DevicePlaybackUpdate -> {
                dev?.playback = event.playback
                if (event.playback.playing) {
                    dev?.let { activeDevices[userId] = it.deviceId }
                    event.playback.song?.let { nowPlaying[userId] = it }
                    announcePresence(userId)
                } else if (activeDevices[userId] == dev?.deviceId) {
                    nowPlaying.remove(userId)
                    announcePresence(userId)
                }
                pushDevices(userId)
            }
            is SetActiveDevice -> {
                if (userDevices[userId]?.containsKey(event.deviceId) == true) {
                    activeDevices[userId] = event.deviceId
                    pushDevices(userId)
                }
            }
            is RemoteCommand -> {
                val target = userDevices[userId]?.get(event.targetDeviceId)
                if (target != null) {
                    val cmd = RemoteCommandEvent(
                        commandId = UUID.randomUUID().toString(),
                        action = event.action,
                        positionMs = event.positionMs,
                        volume = event.volume,
                        song = event.song,
                        queue = event.queue,
                        index = event.index,
                        byDeviceId = dev?.deviceId,
                    )
                    val text = eventJson.encodeToString(Event.serializer(), cmd)
                    runCatching { target.session.send(Frame.Text(text)) }
                }
            }
            is TransferPlayback -> {
                val target = userDevices[userId]?.get(event.toDeviceId)
                val current = activeDevices[userId]?.let { userDevices[userId]?.get(it) } ?: dev
                val currentState = current?.playback ?: DevicePlaybackState()
                if (target != null) {
                    if (current != null && current.deviceId != target.deviceId && current.playback?.playing == true) {
                        val pauseCmd = RemoteCommandEvent(
                            commandId = UUID.randomUUID().toString(),
                            action = "pause",
                            byDeviceId = dev?.deviceId,
                        )
                        val pauseText = eventJson.encodeToString(Event.serializer(), pauseCmd)
                        runCatching { current.session.send(Frame.Text(pauseText)) }
                    }
                    val transfer = TransferPlaybackEvent(
                        state = currentState.copy(playing = true),
                        fromDeviceId = current?.deviceId ?: "unknown",
                    )
                    val transferText = eventJson.encodeToString(Event.serializer(), transfer)
                    runCatching { target.session.send(Frame.Text(transferText)) }
                    activeDevices[userId] = target.deviceId
                    pushDevices(userId)
                }
            }
            else -> Unit // listen-together events are handled by ListenTogether
        }
    }

    /** Closes every connection of a user (their account was removed). */
    suspend fun kick(userId: Long) {
        val sessions = lock.withLock {
            val devs = userDevices.remove(userId)?.values?.toList().orEmpty()
            activeDevices.remove(userId)
            nowPlaying.remove(userId)
            devs
        }
        sessions.forEach { runCatching { it.session.close(CloseReason(CloseReason.Codes.NORMAL, "Account removed")) } }
    }

    /** Sends [event] to every connected device of [userIds]. Returns the users who were offline. */
    suspend fun send(userIds: Collection<Long>, event: Event): List<Long> {
        val text = eventJson.encodeToString(Event.serializer(), event)
        val offline = mutableListOf<Long>()
        for (id in userIds.toSet()) {
            val sessions = userDevices[id]?.values?.map { it.session }.orEmpty()
            if (sessions.isEmpty()) offline += id
            sessions.forEach { runCatching { it.send(Frame.Text(text)) } }
        }
        return offline
    }

    private suspend fun announcePresence(userId: Long) {
        send(friendsOf(userId), PresenceEvent(userId, isOnline(userId), nowPlaying[userId]))
    }
}

