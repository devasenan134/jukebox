package io.github.devasenan134.isaipetti.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// These mirror the companion server's JSON (server/src/.../Models.kt and Hub.kt).

@Serializable
data class SocialUser(
    val id: Long,
    val username: String,
    val displayName: String,
    /** When their profile picture was set (its version), or null if they have none. */
    val avatar: Long? = null,
)

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
    /** For a song request while listening together: "pending", "accepted", "declined" or "expired". Null otherwise. */
    val request: String? = null,
    /** What a song request asks for: "next" (after the current song) or "now" (skip to it). */
    val requestMode: String? = null,
    /** The message this one replies to, quoted. */
    val replyTo: ReplyQuote? = null,
) {
    /** "Alice left the group" / "You left the group". */
    fun systemText(me: Long?) = (if (sender.id == me) "You" else sender.displayName) + " " + body

    /** What a reply to this message quotes. */
    fun quote() = ReplyQuote(id, sender, body, song)
}

/**
 * The message a reply quotes. [hidden] when it's from before you joined the group (or cleared the
 * chat): then you only see who wrote it.
 */
@Serializable
data class ReplyQuote(
    val id: Long,
    val sender: SocialUser,
    val body: String = "",
    val song: SongRef? = null,
    val hidden: Boolean = false,
) {
    /** One line about what it said: its text, or the song it shared. */
    val preview get() = when {
        hidden -> "Earlier message"
        body.isNotBlank() -> body
        song != null -> "♪ ${song.title}${song.clipLabel}"
        else -> ""
    }
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
    /** Who started (and controls) the listening session, if there is one. */
    val listenOwner: Long? = null,
    /** The group's owner, the only one who can delete it for everyone. */
    val createdBy: Long? = null,
    /** When the group's photo was set (its version), or null if it has none. */
    val picture: Long? = null,
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

/** Who is listening together in a chat, and who controls it; an empty list means the session ended. */
@Serializable @SerialName("listenSession")
data class ListenSessionEvent(val conversationId: Long, val listeners: List<Long>, val owner: Long? = null) : SocialEvent

/** A message changed after it was sent (a song request was accepted or declined). */
@Serializable @SerialName("messageUpdated")
data class MessageUpdatedEvent(val message: ChatMessage) : SocialEvent

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

// Admin listening stats (server: Stats.kt). Only Navidrome admins get these.

@Serializable data class AdminAccess(val isAdmin: Boolean = false)

@Serializable data class TopItem(val name: String, val detail: String? = null, val plays: Int, val coverArt: String? = null)

@Serializable
data class RangeStats(
    val hours: Double = 0.0,
    val plays: Int = 0,
    val topSongs: List<TopItem> = emptyList(),
    val topMovies: List<TopItem> = emptyList(),
    val topComposers: List<TopItem> = emptyList(),
)

@Serializable
data class UserStats(
    val username: String,
    val displayName: String,
    val lastPlayedAt: Long? = null,
    /** Keys: "today", "7d", "30d", "all". */
    val ranges: Map<String, RangeStats> = emptyMap(),
)

@Serializable data class DayHours(val date: String, val hours: Double)

@Serializable
data class ListeningStats(
    val generatedAt: Long,
    val users: List<UserStats> = emptyList(),
    val daily: List<DayHours> = emptyList(),
    val hourOfDay: List<Double> = emptyList(),
)

// Search by Isai Pettai (the friends server): forgives spelling, and knows lyricists and actors.

/** A composer, artist (singer), lyricist or actor. Actors' ids start with "actor-" (they aren't in Navidrome). */
@Serializable
data class PersonHit(
    val id: String,
    val name: String,
    /** "composer", "singer", "lyricist", "actor". */
    val roles: List<String> = emptyList(),
    val coverArt: String? = null,
    val songCount: Int = 0,
    val movieCount: Int = 0,
) {
    /** "Actor · Lyricist · 45 movies" */
    val description get() = (
        roles.map { when (it) { "composer" -> "Composer"; "singer" -> "Artist"; "lyricist" -> "Lyricist"; else -> "Actor" } } +
            listOfNotNull(
                if ("actor" in roles || "composer" in roles) movieCount.takeIf { it > 0 }?.let { if (it == 1) "1 movie" else "$it movies" }
                else songCount.takeIf { it > 0 }?.let { if (it == 1) "1 song" else "$it songs" },
            )
        ).joinToString(" · ")

    /** As an artist, for "Your recent artists"; lyricists and actors keep their roles. */
    fun toArtist() = Artist(
        id, name, coverArt = coverArt,
        roles = roles.map { when (it) { "composer" -> "albumartist"; "singer" -> "artist"; else -> it } },
    )
}

@Serializable
data class MovieHit(
    val id: String,
    val name: String,
    val year: Int? = null,
    val composer: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    /** Why it was found when it isn't the name, e.g. "Starring Vijay". */
    val reason: String? = null,
) {
    fun toAlbum() = Album(id = id, name = name, artist = reason ?: composer, coverArt = coverArt, songCount = songCount, year = year)
}

@Serializable
data class SongHit(val song: MixSong, val reason: String? = null)

@Serializable
data class LibrarySearchResults(
    val people: List<PersonHit> = emptyList(),
    val movies: List<MovieHit> = emptyList(),
    val songs: List<SongHit> = emptyList(),
)

/** A lyricist's or actor's page: the movies they wrote for or acted in, and the songs. */
@Serializable
data class PersonPage(val person: PersonHit, val movies: List<MovieHit> = emptyList(), val songs: List<MixSong> = emptyList())
