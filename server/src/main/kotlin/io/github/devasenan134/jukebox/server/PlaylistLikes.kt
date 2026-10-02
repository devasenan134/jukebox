package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A Navidrome playlist someone liked, with what the app needs to show it in Your Library. */
@Serializable
data class PlaylistRef(val id: String, val name: String = "", val coverArt: String? = null, val songCount: Int = 0)

/**
 * Liked playlists, saved per person. Navidrome can like ("star") songs and albums but not
 * playlists, so this server keeps them, and they follow you to every phone.
 */
class PlaylistLikes(private val db: Db) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Newest likes first. */
    suspend fun list(userId: Long): List<PlaylistRef> = db.tx {
        query("SELECT playlist_json FROM liked_playlists WHERE user_id = ? ORDER BY liked_at DESC", userId) {
            json.decodeFromString(PlaylistRef.serializer(), it.getString(1))
        }
    }

    suspend fun like(userId: Long, playlist: PlaylistRef) {
        val ref = playlist.copy(id = playlist.id.trim(), name = playlist.name.take(200), coverArt = playlist.coverArt?.take(200))
        if (ref.id.isEmpty() || ref.id.length > 100) throw ApiError(HttpStatusCode.BadRequest, "Unknown playlist")
        db.tx {
            val count = queryOne("SELECT count(*) FROM liked_playlists WHERE user_id = ?", userId) { it.getInt(1) } ?: 0
            val known = queryOne("SELECT 1 FROM liked_playlists WHERE user_id = ? AND playlist_id = ?", userId, ref.id) { true } != null
            if (!known && count >= MAX) throw ApiError(HttpStatusCode.BadRequest, "You can like up to $MAX playlists")
            // Liking again keeps its place but refreshes the name, cover and song count.
            update(
                """INSERT INTO liked_playlists (user_id, playlist_id, playlist_json, liked_at) VALUES (?, ?, ?, ?)
                   ON CONFLICT (user_id, playlist_id) DO UPDATE SET playlist_json = excluded.playlist_json""",
                userId, ref.id, json.encodeToString(PlaylistRef.serializer(), ref), now(),
            )
        }
    }

    /**
     * How many people liked each of these playlists, not counting [userId] (the app asks about
     * your own playlists, and your own like isn't news to you). Playlists nobody liked are 0.
     */
    suspend fun counts(userId: Long, playlistIds: List<String>): Map<String, Int> {
        val ids = playlistIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.size > MAX_COUNTS) throw ApiError(HttpStatusCode.BadRequest, "Ask about up to $MAX_COUNTS playlists at a time")
        if (ids.isEmpty()) return emptyMap()
        val found = db.tx {
            query(
                "SELECT playlist_id, count(*) FROM liked_playlists WHERE user_id != ? AND playlist_id IN (${ids.joinToString { "?" }}) GROUP BY playlist_id",
                userId, *ids.toTypedArray(),
            ) { it.getString(1) to it.getInt(2) }.toMap()
        }
        return ids.associateWith { found[it] ?: 0 }
    }

    suspend fun unlike(userId: Long, playlistId: String) = db.tx {
        update("DELETE FROM liked_playlists WHERE user_id = ? AND playlist_id = ?", userId, playlistId)
    }

    private companion object {
        const val MAX = 1_000
        const val MAX_COUNTS = 500
    }
}
