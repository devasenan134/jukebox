package io.github.devasenan134.jukebox.server.subsonic

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.Fuzzy
import io.github.devasenan134.jukebox.server.library.AudioTools
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.queryOne
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

/**
 * The catalog as Subsonic clients see it (the Jukebox app and web app today, other players too).
 *
 * Albums: each album answers as its songs (soundtrack, singles and other releases, as discs) under the
 * album's id, and its background score release, if any, as a second album under the release's id, named
 * "<album> (Original Background Score)". A song is a recording; its id never changes.
 */
class SubsonicLibrary(private val db: Db, private val tools: AudioTools, private val artworkDir: File, private val roots: () -> Map<Long, String>) {

    // ---------- albums ----------

    /** Every album view: (id, name, album id, release id for a score or null, year, cover, created, sort). */
    private val views = """
        SELECT a.id AS vid, a.title AS name, a.id AS album_id, NULL AS score_id, a.year, a.cover_id, a.created_at, a.sort_title
          FROM albums a WHERE EXISTS (SELECT 1 FROM releases rl JOIN tracks t ON t.release_id = rl.id JOIN files f ON f.track_id = t.id
                                      WHERE rl.album_id = a.id AND rl.kind != 'score' AND f.missing_since IS NULL)
        UNION ALL
        SELECT rl.id, a.title || ' (Original Background Score)', a.id, rl.id, a.year, coalesce(rl.cover_id, a.cover_id), a.created_at, a.sort_title || ' ~'
          FROM releases rl JOIN albums a ON a.id = rl.album_id
         WHERE rl.kind = 'score' AND EXISTS (SELECT 1 FROM tracks t JOIN files f ON f.track_id = t.id WHERE t.release_id = rl.id AND f.missing_since IS NULL)
    """.trimIndent()

    suspend fun albumList(type: String, size: Int, offset: Int, fromYear: Int?, toYear: Int?): List<JsonObject> = db.tx {
        val (where, order) = when (type) {
            "alphabeticalByName" -> "" to "sort_title, name"
            "newest" -> "" to "created_at DESC, sort_title"
            "random" -> "" to "random()"
            "byYear" -> {
                val (lo, hi) = (fromYear ?: 0) to (toYear ?: 9999)
                "WHERE year BETWEEN ${minOf(lo, hi)} AND ${maxOf(lo, hi)}" to (if ((fromYear ?: 0) > (toYear ?: 9999)) "year DESC" else "year") + ", sort_title"
            }
            // Plays and likes come with milestone 2: nothing is recent, frequent or starred yet.
            "recent", "frequent", "highest", "starred" -> return@tx emptyList()
            else -> "" to "sort_title, name"
        }
        query("SELECT vid FROM ($views) $where ORDER BY $order LIMIT ? OFFSET ?", size.coerceIn(1, 500), offset.coerceAtLeast(0)) { it.getString(1) }
            .mapNotNull { albumJson(it, withSongs = false) }
    }

    suspend fun album(id: String): JsonObject? = db.tx { albumJson(id, withSongs = true) }

