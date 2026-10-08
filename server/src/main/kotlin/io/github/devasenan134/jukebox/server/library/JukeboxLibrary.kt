package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.History
import io.github.devasenan134.jukebox.server.LibrarySnapshot
import io.github.devasenan134.jukebox.server.LibrarySong
import io.github.devasenan134.jukebox.server.MusicSource
import io.github.devasenan134.jukebox.server.Person
import io.github.devasenan134.jukebox.server.moodScores
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.queryOne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager

private val log = LoggerFactory.getLogger("jukebox.music")

/**
 * The mixes, search and music requests made from Jukebox's own catalog, likes and plays.
 *
 * A song is a recording with a file on disk; its album is the album view the app shows (the songs, or the
 * background score). The audio analyzer (analyzer/) keys its sound features by recording id.
 */
class JukeboxLibrary(
    private val db: Db,
    private val featuresDb: String? = null,
) : MusicSource {
    private val mutex = Mutex()
    @Volatile private var cached: LibrarySnapshot? = null
    @Volatile private var checkedAt = 0L

    override suspend fun snapshot(): LibrarySnapshot? = mutex.withLock {
        // The catalog's version is known in memory; the sound features' (another program's file) is checked once a minute.
        val sameCatalog = cached?.version?.startsWith(catalogVersion() + "/") == true
        if (System.currentTimeMillis() - checkedAt < CHECK_EVERY_MS && sameCatalog) return cached
        checkedAt = System.currentTimeMillis()
        runCatching {
            val version = catalogVersion() + "/" + withContext(Dispatchers.IO) { featuresVersion() }
            if (cached?.version != version) {
                cached = load(version)
                log.info("Library for mixes: {} songs, {} with sound features", cached!!.songs.size, cached!!.analyzed)
            }
        }.onFailure { log.warn("Couldn't load the library for mixes", it) }
        cached
    }

    override suspend fun history(userId: Long, snapshot: LibrarySnapshot): History = db.read {
        val playCount = HashMap<Int, Int>()
        val lastPlayed = HashMap<Int, Long>()
        query("SELECT recording_id, count, last_played FROM play_counts WHERE user_id = ?", userId) { rs ->
            snapshot.index[rs.getString(1)]?.let { i -> playCount[i] = rs.getInt(2); lastPlayed[i] = rs.getLong(3) }
        }
        val plays = query(
            "SELECT json_extract(payload, '$.recording'), at FROM events WHERE user_id = ? AND type = 'played' ORDER BY seq DESC LIMIT 5000", userId,
        ) { rs -> snapshot.index[rs.getString(1)]?.let { it to rs.getLong(2) } }.filterNotNull()
        val likes = query("SELECT item_type, item_id FROM likes WHERE user_id = ?", userId) { it.getString(1) to it.getString(2) }
        History(
            playCount, lastPlayed, plays,
            starred = likes.filter { it.first == "recording" }.mapNotNull { snapshot.index[it.second] }.toSet(),
            starredAlbums = likes.filter { it.first == "album" }.map { it.second }.toSet(),
            starredArtists = likes.filter { it.first == "person" }.map { it.second }.toSet(),
            rating = emptyMap(),
        )
    }

    override suspend fun popularity(snapshot: LibrarySnapshot): Map<Int, Int> = db.read {
        query("SELECT recording_id, sum(count) FROM play_counts GROUP BY recording_id") { rs ->
            snapshot.index[rs.getString(1)]?.let { it to rs.getInt(2) }
        }.filterNotNull().toMap()
    }

    override suspend fun playlists(snapshot: LibrarySnapshot): List<List<Int>> = db.read {
        query("SELECT playlist_id, recording_id FROM playlist_entries ORDER BY playlist_id, position") { rs ->
            rs.getString(1) to snapshot.index[rs.getString(2)]
        }.filter { it.second != null }.groupBy({ it.first }, { it.second!! }).values.toList()
    }

    private fun catalogVersion(): String = db.catalogVersion.toString()

    private suspend fun load(version: String): LibrarySnapshot {
        val songs = db.read {
            val credits = HashMap<String, MutableMap<String, MutableList<Person>>>()
            query("""SELECT c.recording_id, c.role, p.id, p.name FROM recording_credits c JOIN people p ON p.id = c.person_id
                     ORDER BY c.recording_id, c.role, c.position""") { rs ->
                credits.getOrPut(rs.getString(1)) { HashMap() }.getOrPut(rs.getString(2)) { mutableListOf() } += Person(rs.getString(3), rs.getString(4))
            }
            // One row per recording: its main appearance (a soundtrack before a single), its best file.
            query(
                """SELECT r.id, r.title, r.version, r.duration_ms, r.created_at, a.title, a.id, rl.kind, rl.id, coalesce(rl.year, a.year),
                          f.path, l.language
                     FROM recordings r JOIN tracks t ON t.recording_id = r.id JOIN releases rl ON rl.id = t.release_id
                     JOIN albums a ON a.id = rl.album_id JOIN files f ON f.track_id = t.id AND f.missing_since IS NULL
                     JOIN libraries l ON l.id = f.library_id
                    WHERE r.merged_into IS NULL
                    ORDER BY r.id, CASE rl.kind WHEN 'soundtrack' THEN 0 WHEN 'album' THEN 0 WHEN 'score' THEN 1 ELSE 2 END, coalesce(f.bitrate, 0) DESC""",
            ) { rs ->
                val id = rs.getString(1)
                val relKind = rs.getString(8)
                val albumRaw = rs.getString(6)
                val title = rs.getString(2)
                val isScoreRelease = relKind == "score"
                val score = Names.isScore(relKind, albumRaw, title)
                val roles = credits[id].orEmpty()
                val singers = roles["singer"].orEmpty()
                LibrarySong(
                    id = id,
                    title = title,
                    album = if (isScoreRelease) albumRaw + " (Original Background Score)" else albumRaw,
                    albumId = if (isScoreRelease) rs.getString(9) else rs.getString(7),
                    artist = singers.joinToString(" • ") { it.name },
                    singers = singers,
                    composer = roles["composer"]?.firstOrNull(),
                    year = rs.getInt(10),
                    duration = (rs.getLong(4) / 1000).toInt(),
                    genre = rs.getString(12)?.replaceFirstChar(Char::uppercase).orEmpty(),
                    addedAt = rs.getLong(5),
                    karaoke = rs.getString(3) == "karaoke",
                    lyricists = roles["lyricist"].orEmpty(),
                    score = score,
                )
            }.distinctBy { it.id }
        }
        val sound = arrayOfNulls<FloatArray>(songs.size)
        val tempo = FloatArray(songs.size) { Float.NaN }
        val energy = FloatArray(songs.size) { Float.NaN }
        val rhythm = FloatArray(songs.size) { Float.NaN }
        val prompts = HashMap<String, FloatArray>()
        withContext(Dispatchers.IO) {
            val features = featuresDb?.takeIf { File(it).isFile }
            // The analyzer this server came from keyed songs by Navidrome's ids; those can't be matched any more.
            val byRecording = features != null && readOnly(features) { c ->
                runCatching { c.queryOne("SELECT value FROM meta WHERE key = 'ids'") { it.getString(1) } }.getOrNull() == "recording"
            }
            if (features != null && !byRecording) log.warn("{} isn't keyed by recording id; run Jukebox's analyzer (analyzer/)", features)
            val position = if (byRecording) snapshotIndex(songs) else emptyMap()
            features?.takeIf { position.isNotEmpty() }?.let { path ->
                readOnly(path) { c ->
                    c.query("SELECT id, tempo, energy, embedding, rhythm FROM songs WHERE embedding IS NOT NULL") { rs ->
                        val i = position[rs.getString(1)] ?: return@query
                        if (rs.getFloat(3) > -100f && rs.getFloat(2) > 0f) {
                            tempo[i] = rs.getFloat(2)
                            energy[i] = rs.getFloat(3)
                            rhythm[i] = rs.getFloat(5)
                        }
                        sound[i] = floats(rs.getBytes(4))
                    }
                    c.query("SELECT key, embedding FROM prompts") { rs -> prompts[rs.getString(1)] = floats(rs.getBytes(2)) }
                }
            }
        }
        return LibrarySnapshot(songs, sound, moodScores(sound, prompts), prompts, tempo, energy, version, rhythm)
    }

    private fun snapshotIndex(songs: List<LibrarySong>): Map<String, Int> = songs.withIndex().associate { (i, s) -> s.id to i }

    private fun featuresVersion(): String = featuresDb?.takeIf { File(it).isFile }?.let { path ->
        readOnly(path) { c -> c.queryOne("SELECT count(*), max(analyzed_at) FROM songs") { "${it.getInt(1)}-${it.getLong(2)}" } }
    } ?: "none"

    private fun <T> readOnly(path: String, block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro").use { c ->
            c.createStatement().use { it.execute("PRAGMA busy_timeout = 10000") }
            block(c)
        }

    private companion object {
        const val CHECK_EVERY_MS = 60_000L

        fun floats(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
    }
}
