package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.ApiError
import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.insert
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.queryOne
import io.github.devasenan134.jukebox.server.update
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    /** Its own cover, if its owner set one (an artwork id). */
    val coverId: String? = null,
)

/** A song to keep in a new playlist although the library doesn't have it: before the song at [slot]. */
class NewMissing(val slot: Int, val trackJson: String, val notIds: List<String> = emptyList(), val requestId: Long? = null)

/** A missing song of a playlist as kept: where, what (JSON), the songs not to match it to, its request, when last looked for. */
class MissingRow(val id: Long, val slot: Int, val trackJson: String, val notIds: List<String>, val requestId: Long?, val checkedVersion: String?)

/** Where missing songs go when a playlist's songs change. A slot is the position of the song a missing one is before. */
object MissingSlots {
    /** After the songs were reordered or trimmed: before the same song again; at the end if it's gone and nothing followed. */
    fun reordered(before: List<String>, after: List<String>, slot: Int): Int {
        // The song it was before, or the next one that's still there.
        for (i in slot until before.size) after.indexOf(before[i]).takeIf { it >= 0 }?.let { return it }
        return after.size
    }

    /** Puts the songs found for missing ones ([fills]) in their place; the new songs and the remaining gaps' slots. */
    fun fill(songs: List<String>, gaps: List<Pair<Long, Int>>, fills: Map<Long, String>): Pair<List<String>, List<Pair<Long, Int>>> {
        val out = mutableListOf<String>()
        val slots = mutableListOf<Pair<Long, Int>>()
        val sorted = gaps.sortedWith(compareBy({ it.second }, { it.first }))
        var g = 0
        fun gapsBefore(i: Int) {
            while (g < sorted.size && (sorted[g].second <= i || i == songs.size)) {
                val (gap, _) = sorted[g++]
                fills[gap]?.let { out += it } ?: run { slots += gap to out.size }
            }
        }
        songs.forEachIndexed { i, s -> gapsBefore(i); out += s }
        gapsBefore(songs.size)
        return out to slots
    }
}

/**
 * Likes, play counts and playlists: what people do with the music. Every change is also written to the
 * event log. Ids of merged recordings and people are followed to the one kept.
 */
