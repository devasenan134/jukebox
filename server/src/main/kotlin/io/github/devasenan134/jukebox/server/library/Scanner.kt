package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.Fuzzy
import io.github.devasenan134.jukebox.server.insert
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.queryOne
import io.github.devasenan134.jukebox.server.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.SecureRandom
import java.sql.Connection

/** A music folder to scan. [kind] "film": its albums are films (soundtracks, scores, singles); "album": plain albums. */
data class LibraryDef(val name: String, val path: String, val kind: String = "album", val language: String? = null)

@Serializable
data class ScanReport(
    val library: String,
    val files: Int,
    val unchanged: Int,
    val added: Int,
    val updated: Int,
    val moved: Int,
    val missing: Int,
    val unreadable: Int,
    val lyricsUpdated: Int,
    val ms: Long,
)

private val log = LoggerFactory.getLogger("jukebox.scanner")
private val audioExtensions = setOf("m4a", "mp3", "flac", "ogg", "opus", "aac", "wav", "wma", "alac", "mp4")
private val coverNames = listOf("cover", "folder", "front", "album")
private val random = SecureRandom()
private const val ID_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

/** A new id: 16 random letters and digits. Given once, never derived from a path. */
fun newId(): String = buildString { repeat(16) { append(ID_CHARS[random.nextInt(ID_CHARS.length)]) } }

/**
 * Builds the catalog from the music folders (docs/milestone-1.md). Music files are only read, never changed.
 *
 * [externalIds] gives a JioSaavn id for a file (library name, path inside the library) when the tags have
 * none; our library imports them once from the collectors' songs.csv.
 */
