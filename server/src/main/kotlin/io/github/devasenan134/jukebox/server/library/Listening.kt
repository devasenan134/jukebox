package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.ApiError
import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.insert
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.queryOne
import io.github.devasenan134.jukebox.server.update
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection

/** One thing that happened, for the event log (docs/milestone-2.md). Write it in the same transaction as the change. */
fun Connection.event(userId: Long?, type: String, at: Long = System.currentTimeMillis(), payload: JsonObjectBuilder.() -> Unit = {}) {
    update("INSERT INTO events (user_id, type, at, payload) VALUES (?, ?, ?, ?)", userId, type, at, buildJsonObject(payload).toString())
}

/** A person's likes and play counts, loaded once per request to mark songs and albums. */
class Personal(
    val likedSongs: Map<String, Long> = emptyMap(),
    val likedAlbums: Map<String, Long> = emptyMap(),
    val likedPeople: Map<String, Long> = emptyMap(),
    val plays: Map<String, Pair<Int, Long>> = emptyMap(),
) {
    companion object {
        val NOBODY = Personal()
    }
}

class PlaylistRow(
    val id: String, val ownerId: Long, val owner: String, val name: String, val comment: String?, val public: Boolean,
    val created: Long, val changed: Long, val songIds: List<String>,
)

/**
 * Likes, play counts and playlists: what people do with the music. Every change is also written to the
 * event log. Ids of merged recordings and people are followed to the one kept.
 */
