package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One album in a site's list, as the sync read it ("Jailer 2", 2026, "Anirudh Ravichander"). */
@Serializable
data class FeedEntry(
    val title: String,
    val year: Int? = null,
    val composer: String? = null,
    /** Its page on the site, and the site's poster. */
    val url: String? = null,
    val poster: String? = null,
    /** The album in the library, when the sync filed it (else it's looked up by title, year and composer). */
    val albumId: String? = null,
)

/** A list as the sync sends it: its name on Home and the albums, in the site's order. */
@Serializable
data class FeedUpdate(val title: String, val entries: List<FeedEntry>)

/** A list for Home: the albums of it the library has, in the site's order (Subsonic album entries, like getAlbumList2). */
@Serializable
data class FeedDto(
    val id: String,
    val title: String,
    /** When the list last changed on the site (not when it was last checked). */
    val changedAt: Long,
    val checkedAt: Long,
    val albums: List<JsonObject>,
    /** Everything on the site's list, in or not in the library yet (admins only). */
    val entries: List<FeedEntry>? = null,
)

/** A list changed: apps fetch /feeds again. */
@Serializable @SerialName("feedsChanged")
data object FeedsChangedEvent : Event

/**
 * Lists of albums from the music site the library is kept up to date from: "Trending" and "Latest" on masstamilan
 * (scripts/requests/fulfil.py feeds). The sync downloads what the library is missing, then sends each list here; Home
 * shows each as a row of the albums the library has, in the site's order. A list that changed is announced to
 * every connected app.
 */
class Feeds(
    private val db: Db,
    private val hub: Hub,
    private val isAdmin: suspend (UserDto) -> Boolean,
    /** The library's album for a title, year and composer (MusicRequests.findAlbumId). */
    private val findAlbum: suspend (String, Int?, String?) -> String?,
    private val hasAlbum: suspend (String) -> Boolean,
    /** Album entries for album ids, in that order (SubsonicLibrary.albumsById). */
    private val albums: suspend (List<String>) -> List<JsonObject>,
    private val clock: () -> Long = ::now,
) {
    /** Replaces list [id] with [update]; tells the apps if it changed. */
    suspend fun update(me: UserDto, id: String, update: FeedUpdate) {
        if (!isAdmin(me)) throw ApiError(HttpStatusCode.Forbidden, "Only admins can change Home's lists")
        if (!ID.matches(id)) throw ApiError(HttpStatusCode.BadRequest, "A list's id is a few letters, digits or dashes")
        if (update.title.isBlank() || update.title.length > 100 || update.entries.size > MAX_ENTRIES) {
            throw ApiError(HttpStatusCode.BadRequest, "A list has a name and at most $MAX_ENTRIES albums")
        }
        val entries = update.entries.filter { it.title.isNotBlank() }.map {
            it.copy(title = it.title.trim().take(300), composer = it.composer?.take(300), url = it.url?.take(500), poster = it.poster?.take(500), albumId = it.albumId?.take(64))
        }
        val now = clock()
        val changed = db.tx {
            val before = query("SELECT title, year, album_id FROM feed_entries WHERE feed_id = ? ORDER BY position", id) {
                Triple(it.getString(1), (it.getObject(2) as Number?)?.toInt(), it.getString(3))
            }
            val oldTitle = queryOne("SELECT title FROM feeds WHERE id = ?", id) { it.getString(1) }
            val changed = oldTitle == null || oldTitle != update.title || before != entries.map { Triple(it.title, it.year, it.albumId) }
            if (oldTitle == null) {
                val position = queryOne("SELECT coalesce(max(position), -1) + 1 FROM feeds") { it.getInt(1) } ?: 0
                insert("INSERT INTO feeds (id, title, position, changed_at, checked_at) VALUES (?, ?, ?, ?, ?)", id, update.title, position, now, now)
            } else {
                update("UPDATE feeds SET title = ?, checked_at = ? WHERE id = ?", update.title, now, id)
                if (changed) update("UPDATE feeds SET changed_at = ? WHERE id = ?", now, id)
            }
            if (changed) {
                update("DELETE FROM feed_entries WHERE feed_id = ?", id)
                entries.forEachIndexed { i, e ->
                    update("INSERT INTO feed_entries (feed_id, position, title, year, composer, url, poster, album_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        id, i, e.title, e.year, e.composer, e.url, e.poster, e.albumId)
                }
            }
            changed
        }
        if (changed) hub.sendToAll(FeedsChangedEvent)
    }

    /** Every list, with the albums of it the library has (admins also get the whole list). */
    suspend fun list(me: UserDto): List<FeedDto> {
        val admin = isAdmin(me)
        val feeds = db.read {
            query("SELECT id, title, changed_at, checked_at FROM feeds ORDER BY position") { rs ->
                FeedDto(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), emptyList())
            }.map { f ->
                f to query("SELECT title, year, composer, url, poster, album_id FROM feed_entries WHERE feed_id = ? ORDER BY position", f.id) { rs ->
                    FeedEntry(rs.getString(1), (rs.getObject(2) as Number?)?.toInt(), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))
                }
            }
        }
        return feeds.map { (feed, entries) ->
            // The album the sync filed it as, else the library's album of that name (it may have come another way).
            val ids = entries.mapNotNull { e -> e.albumId?.takeIf { hasAlbum(it) } ?: findAlbum(e.title, e.year, e.composer) }.distinct()
            feed.copy(albums = albums(ids), entries = entries.takeIf { admin })
        }
    }

    /** Removes list [id] (it's no longer synced). */
    suspend fun remove(me: UserDto, id: String) {
        if (!isAdmin(me)) throw ApiError(HttpStatusCode.Forbidden, "Only admins can change Home's lists")
        if (db.tx { update("DELETE FROM feeds WHERE id = ?", id) } > 0) hub.sendToAll(FeedsChangedEvent)
    }

    companion object {
        private val ID = Regex("^[a-z0-9-]{1,40}$")
        const val MAX_ENTRIES = 100
    }
}