    private fun Connection.albumJson(id: String, withSongs: Boolean): JsonObject? {
        val v = queryOne("SELECT vid, name, album_id, score_id, year, cover_id, created_at FROM ($views) WHERE vid = ?", id) {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getObject(5), it.getString(6), it.getLong(7))
        } ?: return null
        val (vid, name, albumId, scoreId) = v
        val songs = songsOf(albumId as String, scoreId as String?)
        val composers = query(
            "SELECT p.id, p.name FROM album_credits c JOIN people p ON p.id = c.person_id WHERE c.album_id = ? AND c.role = 'composer' ORDER BY c.position",
            albumId,
        ) { it.getString(1) to it.getString(2) }
        return buildJsonObject {
            put("id", vid as String)
            put("name", name as String)
            put("title", name)
            if (composers.isNotEmpty()) {
                put("artist", composers.joinToString(", ") { it.second })
                put("artistId", composers.first().first)
            }
            (v[5] as String?)?.let { put("coverArt", it) }
            put("songCount", songs.size)
            put("duration", songs.sumOf { it.durationMs / 1000 })
            (v[4] as Number?)?.let { put("year", it.toInt()) }
            put("created", iso(v[6] as Long))
            if (withSongs) putJsonArray("song") { songs.forEach { add(it.json()) } }
        }
    }

    // ---------- songs ----------

    /** One song as an album or search shows it. */
    private class SongRow(
        val id: String, val title: String, val albumViewId: String, val albumName: String, val year: Int?, val disc: Int, val track: Int,
        val durationMs: Long, val coverId: String?, val singers: List<Pair<String, String>>, val suffix: String?, val bitrate: Int?,
        val size: Long?, val created: Long,
    ) {
        fun json(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
            put("id", id)
            put("parent", albumViewId)
            put("isDir", false)
            put("title", title)
            put("album", albumName)
            put("albumId", albumViewId)
            if (singers.isNotEmpty()) {
                put("artist", singers.joinToString(", ") { it.second })
                put("artistId", singers.first().first)
                putJsonArray("artists") { singers.forEach { (pid, n) -> addJsonObject { put("id", pid); put("name", n) } } }
            }
            put("track", track)
            put("discNumber", disc)
            year?.let { put("year", it) }
            put("duration", durationMs / 1000)
            coverId?.let { put("coverArt", it) }
            suffix?.let { put("suffix", it); put("contentType", contentType(it)) }
            bitrate?.let { put("bitRate", it / 1000) }
            size?.let { put("size", it) }
            put("type", "music")
            put("mediaType", "song")
            put("created", iso(created))
            extra()
        }
    }

    private val songSelect = """
        SELECT r.id, t.title, CASE WHEN rl.kind = 'score' THEN rl.id ELSE a.id END,
               CASE WHEN rl.kind = 'score' THEN a.title || ' (Original Background Score)' ELSE a.title END,
               coalesce(rl.year, a.year), t.disc, t.number, r.duration_ms, coalesce(f.cover_id, rl.cover_id, a.cover_id),
               (SELECT group_concat(p.id || char(31) || p.name, char(30)) FROM (SELECT * FROM recording_credits WHERE recording_id = r.id
                       AND role = 'singer' ORDER BY position) c JOIN people p ON p.id = c.person_id),
               f.format, f.bitrate, f.size, r.created_at
          FROM tracks t JOIN recordings r ON r.id = t.recording_id JOIN releases rl ON rl.id = t.release_id JOIN albums a ON a.id = rl.album_id
          JOIN files f ON f.track_id = t.id AND f.missing_since IS NULL
    """.trimIndent()

    private fun row(rs: ResultSet) = SongRow(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), (rs.getObject(5) as Number?)?.toInt(), rs.getInt(6), rs.getInt(7),
        rs.getLong(8), rs.getString(9),
        rs.getString(10)?.split('\u001e')?.map { it.substringBefore('\u001f') to it.substringAfter('\u001f') }.orEmpty(),
        rs.getString(11), (rs.getObject(12) as Number?)?.toInt(), (rs.getObject(13) as Number?)?.toLong(), rs.getLong(14),
    )

    private fun Connection.songsOf(albumId: String, scoreId: String?): List<SongRow> {
        val rows = if (scoreId != null) query("$songSelect WHERE rl.id = ? ORDER BY t.disc, t.number, t.title", scoreId, map = ::row)
        else query(
            """$songSelect WHERE a.id = ? AND rl.kind != 'score'
               ORDER BY CASE rl.kind WHEN 'single' THEN 2 WHEN 'soundtrack' THEN 0 WHEN 'album' THEN 0 ELSE 1 END, t.disc, t.number, t.title""",
            albumId, map = ::row,
        )
        return rows.distinctBy { it.id } // the same recording twice on one album (two copies of one file) shows once
    }

    /** The song's main appearance: a soundtrack or album before a single, a score or a re-release. */
    private fun Connection.mainSong(recordingId: String): SongRow? =
        query("""$songSelect WHERE r.id = ? ORDER BY CASE rl.kind WHEN 'soundtrack' THEN 0 WHEN 'album' THEN 0 WHEN 'score' THEN 1 ELSE 2 END,
                 coalesce(f.bitrate, 0) DESC LIMIT 1""", recordingId, map = ::row).firstOrNull()

    suspend fun song(id: String): JsonObject? = db.tx {
        val rid = resolve(id)
        val song = mainSong(rid) ?: return@tx null
        val credits = query(
            "SELECT c.role, p.id, p.name FROM recording_credits c JOIN people p ON p.id = c.person_id WHERE c.recording_id = ? ORDER BY c.role, c.position",
            rid,
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        val details = queryOne("SELECT f.sample_rate, f.channels, l.language FROM files f JOIN libraries l ON l.id = f.library_id " +
            "WHERE f.recording_id = ? AND f.missing_since IS NULL ORDER BY coalesce(f.bitrate, 0) DESC LIMIT 1", rid) {
            Triple((it.getObject(1) as Number?)?.toInt(), (it.getObject(2) as Number?)?.toInt(), it.getString(3))
        }
        val composers = credits.filter { it.first == "composer" }
        song.json {
            if (composers.isNotEmpty()) {
                put("displayComposer", composers.joinToString(", ") { it.third })
                put("displayAlbumArtist", composers.joinToString(", ") { it.third })
                putJsonArray("albumArtists") { composers.forEach { addJsonObject { put("id", it.second); put("name", it.third) } } }
            }
            putJsonArray("contributors") {
                credits.forEach { (role, pid, name) -> addJsonObject { put("role", role); putJsonObject("artist") { put("id", pid); put("name", name) } } }
            }
            details?.first?.let { put("samplingRate", it) }
            details?.second?.let { put("channelCount", it) }
            details?.third?.let { lang -> put("genre", lang.replaceFirstChar(Char::uppercase)); putJsonArray("genres") { addJsonObject { put("name", lang.replaceFirstChar(Char::uppercase)) } } }
        }
    }

    // ---------- artists ----------

    /** People who compose (album artists here) and people who sing, with their Subsonic roles. */
    private fun Connection.people(where: String = "", vararg args: Any?): List<JsonObject> = query(
        """SELECT p.id, p.name, p.sort_name,
                  (SELECT count(DISTINCT c.album_id) FROM album_credits c WHERE c.person_id = p.id AND c.role = 'composer'),
                  EXISTS (SELECT 1 FROM recording_credits c WHERE c.person_id = p.id AND c.role = 'singer'),
                  EXISTS (SELECT 1 FROM recording_credits c WHERE c.person_id = p.id AND c.role = 'composer')
             FROM people p $where""", *args,
    ) {
        val albums = it.getInt(4)
        buildJsonObject {
            put("id", it.getString(1))
            put("name", it.getString(2))
            put("albumCount", albums)
            putJsonArray("roles") {
                if (albums > 0) add("albumartist")
                if (it.getInt(6) == 1) add("composer")
                if (it.getInt(5) == 1) add("artist")
            }
        }
    }

    suspend fun artists(): JsonObject = db.tx {
        val all = people("WHERE EXISTS (SELECT 1 FROM album_credits c WHERE c.person_id = p.id) OR EXISTS (SELECT 1 FROM recording_credits c WHERE c.person_id = p.id) ORDER BY p.sort_name")
        buildJsonObject {
            put("ignoredArticles", "The A")
            putJsonArray("index") {
                all.groupBy { (it["name"].toString().trim('"').firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter) ?: '#').toString() }
                    .toSortedMap().forEach { (letter, list) -> addJsonObject { put("name", letter); put("artist", JsonArray(list)) } }
            }
        }
    }

    suspend fun artist(id: String): JsonObject? = db.tx {
        val person = people("WHERE p.id = ?", id).firstOrNull() ?: return@tx null
        // Every view of their albums: the songs and, for films, the background score.
        val albumIds = query(
            """SELECT vid FROM ($views) WHERE album_id IN (SELECT album_id FROM album_credits WHERE person_id = ?)
               OR album_id IN (SELECT rl.album_id FROM recording_credits c JOIN tracks t ON t.recording_id = c.recording_id
                               JOIN releases rl ON rl.id = t.release_id WHERE c.person_id = ?)
               ORDER BY year DESC, sort_title""", id, id,
        ) { it.getString(1) }
        val albums = albumIds.mapNotNull { albumJson(it, withSongs = false) }
        JsonObject(person + ("album" to JsonArray(albums)) + ("albumCount" to kotlinx.serialization.json.JsonPrimitive(albums.size)))
    }

    // ---------- search ----------

    private class Entry(val id: String, val words: List<String>, val alsoWords: List<String> = emptyList())
    private class Index(val version: String, val albums: List<Entry>, val people: List<Entry>, val songs: List<Entry>)

    @Volatile private var index: Index? = null

    /** Search words for everything, kept in memory and rebuilt when a scan changed the catalog. */
    private suspend fun index(): Index {
        val version = db.tx { queryOne("SELECT coalesce(max(finished_at), 0) || '/' || (SELECT count(*) FROM recordings) FROM scans") { it.getString(1) } } ?: "0"
        index?.takeIf { it.version == version }?.let { return it }
        return db.tx {
            val albums = query("SELECT vid, name FROM ($views)") { Entry(it.getString(1), Fuzzy.words(it.getString(2))) }
            val people = query("SELECT id, name FROM people") { Entry(it.getString(1), Fuzzy.words(it.getString(2))) }
            val songs = query(
                """SELECT r.id, r.title, (SELECT group_concat(p.name, ' ') FROM recording_credits c JOIN people p ON p.id = c.person_id
                                          WHERE c.recording_id = r.id AND c.role = 'singer')
                     FROM recordings r WHERE r.merged_into IS NULL AND EXISTS (SELECT 1 FROM files f WHERE f.recording_id = r.id AND f.missing_since IS NULL)""",
            ) { Entry(it.getString(1), Fuzzy.words(it.getString(2)), Fuzzy.words(it.getString(3).orEmpty())) }
            Index(version, albums, people, songs)
        }.also { index = it }
    }

    suspend fun search(query: String, artistCount: Int, artistOffset: Int, albumCount: Int, albumOffset: Int, songCount: Int, songOffset: Int): JsonObject {
        val q = Fuzzy.words(query.trim('"'))
        val idx = index()
        fun ranked(list: List<Entry>, count: Int, offset: Int, singerToo: Boolean = false): List<String> {
            if (count <= 0) return emptyList()
            if (q.isEmpty()) return list.sortedBy { it.words.joinToString(" ") }.drop(offset).take(count).map { it.id }
            return list.asSequence().map { e ->
                e.id to maxOf(Fuzzy.match(q, e.words), if (singerToo && e.alsoWords.isNotEmpty()) Fuzzy.match(q, e.alsoWords) * 0.9 else 0.0)
            }.filter { it.second > 0 }.sortedByDescending { it.second }.drop(offset).take(count).map { it.first }.toList()
        }
        val artistIds = ranked(idx.people, artistCount, artistOffset)
        val albumIds = ranked(idx.albums, albumCount, albumOffset)
        val songIds = ranked(idx.songs, songCount, songOffset, singerToo = true)
        return db.tx {
            val artists = artistIds.mapNotNull { people("WHERE p.id = ?", it).firstOrNull() }
            val albums = albumIds.mapNotNull { albumJson(it, withSongs = false) }
            val songs = songIds.mapNotNull { mainSong(it)?.json() }
            buildJsonObject {
                put("artist", JsonArray(artists))
                put("album", JsonArray(albums))
                put("song", JsonArray(songs))
            }
        }
    }

    // ---------- lyrics, audio and covers ----------

    suspend fun lyrics(id: String): JsonArray = db.tx {
        val rid = resolve(id)
        val title = queryOne("SELECT title FROM recordings WHERE id = ?", rid) { it.getString(1) } ?: return@tx JsonArray(emptyList())
        val all = query("SELECT script, synced, text FROM lyrics WHERE recording_id = ? ORDER BY synced DESC, script = 'ta' DESC", rid) {
            Triple(it.getString(1), it.getInt(2) == 1, it.getString(3))
        }
        buildJsonArray {
            for ((script, synced, text) in all) addJsonObject {
                put("displayTitle", title)
                put("lang", script)
                put("synced", synced)
                put("offset", 0)
                putJsonArray("line") {
                    if (synced) lrcLines(text).forEach { (start, value) -> addJsonObject { put("start", start); put("value", value) } }
                    else text.lines().forEach { addJsonObject { put("value", it) } }
                }
            }
        }
    }

    /** The best copy of a song on disk: (file, content type). */
    suspend fun audio(id: String): Pair<File, String>? = db.tx {
        val rid = resolve(id)
        queryOne("""SELECT library_id, path, format FROM files WHERE recording_id = ? AND missing_since IS NULL
                    ORDER BY coalesce(bitrate, 0) DESC LIMIT 1""", rid) { Triple(it.getLong(1), it.getString(2), it.getString(3)) }
    }?.let { (lib, path, format) -> roots()[lib]?.let { File(it, path) }?.takeIf { it.isFile }?.let { it to contentType(format) } }

    /** A cover (by artwork, album, release or song id), resized to fit [size] when asked. */
    suspend fun cover(id: String, size: Int?): Pair<File, String>? {
        val art = db.tx {
            queryOne("SELECT hash, mime FROM artwork WHERE id = ?", id) { it.getString(1) to it.getString(2) }
                ?: queryOne(
                    """SELECT w.hash, w.mime FROM artwork w WHERE w.id = coalesce(
                         (SELECT cover_id FROM albums WHERE id = ?), (SELECT cover_id FROM releases WHERE id = ?),
                         (SELECT coalesce(f.cover_id, rl.cover_id, a.cover_id) FROM files f JOIN tracks t ON t.id = f.track_id
                            JOIN releases rl ON rl.id = t.release_id JOIN albums a ON a.id = rl.album_id
                           WHERE f.recording_id = ? AND f.missing_since IS NULL LIMIT 1))""",
                    id, id, resolve(id),
                ) { it.getString(1) to it.getString(2) }
        } ?: return null
        val (hash, mime) = art
        val original = File(artworkDir, hash + if (mime == "image/png") ".png" else ".jpg").takeIf { it.isFile } ?: return null
        if (size == null || size <= 0 || size >= 1500) return original to mime
        val sized = File(artworkDir, "sized/$hash-$size.jpg")
        if (!sized.isFile) {
            val bytes = tools.resize(original, size) ?: return original to mime
            sized.parentFile.mkdirs()
            sized.writeBytes(bytes)
        }
        return sized to "image/jpeg"
    }

    private fun Connection.resolve(id: String): String {
        var current = id
        repeat(10) { current = queryOne("SELECT merged_into FROM recordings WHERE id = ?", current) { it.getString(1) } ?: return current }
        return current
    }

    companion object {
        private val lrcTime = Regex("""\[(\d+):(\d{1,2}(?:[.:]\d{1,3})?)]""")

        /** Lines of an .lrc file with their start in milliseconds; tag lines like [ar:...] are skipped. */
        fun lrcLines(text: String): List<Pair<Long, String>> = text.lines().flatMap { line ->
            val times = lrcTime.findAll(line).toList()
            if (times.isEmpty()) return@flatMap emptyList()
            val value = line.substring(times.last().range.last + 1).trim()
            times.map { m ->
                val seconds = m.groupValues[2].replace(':', '.').toDouble()
                (m.groupValues[1].toLong() * 60_000 + (seconds * 1000).toLong()) to value
            }
        }.sortedBy { it.first }

        fun contentType(format: String?) = when (format?.lowercase()) {
            "m4a", "mp4", "aac", "alac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
    }
}
