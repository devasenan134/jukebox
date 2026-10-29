package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One thing someone played: a song, or a movie, playlist, composer, artist, mix or Liked songs started as a whole.
 * The same shape the apps keep (RecentActivity.Item on Android, ActivityItem on the website).
 */
@Serializable
data class RecentItemDto(
    /** "Song", "Movie", "Playlist", "Composer", "Artist", "Liked" or "Mix". */
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val coverArt: String? = null,
    /** For a song: what's needed to play it again. */
    val song: SongRef? = null,
    val playedAt: Long = 0,
)

/** Where someone left off in a playlist, movie or Liked songs ([source] as the player names it, "playlist:<id>"). */
@Serializable
data class ResumePointDto(val source: String, val songIds: List<String>, val currentId: String, val savedAt: Long = 0)

/** Something in someone's recently played changed (on one of their devices): their other devices fetch it again. */
@Serializable @SerialName("recentsChanged")
data object RecentsChangedEvent : Event

/**
 * What each person played recently and where they left off, kept here so every device of theirs shows the same:
 * Recently played and Jump back in on Home, and Resume on a playlist, movie or Liked songs. (Each app used to keep
 * its own.) The newest [MAX_SONGS] songs and [MAX_OTHERS] other things are kept, and [MAX_RESUME] resume points.
 */
class Recents(private val db: Db, private val hub: Hub, private val clock: () -> Long = ::now) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(user: UserDto): List<RecentItemDto> = db.read {
        query(
            "SELECT kind, item_id, title, subtitle, cover_art, song_json, played_at FROM recent_items WHERE user_id = ? ORDER BY played_at DESC",
            user.id,
        ) { rs ->
            RecentItemDto(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6)?.let { runCatching { json.decodeFromString(SongRef.serializer(), it) }.getOrNull() }, rs.getLong(7),
            )
        }
    }

    /** Adds [item] as just played (or moves it to the top), then tells their devices. */
    suspend fun played(user: UserDto, item: RecentItemDto) {
        if (item.kind !in KINDS || item.id.isBlank() || item.id.length > 200 || item.title.length > 500) {
            throw ApiError(HttpStatusCode.BadRequest, "That isn't something that can be played")
        }
        val at = clock()
        db.tx {
            update(
                """INSERT OR REPLACE INTO recent_items (user_id, kind, item_id, title, subtitle, cover_art, song_json, played_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                user.id, item.kind, item.id, item.title, item.subtitle?.take(500), item.coverArt?.take(200),
                item.song?.let { json.encodeToString(SongRef.serializer(), it.copy(clipStartMs = null, clipEndMs = null)) }, at,
            )
            // Keep the newest songs and the newest of everything else.
            for ((condition, keep) in listOf("kind = 'Song'" to MAX_SONGS, "kind != 'Song'" to MAX_OTHERS)) {
                update(
                    """DELETE FROM recent_items WHERE user_id = ? AND $condition AND rowid NOT IN
                       (SELECT rowid FROM recent_items WHERE user_id = ? AND $condition ORDER BY played_at DESC LIMIT ?)""",
                    user.id, user.id, keep,
                )
            }
        }
        hub.send(listOf(user.id), RecentsChangedEvent)
    }

    suspend fun forget(user: UserDto, kind: String, id: String) {
        db.tx { update("DELETE FROM recent_items WHERE user_id = ? AND kind = ? AND item_id = ?", user.id, kind, id) }
        hub.send(listOf(user.id), RecentsChangedEvent)
    }

    suspend fun resumePoint(user: UserDto, source: String): ResumePointDto? = db.read {
        queryOne("SELECT song_ids, current_id, saved_at FROM resume_points WHERE user_id = ? AND source = ?", user.id, source) { rs ->
            ResumePointDto(source, runCatching { json.decodeFromString<List<String>>(rs.getString(1)) }.getOrDefault(emptyList()), rs.getString(2), rs.getLong(3))
        }
    }

    suspend fun saveResumePoint(user: UserDto, point: ResumePointDto) {
        if (point.source.isBlank() || point.source.length > 200 || point.songIds.isEmpty() || point.songIds.size > 5000) {
            throw ApiError(HttpStatusCode.BadRequest, "That isn't a place to resume from")
        }
        db.tx {
            update(
                "INSERT OR REPLACE INTO resume_points (user_id, source, song_ids, current_id, saved_at) VALUES (?, ?, ?, ?, ?)",
                user.id, point.source, json.encodeToString(point.songIds), point.currentId, clock(),
            )
            update(
                """DELETE FROM resume_points WHERE user_id = ? AND source NOT IN
                   (SELECT source FROM resume_points WHERE user_id = ? ORDER BY saved_at DESC LIMIT ?)""",
                user.id, user.id, MAX_RESUME,
            )
        }
    }

    suspend fun clear(user: UserDto) {
        db.tx {
            update("DELETE FROM recent_items WHERE user_id = ?", user.id)
            update("DELETE FROM resume_points WHERE user_id = ?", user.id)
        }
        hub.send(listOf(user.id), RecentsChangedEvent)
    }

    private companion object {
        val KINDS = setOf("Song", "Movie", "Playlist", "Composer", "Artist", "Liked", "Mix")
        const val MAX_SONGS = 30
        const val MAX_OTHERS = 40
        const val MAX_RESUME = 30
    }
}
