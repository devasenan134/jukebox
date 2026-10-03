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

    /**
     * What an album list shows for one album view, kept in memory: the catalog only changes when a scan or a
     * merge finishes, and working it out per request (EXISTS over every album) made lists take seconds.
     */
    private class View(
        val id: String, val name: String, val albumId: String, val scoreId: String?, val year: Int?, val coverId: String?,
        val created: Long, val sort: String, val songCount: Int, val durationS: Long, val composers: List<Pair<String, String>>,
    )

    private class Views(val version: String, val list: List<View>, val byId: Map<String, View>)

    @Volatile private var viewsCache: Views? = null

    /** Changes whenever a scan finishes, recordings merge, or people merge. */
    private suspend fun catalogVersion(): String = db.tx {
        queryOne("""SELECT coalesce(max(finished_at), 0) || '/' || (SELECT count(*) FROM recordings WHERE merged_into IS NULL)
                    || '/' || (SELECT count(*) FROM people WHERE merged_into IS NULL) FROM scans""") { it.getString(1) }
    } ?: "0"

    private suspend fun views(): Views {
        val version = catalogVersion()
        viewsCache?.takeIf { it.version == version }?.let { return it }
        return db.tx {
            val composers = HashMap<String, MutableList<Pair<String, String>>>()
            query("""SELECT c.album_id, p.id, p.name FROM album_credits c JOIN people p ON p.id = c.person_id
                     WHERE c.role = 'composer' ORDER BY c.album_id, c.position""") {
                composers.getOrPut(it.getString(1)) { mutableListOf() } += it.getString(2) to it.getString(3)
            }
            // Songs and length of each view: distinct recordings with a file on disk.
            fun counts(groupBy: String, scores: Boolean) = query(
                """SELECT g, count(*), sum(d) FROM (SELECT DISTINCT $groupBy AS g, r.id, r.duration_ms AS d
                     FROM releases rl JOIN tracks t ON t.release_id = rl.id JOIN files f ON f.track_id = t.id AND f.missing_since IS NULL
                     JOIN recordings r ON r.id = t.recording_id WHERE rl.kind ${if (scores) "=" else "!="} 'score') GROUP BY g""",
            ) { it.getString(1) to (it.getInt(2) to it.getLong(3) / 1000) }.toMap()
            val songs = counts("rl.album_id", scores = false)
            val scores = counts("rl.id", scores = true)
            val list = query("SELECT id, title, year, cover_id, created_at, sort_title FROM albums") { rs ->
                val id = rs.getString(1)
                songs[id]?.let { (n, d) ->
                    View(id, rs.getString(2), id, null, (rs.getObject(3) as Number?)?.toInt(), rs.getString(4), rs.getLong(5), rs.getString(6), n, d, composers[id].orEmpty())
                }
            }.filterNotNull() + query(
                """SELECT rl.id, a.title, a.id, a.year, coalesce(rl.cover_id, a.cover_id), a.created_at, a.sort_title
                     FROM releases rl JOIN albums a ON a.id = rl.album_id WHERE rl.kind = 'score'""",
            ) { rs ->
                val id = rs.getString(1)
                scores[id]?.let { (n, d) ->
                    View(id, rs.getString(2) + " (Original Background Score)", rs.getString(3), id, (rs.getObject(4) as Number?)?.toInt(),
                        rs.getString(5), rs.getLong(6), rs.getString(7) + " ~", n, d, composers[rs.getString(3)].orEmpty())
                }
            }.filterNotNull()
            Views(version, list, list.associateBy { it.id })
        }.also { viewsCache = it }
    }

    suspend fun albumList(type: String, size: Int, offset: Int, fromYear: Int?, toYear: Int?): List<JsonObject> {
        val all = views().list
        val sorted = when (type) {
            "newest" -> all.sortedWith(compareByDescending<View> { it.created }.thenBy { it.sort })
            "random" -> all.shuffled()
            "byYear" -> {
                val (from, to) = (fromYear ?: 0) to (toYear ?: 9999)
                val inRange = all.filter { (it.year ?: -1) in minOf(from, to)..maxOf(from, to) }
                if (from > to) inRange.sortedWith(compareByDescending<View> { it.year }.thenBy { it.sort })
                else inRange.sortedWith(compareBy<View> { it.year }.thenBy { it.sort })
            }
            // Plays and likes come with milestone 2: nothing is recent, frequent or starred yet.
            "recent", "frequent", "highest", "starred" -> emptyList()
            else -> all.sortedWith(compareBy<View> { it.sort }.thenBy { it.name })
        }
        return sorted.drop(offset.coerceAtLeast(0)).take(size.coerceIn(1, 500)).map { albumJson(it, null) }
    }

    suspend fun album(id: String): JsonObject? {
        val view = views().byId[id] ?: return null
        val songs = db.tx { songsOf(view.albumId, view.scoreId) }
        return albumJson(view, songs)
    }

    /** Album list entries for album views by id, in that order. */
    private suspend fun albumsById(ids: List<String>): List<JsonObject> {
        val byId = views().byId
        return ids.mapNotNull { byId[it] }.map { albumJson(it, null) }
    }

    private fun albumJson(v: View, songs: List<SongRow>?): JsonObject = buildJsonObject {
        put("id", v.id)
        put("name", v.name)
        put("title", v.name)
        if (v.composers.isNotEmpty()) {
            put("artist", v.composers.joinToString(", ") { it.second })
            put("artistId", v.composers.first().first)
        }
        v.coverId?.let { put("coverArt", it) }
        put("songCount", songs?.size ?: v.songCount)
        put("duration", songs?.sumOf { it.durationMs / 1000 } ?: v.durationS)
        v.year?.let { put("year", it) }
        put("created", iso(v.created))
        if (songs != null) putJsonArray("song") { songs.forEach { add(it.json()) } }
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
             FROM people p ${if (where.isBlank()) "WHERE" else "$where AND"} p.merged_into IS NULL""", *args,
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
        val all = people("WHERE (EXISTS (SELECT 1 FROM album_credits c WHERE c.person_id = p.id) OR EXISTS (SELECT 1 FROM recording_credits c WHERE c.person_id = p.id))")
            .sortedBy { it["name"].toString().trim('"').lowercase() }
        buildJsonObject {
            put("ignoredArticles", "The A")
            putJsonArray("index") {
                all.groupBy { (it["name"].toString().trim('"').firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter) ?: '#').toString() }
                    .toSortedMap().forEach { (letter, list) -> addJsonObject { put("name", letter); put("artist", JsonArray(list)) } }
            }
        }
    }

    suspend fun artist(requested: String): JsonObject? = db.tx {
        // An old id of a spelling that was merged leads to the person.
        val id = queryOne("SELECT coalesce(merged_into, id) FROM people WHERE id = ?", requested) { it.getString(1) } ?: return@tx null
        val person = people("WHERE p.id = ?", id).firstOrNull() ?: return@tx null
        // Their albums (credited on the album, or singing on it).
        val albumIds = query(
            """SELECT album_id FROM album_credits WHERE person_id = ?
               UNION SELECT rl.album_id FROM recording_credits c JOIN tracks t ON t.recording_id = c.recording_id
                     JOIN releases rl ON rl.id = t.release_id WHERE c.person_id = ?""", id, id,
        ) { it.getString(1) }.toSet()
        person to albumIds
    }?.let { (person, albumIds) ->
        // Every view of those albums: the songs and, for films, the background score; newest first.
        val albums = views().list.filter { it.albumId in albumIds }
            .sortedWith(compareByDescending<View> { it.year }.thenBy { it.sort }).map { albumJson(it, null) }
        JsonObject(person + ("album" to JsonArray(albums)) + ("albumCount" to kotlinx.serialization.json.JsonPrimitive(albums.size)))
    }

    // ---------- search ----------

    private class Entry(val id: String, val words: List<String>, val alsoWords: List<String> = emptyList())
    private class Index(val version: String, val albums: List<Entry>, val people: List<Entry>, val songs: List<Entry>)

    @Volatile private var index: Index? = null

    /** Search words for everything, kept in memory and rebuilt when a scan changed the catalog. */
    private suspend fun index(): Index {
        val version = catalogVersion()
        index?.takeIf { it.version == version }?.let { return it }
        val viewsNow = views().list
        return db.tx {
            val albums = viewsNow.map { Entry(it.id, Fuzzy.words(it.name)) }
            // People are found by their other spellings too.
            val people = query("SELECT id, name, coalesce(aliases, '') FROM people WHERE merged_into IS NULL") {
                Entry(it.getString(1), Fuzzy.words(it.getString(2)), Fuzzy.words(it.getString(3).replace('\n', ' ')))
            }
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
        val artistIds = ranked(idx.people, artistCount, artistOffset, singerToo = true)
        val albumIds = ranked(idx.albums, albumCount, albumOffset)
        val songIds = ranked(idx.songs, songCount, songOffset, singerToo = true)
        val albums = albumsById(albumIds)
        return db.tx {
            val artists = artistIds.mapNotNull { people("WHERE p.id = ?", it).firstOrNull() }
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
            val bytes = resize(original, size) ?: tools.resize(original, size) ?: return original to mime
            sized.parentFile.mkdirs()
            val tmp = File(sized.parentFile, "${sized.name}.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(sized)
        }
        return sized to "image/jpeg"
    }

    /** [image] scaled to fit [size] × [size], as JPEG, in this process (much quicker than starting ffmpeg). */
    private fun resize(image: File, size: Int): ByteArray? = runCatching {
        val src = javax.imageio.ImageIO.read(image) ?: return null
        val scale = minOf(1.0, size.toDouble() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())
        val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(src.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
            dispose()
        }
        val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply { compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.85f }
        java.io.ByteArrayOutputStream().also { bytes ->
            javax.imageio.ImageIO.createImageOutputStream(bytes).use { stream ->
                writer.output = stream
                writer.write(null, javax.imageio.IIOImage(out, null, null), params)
            }
            writer.dispose()
        }.toByteArray()
    }.getOrNull()

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