class Scanner(
    private val db: Db,
    private val tools: AudioTools,
    private val artworkDir: File,
    private val externalIds: (library: String, path: String) -> String? = { _, _ -> null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val threads: Int = 4,
) {
    private class Known(
        val id: Long, val path: String, val size: Long, val mtime: Long, val recordingId: String, val trackId: String?,
        val durationMs: Long, val md5: String?, val lyricsMtime: Long?, val missingSince: Long?,
    )

    private class Found(val path: String, val file: File, val size: Long, val mtime: Long)

    suspend fun scan(library: LibraryDef): ScanReport = withContext(Dispatchers.IO) {
        val started = clock()
        val root = File(library.path)
        require(root.isDirectory) { "Not a folder: ${library.path}" }
        val libId = db.tx { libraryId(library) }
        val scanId = db.tx { insert("INSERT INTO scans (started_at, info) VALUES (?, ?)", started, library.name) }

        val found = root.walkTopDown().onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.lowercase() in audioExtensions && !it.name.startsWith(".") }
            .map { Found(it.relativeTo(root).path, it, it.length(), it.lastModified()) }
            .toList()
        val known = db.tx {
            query("""SELECT id, path, size, mtime, recording_id, track_id, duration_ms, audio_md5, lyrics_mtime, missing_since
                     FROM files WHERE library_id = ?""", libId) {
                Known(it.getLong(1), it.getString(2), it.getLong(3), it.getLong(4), it.getString(5), it.getString(6),
                    it.getLong(7), it.getString(8), it.getObject(9) as Long?, it.getObject(10) as Long?)
            }
        }.associateBy { it.path }

        var unchanged = 0
        var lyricsUpdated = 0
        val toRead = mutableListOf<Found>()
        for (f in found) {
            val k = known[f.path]
            if (k != null && k.size == f.size && k.mtime == f.mtime) {
                unchanged++
                val lyricsMtime = lyricsFiles(f.file).maxOfOrNull { it.lastModified() }
                if (lyricsMtime != k.lyricsMtime || k.missingSince != null) db.tx {
                    update("UPDATE files SET missing_since = NULL, lyrics_mtime = ? WHERE id = ?", lyricsMtime, k.id)
                    if (lyricsMtime != k.lyricsMtime) { readLyrics(k.recordingId, f.file); lyricsUpdated++ }
                }
            } else toRead += f
        }

        // Read the new and changed files a few at a time (ffprobe), then write them one by one.
        val gate = Semaphore(threads)
        val probed = toRead.map { f -> async { gate.withPermit { f to tools.probe(f.file) } } }.awaitAll()
        val present = found.mapTo(HashSet()) { it.path }
        val gone = known.values.filter { it.path !in present }.toMutableList()
        val folderCovers = HashMap<String, String?>()
        var added = 0
        var updated = 0
        var moved = 0
        var unreadable = 0
        for ((f, p) in probed) {
            if (p == null) { unreadable++; log.warn("Couldn't read {}", f.path); continue }
            val existing = known[f.path]
            val movedFrom = if (existing == null) movedFile(f, p, gone)?.also { gone.remove(it); moved++ } else null
            runCatching {
                db.tx { save(library, libId, f, p, existing ?: movedFrom, folderCovers) }
            }.onFailure { log.warn("Couldn't add {}", f.path, it); unreadable++ }
                .onSuccess { if (existing != null) updated++ else if (movedFrom == null) added++ }
        }
        val now = clock()
        db.tx { for (g in gone) if (g.missingSince == null) update("UPDATE files SET missing_since = ? WHERE id = ?", now, g.id) }

        val report = ScanReport(library.name, found.size, unchanged, added, updated, moved, gone.count { it.missingSince == null },
            unreadable, lyricsUpdated, clock() - started)
        db.tx { update("UPDATE scans SET finished_at = ?, info = ? WHERE id = ?", clock(), Json.encodeToString(ScanReport.serializer(), report), scanId) }
        if (report.added + report.updated + report.moved + report.missing > 0) db.catalogChanged()
        log.info("Scanned {}: {}", library.name, report)
        report
    }

    /** A file that left one path and turned up at another: same length, and the same size or the same audio. */
    private fun movedFile(f: Found, p: Probed, gone: List<Known>): Known? {
        val candidates = gone.filter { kotlin.math.abs(it.durationMs - p.durationMs) <= 1000 }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.size == f.size }?.let { return it }
        val md5 = tools.audioMd5(f.file) ?: return null
        return candidates.firstOrNull { it.md5 == md5 }
    }

    private fun Connection.libraryId(l: LibraryDef): Long {
        queryOne("SELECT id FROM libraries WHERE name = ?", l.name) { it.getLong(1) }?.let { id ->
            update("UPDATE libraries SET path = ?, kind = ?, language = ? WHERE id = ?", l.path, l.kind, l.language, id)
            return id
        }
        return insert("INSERT INTO libraries (name, path, kind, language) VALUES (?, ?, ?, ?)", l.name, l.path, l.kind, l.language)
    }

    /** Writes one file into the catalog: its recording, album, release, track, people, lyrics and cover. */
    private fun Connection.save(library: LibraryDef, libId: Long, f: Found, p: Probed, existing: Known?, folderCovers: HashMap<String, String?>) {
        val now = clock()
        val titleTag = p.tag("title") ?: f.file.nameWithoutExtension.replace(Regex("""^\d+(-\d+)?\s*-\s*"""), "")
        val (baseTitle, version) = Names.version(titleTag)
        val isrc = p.tag("isrc", "tsrc")
        val mbid = p.tag("musicbrainz_trackid", "musicbrainz track id", "musicbrainz_recordingid")
        val saavnId = p.tag("saavn_id", "jiosaavn_id", "jiosaavn") ?: externalIds(library.name, f.path)

        // The recording: the one this file already belongs to, or one with the same external id, or a new one.
        val recordingId = existing?.recordingId?.let { resolve(it) }
            ?: isrc?.let { byId("isrc", it) } ?: mbid?.let { byId("mbid", it) } ?: saavnId?.let { byId("saavn_id", it) }
            ?: newId().also {
                insert("""INSERT INTO recordings (id, title, title_key, version, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?)""",
                    it, baseTitle, Fuzzy.key(baseTitle), version, p.durationMs, now)
            }

        // The album and the release this track belongs to.
        val (albumTitle, isScore) = Names.album(p.tag("album") ?: f.file.parentFile?.name ?: "Unknown album")
        val year = Names.year(p.tag("date", "year", "originaldate"))
        val albumKey = "$libId|${Fuzzy.key(albumTitle)}|${year ?: ""}"
        val albumId = queryOne("SELECT id FROM albums WHERE group_key = ?", albumKey) { it.getString(1) }?.also {
            update("UPDATE albums SET title = ?, sort_title = ?, updated_at = ? WHERE id = ?", albumTitle, Names.sortTitle(albumTitle), now, it)
        } ?: newId().also {
            insert("""INSERT INTO albums (id, library_id, title, sort_title, year, kind, group_key, created_at, updated_at)
                      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", it, libId, albumTitle, Names.sortTitle(albumTitle), year,
                if (library.kind == "film") "film" else "album", albumKey, now, now)
        }
        val subtitle = p.tag("discsubtitle", "setsubtitle", "tsst")
        val (releaseKind, releaseTitle) = when {
            isScore -> "score" to "Background Score"
            subtitle.equals("singles", ignoreCase = true) -> "single" to "Singles"
            library.kind == "film" -> "soundtrack" to (subtitle ?: "Soundtrack")
            else -> "album" to (subtitle ?: albumTitle)
        }
        val releaseKey = "$albumKey|$releaseKind|${Fuzzy.key(releaseTitle)}"
        val releaseId = queryOne("SELECT id FROM releases WHERE group_key = ?", releaseKey) { it.getString(1) }
            ?: newId().also {
                insert("INSERT INTO releases (id, album_id, title, kind, year, group_key) VALUES (?, ?, ?, ?, ?, ?)",
                    it, albumId, releaseTitle, releaseKind, year, releaseKey)
            }

        // The song (versions of the same title on the same album share one).
        val songKey = "$albumKey|${Fuzzy.key(baseTitle)}"
        val songId = queryOne("SELECT id FROM songs WHERE song_key = ?", songKey) { it.getString(1) }
            ?: newId().also { insert("INSERT INTO songs (id, title, song_key) VALUES (?, ?, ?)", it, baseTitle, songKey) }
        update("""UPDATE recordings SET title = ?, title_key = ?, version = ?, duration_ms = ?, song_id = ?,
                  isrc = coalesce(?, isrc), mbid = coalesce(?, mbid), saavn_id = coalesce(?, saavn_id) WHERE id = ?""",
            baseTitle, Fuzzy.key(baseTitle), version, p.durationMs, songId, isrc, mbid, saavnId, recordingId)

        // The track: where this file's recording appears.
        val disc = Names.number(p.tag("disc", "discnumber")) ?: 1
        val number = Names.number(p.tag("track", "tracknumber")) ?: 0
        val trackId = existing?.trackId?.also {
            update("UPDATE tracks SET release_id = ?, recording_id = ?, disc = ?, number = ?, title = ?, disc_subtitle = ? WHERE id = ?",
                releaseId, recordingId, disc, number, titleTag, subtitle, it)
        } ?: newId().also {
            insert("INSERT INTO tracks (id, release_id, recording_id, disc, number, title, disc_subtitle) VALUES (?, ?, ?, ?, ?, ?, ?)",
                it, releaseId, recordingId, disc, number, titleTag, subtitle)
        }

        // People: singers from the artist tag, the composer from the composer tag (or the album artist).
        update("DELETE FROM recording_credits WHERE recording_id = ?", recordingId)
        credit("recording_credits", "recording_id", recordingId, "singer", Names.people(p.tag("artist")))
        credit("recording_credits", "recording_id", recordingId, "composer", Names.people(p.tag("composer") ?: p.tag("album_artist")))
        credit("recording_credits", "recording_id", recordingId, "lyricist", Names.people(p.tag("lyricist", "writer")))
        credit("album_credits", "album_id", albumId, "composer", Names.people(p.tag("album_artist", "composer")), replace = false)

        // Cover: the folder's image, else the one in the file.
        val folder = f.file.parentFile?.path.orEmpty()
        val coverId = folderCovers.getOrPut(folder) { folderCover(f.file.parentFile) } ?: if (p.hasPicture) embeddedCover(f.file) else null
        if (coverId != null) {
            update("UPDATE releases SET cover_id = coalesce(cover_id, ?) WHERE id = ?", coverId, releaseId)
            // The album shows the soundtrack's cover rather than the score's or a single's.
            if (releaseKind == "soundtrack" || releaseKind == "album") update("UPDATE albums SET cover_id = ? WHERE id = ? AND (cover_id IS NULL OR cover_id != ?)", coverId, albumId, coverId)
            else update("UPDATE albums SET cover_id = coalesce(cover_id, ?) WHERE id = ?", coverId, albumId)
        }

        val lyricsMtime = lyricsFiles(f.file).maxOfOrNull { it.lastModified() }
        readLyrics(recordingId, f.file)

        if (existing != null) {
            update("""UPDATE files SET path = ?, size = ?, mtime = ?, recording_id = ?, track_id = ?, format = ?, codec = ?, bitrate = ?,
                      sample_rate = ?, channels = ?, duration_ms = ?, audio_md5 = CASE WHEN size = ? THEN audio_md5 END, cover_id = ?,
                      lyrics_mtime = ?, missing_since = NULL, scanned_at = ? WHERE id = ?""",
                f.path, f.size, f.mtime, recordingId, trackId, p.format, p.codec, p.bitrate, p.sampleRate, p.channels, p.durationMs,
                f.size, coverId, lyricsMtime, now, existing.id)
        } else {
            insert("""INSERT INTO files (library_id, path, size, mtime, recording_id, track_id, format, codec, bitrate, sample_rate, channels,
                      duration_ms, cover_id, lyrics_mtime, scanned_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                libId, f.path, f.size, f.mtime, recordingId, trackId, p.format, p.codec, p.bitrate, p.sampleRate, p.channels,
                p.durationMs, coverId, lyricsMtime, now)
        }
    }

    /** The recording [id] stands for, following merges. */
    private fun Connection.resolve(id: String): String {
        var current = id
        repeat(10) { current = queryOne("SELECT merged_into FROM recordings WHERE id = ?", current) { it.getString(1) } ?: return current }
        return current
    }

    private fun Connection.byId(column: String, value: String): String? =
        queryOne("SELECT id FROM recordings WHERE $column = ? AND merged_into IS NULL LIMIT 1", value) { it.getString(1) }

    private fun Connection.credit(table: String, key: String, id: String, role: String, names: List<String>, replace: Boolean = true) {
        if (!replace && names.isEmpty()) return
        names.forEachIndexed { i, name ->
            update("INSERT OR IGNORE INTO $table ($key, person_id, role, position) VALUES (?, ?, ?, ?)", id, personId(name), role, i)
        }
    }

    private fun Connection.personId(name: String): String {
        val key = Names.personKey(name).ifEmpty { name.lowercase() }
        // A spelling merged into another person's main spelling (PeopleMerger) credits that person.
        return queryOne("SELECT coalesce(merged_into, id) FROM people WHERE sound_key = ?", key) { it.getString(1) }
            ?: newId().also { insert("INSERT INTO people (id, name, sort_name, sound_key) VALUES (?, ?, ?, ?)", it, name, Names.sortTitle(name), key) }
    }

    private fun lyricsFiles(audio: File): List<File> =
        listOf("lrc", "txt").map { File(audio.parentFile, "${audio.nameWithoutExtension}.$it") }.filter { it.isFile }

    /** `<song>.lrc` (synced) and `<song>.txt` next to the audio file. */
    private fun Connection.readLyrics(recordingId: String, audio: File) {
        update("DELETE FROM lyrics WHERE recording_id = ? AND source = 'sidecar'", recordingId)
        for (file in lyricsFiles(audio)) {
            val text = runCatching { file.readText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            update("INSERT OR REPLACE INTO lyrics (recording_id, source, script, synced, text) VALUES (?, 'sidecar', ?, ?, ?)",
                recordingId, Names.script(text), if (file.extension == "lrc") 1 else 0, text)
        }
    }

    private fun Connection.folderCover(folder: File?): String? {
        val image = folder?.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { f -> coverNames.indexOf(f.nameWithoutExtension.lowercase()).let { if (it < 0) 99 else it } }
            ?.firstOrNull { it.nameWithoutExtension.lowercase() in coverNames } ?: return null
        return storeArtwork(image.readBytes(), "folder")
    }

    private fun Connection.embeddedCover(file: File): String? = tools.embeddedPicture(file)?.let { storeArtwork(it, "embedded") }

    private fun Connection.storeArtwork(bytes: ByteArray, source: String): String = storeArtwork(artworkDir, bytes, source)
}
