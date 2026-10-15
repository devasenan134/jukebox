package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.Listening
import io.github.devasenan134.jukebox.server.library.MissingRow
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("request-queue")

/** What became of a song someone asked to request: "queued", "requested", "in_library" or "not_found" (see [note]). */
@Serializable
data class QueuedRequest(val status: String = "queued", val note: String? = null)

/** A song in the queue, as kept. */
class QueuedSong(
    val id: Long, val userId: Long, val key: String, val albumKey: String?, val track: ImportedTrack,
    val status: String, val requestId: Long?, val albumChecked: Boolean, val note: String?,
)

/**
 * Asking for songs the library doesn't have. Every Request (one song, all of a playlist's, an import's) only adds
 * the songs here and answers at once; the server then goes through them one at a time:
 *
 * 1. Is it in the library now? Then it's filled in, no request.
 * 2. Did someone ask for it already? Then you join that request.
 * 3. Are two or more queued songs from the same album? Then the album is requested once, for all of them.
 * 4. Otherwise it's found in the music catalog (Apple's iTunes) and requested.
 *
 * The same song asked for twice by the same person is kept once. Apple answers about 20 calls a minute, so when
 * it's out of calls the queue just waits; nobody is told to slow down.
 */
class RequestQueue(
    private val db: Db,
    private val listening: Listening,
    private val requests: MusicRequests,
    private val matcher: suspend () -> ImportMatcher?,
    /** Finds a song in the catalog and requests it, held (PlaylistImports.request). */
    private val requestSong: suspend (UserDto, ImportedTrack) -> MusicRequestDto,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Adds [tracks] for [me] (each once). Ones asked for before keep what became of them, unless that was a dead end. */
    suspend fun add(me: UserDto, tracks: List<ImportedTrack>): List<QueuedSong> {
        if (tracks.isEmpty()) return emptyList()
        val had = find(me.id, tracks)
        // A declined request, or a song the catalog didn't have, is tried again.
        val declined = requests.byIds(me, had.values.mapNotNull { it.requestId }).filterValues { it.status == "declined" }.keys
        db.tx {
            tracks.distinctBy(::key).forEach { t ->
                val old = had[key(t)]
                when {
                    old == null -> insert(
                        "INSERT INTO request_queue (user_id, key, album_key, track_json, status, created_at) VALUES (?, ?, ?, ?, 'queued', ?)",
                        me.id, key(t), albumKey(t), json.encodeToString(ImportedTrack.serializer(), t), System.currentTimeMillis(),
                    )
                    old.status == "not_found" || old.requestId in declined -> update(
                        "UPDATE request_queue SET status = 'queued', request_id = NULL, note = NULL, album_checked = 0, created_at = ? WHERE id = ?",
                        System.currentTimeMillis(), old.id,
                    )
                }
            }
        }
        wake.trySend(Unit)
        val now = find(me.id, tracks)
        return tracks.map { now.getValue(key(it)) }
    }

    /** What became of these songs [userId] asked for (by their key), if they did. */
    suspend fun find(userId: Long, tracks: List<ImportedTrack>): Map<String, QueuedSong> {
        val keys = tracks.map(::key).distinct()
        return db.read {
            keys.chunked(400).flatMap { chunk ->
                query("SELECT $COLUMNS FROM request_queue WHERE user_id = ? AND key IN (${chunk.joinToString(",") { "?" }})", userId, *chunk.toTypedArray()) { row(it) }
            }
        }.associateBy { it.key }
    }

    /** Ties missing songs of a playlist to what became of the same songs in [me]'s queue. */
    suspend fun link(me: UserDto, rows: List<MissingRow>) {
        if (rows.isEmpty()) return
        val tracks = rows.associateWith { track(it.trackJson) }
        val queued = find(me.id, tracks.values.toList())
        rows.forEach { r ->
            val q = queued[key(tracks.getValue(r))] ?: return@forEach
            when (q.status) {
                "queued" -> listening.queueMissing(r.playlistId, listOf(r.id), me.id)
                "requested" -> listening.queueResult(r.id, requestId = q.requestId)
                "not_found" -> listening.queueResult(r.id, note = q.note)
                // In the library: the playlist fills it in the next time it's opened.
            }
        }
    }

    /** Works through the queue for as long as the server runs. */
    suspend fun run() {
        adoptOldRows()
        while (true) {
            val worked = runCatching { next() }.onFailure { log.warn("A queued request failed", it) }.getOrDefault(false)
            if (!worked) {
                // Nothing left waiting: the requests made show now, and admins hear about them once.
                runCatching { requests.releaseHeld() }.onFailure { log.warn("Releasing held requests failed", it) }
                withTimeoutOrNull(10 * 60_000L) { wake.receive() }
            }
        }
    }

    /** Missing songs queued before this queue existed (they only had queued_by) join it. */
    private suspend fun adoptOldRows() {
        val rows = listening.allQueued()
        if (rows.isEmpty()) return
        rows.groupBy { it.queuedBy!! }.forEach { (userId, mine) -> user(userId)?.let { add(it, mine.map { r -> track(r.trackJson) }) } }
    }

    /** Takes the next song in the queue as far as it goes; false if there's nothing to do. */
    suspend fun next(): Boolean {
        val entry = db.read { queryOne("SELECT $COLUMNS FROM request_queue WHERE status = 'queued' ORDER BY id LIMIT 1") { row(it) } } ?: return false
        val user = user(entry.userId) ?: return true.also { db.tx { update("DELETE FROM request_queue WHERE id = ?", entry.id) } }

        // 1. In the library now?
        inLibrary(entry)?.let { songId -> resolve(entry, "in_library", songId = songId); return true }

        // 2. Asked for already (by anyone)? Join that request.
        val earlier = db.read {
            query("SELECT request_id FROM request_queue WHERE key = ? AND request_id IS NOT NULL ORDER BY done_at DESC", entry.key) { it.getLong(1) }
        }
        earlier.firstNotNullOfOrNull { requests.join(user, it) }?.let { resolve(entry, "requested", requestId = it.id); return true }

        // 3. Two or more songs from the same album: ask for the album once.
        if (!entry.albumChecked && entry.albumKey != null) {
            val group = db.read { query("SELECT $COLUMNS FROM request_queue WHERE status = 'queued' AND album_key = ?", entry.albumKey) { row(it) } }
            val missing = group.filter { e -> inLibrary(e)?.let { resolve(e, "in_library", songId = it) } == null }
            if (missing.map { it.key }.distinct().size >= 2) {
                val album = try {
                    requestAlbum(user, entry.track.album!!)
                } catch (e: ApiError) {
                    if (e.status != HttpStatusCode.ServiceUnavailable) throw e
                    delay(BUSY_WAIT_MS) // Apple's calls for this minute are used up: try again shortly
                    return true
                }
                if (album != null) {
                    log.info("${user.username}: ${missing.size} songs of ${album.item.title} requested as the album")
                    missing.forEach { e ->
                        val r = if (e.userId == user.id) album else user(e.userId)?.let { requests.join(it, album.id) }
                        resolve(e, "requested", requestId = (r ?: album).id)
                    }
                    return true
                }
            }
            db.tx { group.forEach { update("UPDATE request_queue SET album_checked = 1 WHERE id = ?", it.id) } }
        }

        // 4. The song itself, from the catalog.
        try {
            resolve(entry, "requested", requestId = requestSong(user, entry.track).id)
        } catch (e: ApiError) {
            when (e.status) {
                HttpStatusCode.ServiceUnavailable -> delay(BUSY_WAIT_MS)
                HttpStatusCode.Conflict -> resolve(entry, "in_library") // the catalog says the library has it
                else -> resolve(entry, "not_found", note = e.message)
            }
        }
        return true
    }

    /** The movie album [album] in the catalog, requested (or joined, if someone asked already); null if it isn't there. */
    private suspend fun requestAlbum(user: UserDto, album: String): MusicRequestDto? {
        val name = ImportMatcher.cleanTitle(album).first
        val words = Fuzzy.words(name)
        val hit = requests.search(user, name).movies.firstOrNull { MusicRequests.same(words, Fuzzy.words(it.item.movie)) }
            ?: if (requests.catalogBusy()) throw ApiError(HttpStatusCode.ServiceUnavailable, MusicRequests.CATALOG_BUSY) else return null
        hit.request?.let { r -> requests.join(user, r.id)?.let { return it } }
        return try {
            requests.request(user, hit.item.id, hold = true)
        } catch (e: ApiError) {
            if (e.status == HttpStatusCode.ServiceUnavailable) throw e
            null // the album is in the library already (only some songs aren't): ask for the songs
        }
    }

    /** The library's song for [entry], if it surely has it. */
    private suspend fun inLibrary(entry: QueuedSong): String? =
        matcher()?.match(entry.track)?.takeIf { it.sure }?.match?.id

    /** Keeps what became of [entry], and tells the missing songs of its asker's playlists that are this song. */
    private suspend fun resolve(entry: QueuedSong, status: String, requestId: Long? = null, songId: String? = null, note: String? = null) {
        db.tx {
            update(
                "UPDATE request_queue SET status = ?, request_id = ?, note = ?, done_at = ? WHERE id = ?",
                status, requestId, note, System.currentTimeMillis(), entry.id,
            )
        }
        val rows = listening.allQueued().filter { it.queuedBy == entry.userId && key(track(it.trackJson)) == entry.key }
        when {
            requestId != null -> rows.forEach { listening.queueResult(it.id, requestId = requestId) }
            songId != null -> rows.groupBy { it.playlistId }.forEach { (playlist, mine) -> listening.fillMissing(playlist, mine.associate { it.id to songId }) }
            else -> rows.forEach { listening.queueResult(it.id, note = note) }
        }
    }

    private suspend fun user(id: Long): UserDto? = db.read { queryOne("SELECT * FROM users WHERE id = ? AND deleted_at IS NULL", id) { it.toUser() } }

    private fun track(text: String) = json.decodeFromString(ImportedTrack.serializer(), text)

    private fun row(it: java.sql.ResultSet) = QueuedSong(
        it.getLong(1), it.getLong(2), it.getString(3), it.getString(4), track(it.getString(5)), it.getString(6),
        (it.getObject(7) as Number?)?.toLong(), it.getInt(8) == 1, it.getString(9),
    )

    companion object {
        private const val COLUMNS = "id, user_id, key, album_key, track_json, status, request_id, album_checked, note"

        /** How long the queue waits when Apple has no calls left this minute. */
        var BUSY_WAIT_MS = 20_000L

        /** The album a song is from, in words ("Minsara Kanavu (Original Motion Picture Soundtrack)" is "minsara kanavu"). */
        fun albumKey(t: ImportedTrack): String? =
            t.album?.let { ImportMatcher.cleanTitle(it).first.substringBefore(" (Original") }?.let(Fuzzy::words)?.joinToString(" ")?.ifEmpty { null }

        /** One song, however it's written: its title's words, and its album's (or first artist's). */
        fun key(t: ImportedTrack): String {
            val title = Fuzzy.words(ImportMatcher.cleanTitle(t.title).first).joinToString(" ")
            val by = albumKey(t) ?: t.artists.firstOrNull()?.let(ImportMatcher::splitArtists)?.firstOrNull()?.let(Fuzzy::words)?.joinToString(" ").orEmpty()
            return "$title|$by"
        }
    }
}