class Listening(private val db: Db, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun personal(userId: Long): Personal = db.tx {
        val likes = query("SELECT item_type, item_id, liked_at FROM likes WHERE user_id = ?", userId) { Triple(it.getString(1), it.getString(2), it.getLong(3)) }
        Personal(
            likedSongs = likes.filter { it.first == "recording" }.associate { it.second to it.third },
            likedAlbums = likes.filter { it.first == "album" }.associate { it.second to it.third },
            likedPeople = likes.filter { it.first == "person" }.associate { it.second to it.third },
            plays = query("SELECT recording_id, count, last_played FROM play_counts WHERE user_id = ?", userId) {
                it.getString(1) to (it.getInt(2) to it.getLong(3))
            }.toMap(),
        )
    }

    /** Likes or unlikes songs, albums (album view ids) and people. */
    suspend fun like(userId: Long, songs: List<String>, albums: List<String>, people: List<String>, liked: Boolean) = db.tx {
        val t = clock()
        fun apply(type: String, id: String) {
            if (liked) update("INSERT OR IGNORE INTO likes (user_id, item_type, item_id, liked_at) VALUES (?, ?, ?, ?)", userId, type, id, t)
            else update("DELETE FROM likes WHERE user_id = ? AND item_type = ? AND item_id = ?", userId, type, id)
            event(userId, if (liked) "liked" else "unliked", t) { put("type", type); put("id", id) }
        }
        songs.forEach { apply("recording", recording(it)) }
        albums.forEach { apply("album", mapped(it)) }
        people.forEach { apply("person", person(it)) }
    }

    /** A song played to the end (the player's scrobble). */
    suspend fun played(userId: Long, songId: String, at: Long) = db.tx {
        val id = recording(songId)
        if (queryOne("SELECT 1 FROM recordings WHERE id = ?", id) { 1 } == null) return@tx
        update(
            """INSERT INTO play_counts (user_id, recording_id, count, last_played) VALUES (?, ?, 1, ?)
               ON CONFLICT (user_id, recording_id) DO UPDATE SET count = count + 1, last_played = max(last_played, excluded.last_played)""",
            userId, id, at,
        )
        event(userId, "played", at) { put("recording", id) }
    }

    // ---------- playlists ----------

    /** Your playlists and everyone's public ones. */
    suspend fun playlists(userId: Long): List<PlaylistRow> = db.tx {
        query("SELECT id FROM playlists WHERE owner_id = ? OR public = 1 ORDER BY changed_at DESC", userId) { it.getString(1) }.mapNotNull { load(it) }
    }

    suspend fun playlist(userId: Long, id: String): PlaylistRow = db.tx { load(mapped(id))?.takeIf { it.ownerId == userId || it.public } } ?: notFound()

    suspend fun create(userId: Long, name: String, songIds: List<String>): PlaylistRow = db.tx {
        val id = newId()
        val t = clock()
        insert("INSERT INTO playlists (id, owner_id, name, public, created_at, changed_at) VALUES (?, ?, ?, 0, ?, ?)", id, userId, name.trim().take(100).ifEmpty { "New playlist" }, t, t)
        songIds.forEachIndexed { i, s -> update("INSERT INTO playlist_entries VALUES (?, ?, ?, ?)", id, i, recording(s), t) }
        event(userId, "playlist_created", t) { put("playlist", id); put("songs", songIds.size) }
        load(id)!!
    }

    /** Replaces a playlist's songs (createPlaylist with an existing playlistId). */
    suspend fun replace(userId: Long, id: String, songIds: List<String>): PlaylistRow = db.tx {
        own(userId, id)
        val t = clock()
        update("DELETE FROM playlist_entries WHERE playlist_id = ?", id)
        songIds.forEachIndexed { i, s -> update("INSERT INTO playlist_entries VALUES (?, ?, ?, ?)", id, i, recording(s), t) }
        update("UPDATE playlists SET changed_at = ? WHERE id = ?", t, id)
        event(userId, "playlist_changed", t) { put("playlist", id) }
        load(id)!!
    }

    /** updatePlaylist: rename, describe, make public, add songs at the end, remove songs at positions. */
    suspend fun update(userId: Long, id: String, name: String?, comment: String?, public: Boolean?, add: List<String>, removePositions: List<Int>) = db.tx {
        own(userId, id)
        val t = clock()
        name?.let { update("UPDATE playlists SET name = ? WHERE id = ?", it.trim().take(100).ifEmpty { "Playlist" }, id) }
        comment?.let { update("UPDATE playlists SET comment = ? WHERE id = ?", it.take(1000), id) }
        public?.let { update("UPDATE playlists SET public = ? WHERE id = ?", if (it) 1 else 0, id) }
        if (add.isNotEmpty() || removePositions.isNotEmpty()) {
            val songs = query("SELECT recording_id FROM playlist_entries WHERE playlist_id = ? ORDER BY position", id) { it.getString(1) }.toMutableList()
            removePositions.toSet().sortedDescending().filter { it in songs.indices }.forEach { songs.removeAt(it) }
            songs += add.map { recording(it) }
            update("DELETE FROM playlist_entries WHERE playlist_id = ?", id)
            songs.forEachIndexed { i, s -> update("INSERT INTO playlist_entries VALUES (?, ?, ?, ?)", id, i, s, t) }
        }
        update("UPDATE playlists SET changed_at = ? WHERE id = ?", t, id)
        event(userId, "playlist_changed", t) { put("playlist", id); put("added", add.size); put("removed", removePositions.size) }
    }

    suspend fun delete(userId: Long, id: String) = db.tx {
        own(userId, id)
        update("DELETE FROM playlists WHERE id = ?", id)
        event(userId, "playlist_deleted") { put("playlist", id) }
    }

    private fun Connection.own(userId: Long, id: String) {
        val owner = queryOne("SELECT owner_id FROM playlists WHERE id = ?", id) { it.getLong(1) } ?: notFound()
        if (owner != userId) throw ApiError(HttpStatusCode.Forbidden, "Only its owner can change a playlist")
    }

    private fun Connection.load(id: String): PlaylistRow? = queryOne(
        "SELECT p.id, p.owner_id, u.username, p.name, p.comment, p.public, p.created_at, p.changed_at FROM playlists p JOIN users u ON u.id = p.owner_id WHERE p.id = ?", id,
    ) { PlaylistRow(it.getString(1), it.getLong(2), it.getString(3), it.getString(4), it.getString(5), it.getInt(6) == 1, it.getLong(7), it.getLong(8), emptyList()) }
        ?.let { p -> PlaylistRow(p.id, p.ownerId, p.owner, p.name, p.comment, p.public, p.created, p.changed,
            query("SELECT recording_id FROM playlist_entries WHERE playlist_id = ? ORDER BY position", id) { it.getString(1) }.map { recording(it) }) }

    private fun notFound(): Nothing = throw ApiError(HttpStatusCode.NotFound, "Playlist not found")

    /** The recording [id] stands for, following merges. */
    private fun Connection.recording(id: String): String {
        var current = mapped(id)
        repeat(10) { current = queryOne("SELECT merged_into FROM recordings WHERE id = ?", current) { it.getString(1) } ?: return current }
        return current
    }

    private fun Connection.person(id: String): String = mapped(id).let { m -> queryOne("SELECT coalesce(merged_into, id) FROM people WHERE id = ?", m) { it.getString(1) } ?: m }

    /** An id Navidrome gave out before the import, as the Jukebox id it became. */
    private fun Connection.mapped(id: String): String = queryOne("SELECT new_id FROM id_map WHERE old_id = ?", id) { it.getString(1) } ?: id
}

/** A small JSON object, for event payloads built outside a builder. */
fun jsonOf(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