class Listening(private val db: Db, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun personal(userId: Long): Personal = db.read {
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

    /** A new playlist of [songIds], and with [missing] the songs it should have that the library doesn't (yet). */
    suspend fun create(userId: Long, name: String, songIds: List<String>, missing: List<NewMissing> = emptyList()): PlaylistRow = db.tx {
        val id = newId()
        val t = clock()
        insert("INSERT INTO playlists (id, owner_id, name, public, created_at, changed_at) VALUES (?, ?, ?, 0, ?, ?)", id, userId, name.trim().take(100).ifEmpty { "New playlist" }, t, t)
        songIds.forEachIndexed { i, s -> update("INSERT INTO playlist_entries VALUES (?, ?, ?, ?)", id, i, recording(s), t) }
        missing.forEach { m ->
            insert(
                "INSERT INTO playlist_missing (playlist_id, slot, track_json, not_ids, request_id) VALUES (?, ?, ?, ?, ?)",
                id, m.slot.coerceIn(0, songIds.size), m.trackJson, Json.encodeToString(m.notIds), m.requestId,
            )
        }
        event(userId, "playlist_created", t) { put("playlist", id); put("songs", songIds.size); if (missing.isNotEmpty()) put("missing", missing.size) }
        load(id)!!
    }

    /** Replaces a playlist's songs (createPlaylist with an existing playlistId). */
    suspend fun replace(userId: Long, id: String, songIds: List<String>): PlaylistRow = db.tx {
        own(userId, id)
        val t = clock()
        val before = entries(id)
        val after = songIds.map { recording(it) }
        update("DELETE FROM playlist_entries WHERE playlist_id = ?", id)
        after.forEachIndexed { i, s -> update("INSERT INTO playlist_entries VALUES (?, ?, ?, ?)", id, i, s, t) }
        // Missing songs stay before the song they were before, wherever it went.
        gaps(id).forEach { (gap, slot) -> moveGap(gap, MissingSlots.reordered(before, after, slot)) }
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
            val removed = removePositions.toSet().filter { it in songs.indices }
            removed.sortedDescending().forEach { songs.removeAt(it) }
            songs += add.map { recording(it) }
            update("DELETE FROM playlist_entries WHERE playlist_id = ?", id)
            songs.forEachIndexed { i, s -> update("INSERT INTO playlist_entries VALUES (?, ?, ?, ?)", id, i, s, t) }
            if (removed.isNotEmpty()) gaps(id).forEach { (gap, slot) -> moveGap(gap, slot - removed.count { it < slot }) }
        }
        update("UPDATE playlists SET changed_at = ? WHERE id = ?", t, id)
        event(userId, "playlist_changed", t) { put("playlist", id); put("added", add.size); put("removed", removePositions.size) }
    }

    suspend fun delete(userId: Long, id: String) = db.tx {
        own(userId, id)
        update("DELETE FROM playlists WHERE id = ?", id)
        // Gone for everyone who liked it too, or it would stay in their library and fail to open.
        update("DELETE FROM liked_playlists WHERE playlist_id = ?", id)
        event(userId, "playlist_deleted") { put("playlist", id) }
    }

    // ---------- songs of a playlist the library doesn't have ----------

    /** The playlist's missing songs, in order. */
    suspend fun missing(playlistId: String): List<MissingRow> = db.read {
        query(
            "SELECT id, slot, track_json, not_ids, request_id, checked_version FROM playlist_missing WHERE playlist_id = ? ORDER BY slot, id", playlistId,
        ) { MissingRow(it.getLong(1), it.getInt(2), it.getString(3), Json.decodeFromString(it.getString(4)), (it.getObject(5) as Number?)?.toLong(), it.getString(6)) }
    }

    /** Remembers the request made for a missing song. */
    suspend fun missingRequested(playlistId: String, missingId: Long, requestId: Long) = db.tx {
        update("UPDATE playlist_missing SET request_id = ? WHERE id = ? AND playlist_id = ?", requestId, missingId, playlistId)
    }

    /** The library at [version] was looked through for these missing songs. */
    suspend fun missingChecked(ids: List<Long>, version: String) = db.tx {
        ids.forEach { update("UPDATE playlist_missing SET checked_version = ? WHERE id = ?", version, it) }
    }

    /** Takes a missing song out of a playlist [userId] made. */
    suspend fun removeMissing(userId: Long, playlistId: String, missingId: Long) = db.tx {
        own(userId, playlistId)
        if (update("DELETE FROM playlist_missing WHERE id = ? AND playlist_id = ?", missingId, playlistId) == 0) throw ApiError(HttpStatusCode.NotFound, "That song isn't in the playlist")
        update("UPDATE playlists SET changed_at = ? WHERE id = ?", clock(), playlistId)
    }

    /** Missing songs that are in the library now ([fills]: missing id to song id) become songs of the playlist, in their place. */
    suspend fun fillMissing(playlistId: String, fills: Map<Long, String>) = db.tx {
        val ownerId = queryOne("SELECT owner_id FROM playlists WHERE id = ?", playlistId) { it.getLong(1) } ?: return@tx
        val gaps = gaps(playlistId)
        val known = fills.filterKeys { k -> gaps.any { it.first == k } }.mapValues { recording(it.value) }
        if (known.isEmpty()) return@tx
        val t = clock()
        val (songs, slots) = MissingSlots.fill(entries(playlistId), gaps, known)
        update("DELETE FROM playlist_entries WHERE playlist_id = ?", playlistId)
        songs.forEachIndexed { i, s -> update("INSERT INTO playlist_entries VALUES (?, ?, ?, ?)", playlistId, i, s, t) }
        known.keys.forEach { update("DELETE FROM playlist_missing WHERE id = ?", it) }
        slots.forEach { (gap, slot) -> moveGap(gap, slot) }
        update("UPDATE playlists SET changed_at = ? WHERE id = ?", t, playlistId)
        event(ownerId, "playlist_changed", t) { put("playlist", playlistId); put("filled", known.size) }
    }

    private fun Connection.entries(id: String): List<String> =
        query("SELECT recording_id FROM playlist_entries WHERE playlist_id = ? ORDER BY position", id) { it.getString(1) }

    /** Missing song ids and their slots, in order. */
    private fun Connection.gaps(id: String): List<Pair<Long, Int>> =
        query("SELECT id, slot FROM playlist_missing WHERE playlist_id = ? ORDER BY slot, id", id) { it.getLong(1) to it.getInt(2) }

    private fun Connection.moveGap(gap: Long, slot: Int) = update("UPDATE playlist_missing SET slot = ? WHERE id = ?", maxOf(0, slot), gap)

    private fun Connection.own(userId: Long, id: String) {
        val owner = queryOne("SELECT owner_id FROM playlists WHERE id = ?", id) { it.getLong(1) } ?: notFound()
        if (owner != userId) throw ApiError(HttpStatusCode.Forbidden, "Only its owner can change a playlist")
    }

    private fun Connection.load(id: String): PlaylistRow? = queryOne(
        "SELECT p.id, p.owner_id, u.username, p.name, p.comment, p.public, p.created_at, p.changed_at, p.cover_id FROM playlists p JOIN users u ON u.id = p.owner_id WHERE p.id = ?", id,
    ) { PlaylistRow(it.getString(1), it.getLong(2), it.getString(3), it.getString(4), it.getString(5), it.getInt(6) == 1, it.getLong(7), it.getLong(8), emptyList(), it.getString(9)) }
        ?.let { p -> PlaylistRow(p.id, p.ownerId, p.owner, p.name, p.comment, p.public, p.created, p.changed,
            query("SELECT recording_id FROM playlist_entries WHERE playlist_id = ? ORDER BY position", id) { it.getString(1) }.map { recording(it) }, p.coverId) }

    /** Whether [id] (or the old id it was imported from) is a playlist Jukebox keeps. */
    suspend fun exists(id: String): Boolean = db.read { queryOne("SELECT 1 FROM playlists WHERE id = ?", mapped(id)) { 1 } != null }

    /** Sets (or with null, removes) the cover of a playlist [userId] made; [store] keeps the picture and gives its artwork id. */
    suspend fun setCover(userId: Long, requested: String, store: Connection.() -> String?) = db.tx {
        val id = mapped(requested)
        own(userId, id)
        val t = clock()
        update("UPDATE playlists SET cover_id = ?, changed_at = ? WHERE id = ?", store(), t, id)
        event(userId, "playlist_changed", t) { put("playlist", id); put("cover", true) }
    }

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
