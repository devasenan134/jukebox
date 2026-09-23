package io.github.devasenan134.isaipetti.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// These mirror the companion server's JSON (server/src/.../Models.kt and Hub.kt).

@Serializable
data class SocialUser(val id: Long, val username: String, val displayName: String)

/**
 * A song as shared between friends. Navidrome ids are the same for everyone, so anyone can play it.
 * A shared clip also has [clipStartMs] and [clipEndMs]: only that part of the song is meant to be heard.
 */
@Serializable
data class SongRef(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
) {
    val isClip get() = clipStartMs != null && clipEndMs != null

    /** " (1:05–1:35)" for a clip, "" for a whole song. */
    val clipLabel get() = if (isClip) " (${clockTime(clipStartMs!!)}–${clockTime(clipEndMs!!)})" else ""

    fun toSong() = Song(id = id, title = title, album = album, albumId = albumId, artist = artist, duration = duration, coverArt = coverArt)
}

fun Song.toRef() = SongRef(id, title, artist, album, albumId, coverArt, duration)

/** "1:05" */
fun clockTime(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

@Serializable data class SessionResponse(val sessionToken: String, val user: SocialUser)
@Serializable data class Invite(val code: String, val expiresAt: Long, val usedBy: SocialUser? = null)
@Serializable data class Friend(val user: SocialUser, val online: Boolean, val nowPlaying: SongRef? = null)
@Serializable data class FriendRequests(val incoming: List<SocialUser> = emptyList(), val outgoing: List<SocialUser> = emptyList())
@Serializable data class AddFriendResponse(val status: String)
@Serializable data class BugReport(val number: Long, val url: String)

/** Firebase settings of a friends server's project (see push/PushSetup.kt). */
@Serializable data class PushConfig(val projectId: String, val appId: String, val apiKey: String, val senderId: String)

@Serializable
data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val sender: SocialUser,
    val body: String,
    val song: SongRef? = null,
    val createdAt: Long,
    /** A line about the chat itself, like "left the group". */
    val system: Boolean = false,
) {
    /** "Alice left the group" / "You left the group". */
    fun systemText(me: Long?) = (if (sender.id == me) "You" else sender.displayName) + " " + body
}

@Serializable
data class Conversation(
    val id: Long,
    val kind: String,
    val name: String? = null,
    val members: List<SocialUser>,
    val lastMessage: ChatMessage? = null,
    val unread: Int = 0,
    /** False for a DM with someone who left or is no longer a friend, or a group everyone else left. Such chats can be deleted. */
    val canMessage: Boolean = true,
    /** Who is listening together in this chat right now. */
    val listeners: List<Long> = emptyList(),
    /** The group's owner, the only one who can delete it for everyone. */
    val createdBy: Long? = null,
) {
    val isGroup get() = kind == "group"

    /** Group name, or the other person's name for a DM. */
    fun title(me: Long?): String = if (isGroup) name.orEmpty() else members.firstOrNull { it.id != me }?.displayName ?: "Chat"
}

/** Live events from the server's WebSocket. */
@Serializable
sealed interface SocialEvent

@Serializable @SerialName("presence")
data class PresenceEvent(val userId: Long, val online: Boolean, val nowPlaying: SongRef? = null) : SocialEvent

@Serializable @SerialName("message")
data class MessageEvent(val message: ChatMessage) : SocialEvent

@Serializable @SerialName("friendRequest")
data class FriendRequestEvent(val from: SocialUser) : SocialEvent

@Serializable @SerialName("friendAdded")
data class FriendAddedEvent(val friend: Friend) : SocialEvent

@Serializable @SerialName("friendRemoved")
data class FriendRemovedEvent(val userId: Long) : SocialEvent

/** A group was deleted for everyone. */
@Serializable @SerialName("conversationRemoved")
data class ConversationRemovedEvent(val conversationId: Long) : SocialEvent

/**
 * What a listen-together session is playing: a shared queue, which song in it, where in the song
 * (at [updatedAt], server time) and whether it's playing. Updates leave out [queue] when it didn't change.
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

/** Who is listening together in a chat; an empty list means the session ended. */
@Serializable @SerialName("listenSession")
data class ListenSessionEvent(val conversationId: Long, val listeners: List<Long>) : SocialEvent

/** The session's playback changed (by [by]), or we just joined. */
@Serializable @SerialName("listenState")
data class ListenStateEvent(val conversationId: Long, val state: ListenState, val by: Long, val serverTime: Long) : SocialEvent

/** What the app sends over the WebSocket. */
@Serializable
sealed interface ClientEvent

@Serializable @SerialName("nowPlaying")
data class NowPlayingUpdate(val song: SongRef? = null) : ClientEvent

/** Tells the server whether the app is on screen, so it knows when to send push notifications instead. */
@Serializable @SerialName("appState")
data class AppStateUpdate(val visible: Boolean) : ClientEvent

@Serializable @SerialName("listenStart")
data class ListenStart(val conversationId: Long, val state: ListenState) : ClientEvent

@Serializable @SerialName("listenJoin")
data class ListenJoin(val conversationId: Long) : ClientEvent

@Serializable @SerialName("listenLeave")
data class ListenLeave(val conversationId: Long) : ClientEvent

@Serializable @SerialName("listenUpdate")
data class ListenUpdate(val conversationId: Long, val state: ListenState) : ClientEvent
