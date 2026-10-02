package io.github.devasenan134.jukebox.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val log = LoggerFactory.getLogger("library")

/** One song, with what the mixes need to know about it. */
data class LibrarySong(
    val id: String,
    val title: String,
    val album: String,
    val albumId: String,
    /** The singers, as shown ("S. Janaki • Malaysia Vasudevan"). */
    val artist: String,
    val singers: List<Person>,
    val composer: Person?,
    val year: Int,
    val duration: Int,
    /** The language here ("Tamil", "Telugu"...), or "" if unknown. */
    val genre: String,
    val addedAt: Long,
    val karaoke: Boolean,
    /** Who wrote the words. */
    val lyricists: List<Person> = emptyList(),
) {
    val coverArt get() = "al-$albumId"
}

data class Person(val id: String, val name: String)

/**
 * Everything the mixes are made from, as of one moment: the songs, and (if the analyzer has run)
 * what each one sounds like. Song positions in [songs] are used as indexes everywhere.
 */
class LibrarySnapshot(
    val songs: List<LibrarySong>,
    /** Per song: 512 numbers of length 1 from the CLAP model, or null if not analyzed (yet). */
    val sound: Array<FloatArray?>,
    /** Per description ("sad", "party"...): how well it fits each song compared with the others (z-score; NaN if not analyzed). */
    val moods: Map<String, FloatArray>,
    /** The descriptions' own CLAP numbers, to see how well a whole taste fits one. */
    val moodVectors: Map<String, FloatArray>,
    val tempo: FloatArray,
    val energy: FloatArray,
    /** Changes whenever the songs or the analysis change. */
    val version: String,
    /** How punchy the beats are (onset strength); NaN if unknown. */
    val rhythm: FloatArray = FloatArray(songs.size) { Float.NaN },
) {
    val index: Map<String, Int> = songs.withIndex().associate { (i, s) -> s.id to i }

    /** Each song's language ("Tamil", "English"...), worked out from its tags, people and sound; null if unknown. */
    val language: Array<String?> by lazy { Languages.infer(songs, sound, moods["indianfilm"], moods["western"]) }
    val analyzed = sound.count { it != null }
    val hasSound get() = analyzed >= songs.size / 4 && analyzed >= 20

    val byComposer: Map<String, List<Int>> = songs.indices.filter { songs[it].composer != null }.groupBy { songs[it].composer!!.id }
    val bySinger: Map<String, List<Int>> = buildMap<String, MutableList<Int>> {
        songs.forEachIndexed { i, s -> s.singers.forEach { getOrPut(it.id) { mutableListOf() } += i } }
    }
    val byAlbum: Map<String, List<Int>> = songs.indices.groupBy { songs[it].albumId }
    val people: Map<String, Person> = buildMap {
        songs.forEach { s -> s.composer?.let { put(it.id, it) }; s.singers.forEach { put(it.id, it) } }
    }
}

/** Plays, likes and ratings of one person in Navidrome, by song position in the snapshot. */
class History(
    val playCount: Map<Int, Int>,
    val lastPlayed: Map<Int, Long>,
    /** Each play with its time, most recent first (Navidrome only keeps these since 0.58). */
    val plays: List<Pair<Int, Long>>,
    val starred: Set<Int>,
    val starredAlbums: Set<String>,
    val starredArtists: Set<String>,
    val rating: Map<Int, Int>,
) {
    /** Changes whenever this person plays or likes something. */
    val version = "${playCount.values.sum()}-${plays.firstOrNull()?.second}-${starred.size}-${starredAlbums.size}-${starredArtists.size}-${rating.size}"

    companion object {
        val EMPTY = History(emptyMap(), emptyMap(), emptyList(), emptySet(), emptySet(), emptySet(), emptyMap())
    }
}

/** Where the mixes get their music from. Tests use a pretend library. */
interface MusicSource {
    suspend fun snapshot(): LibrarySnapshot?
    suspend fun history(navidromeUserId: String, snapshot: LibrarySnapshot): History
    /** Total plays per song across everyone, for "popular" picks. */
    suspend fun popularity(snapshot: LibrarySnapshot): Map<Int, Int>
    suspend fun navidromeUserId(username: String): String?
    /** Everyone's playlists, as songs by position in [snapshot] (for which songs people put together). */
    suspend fun playlists(snapshot: LibrarySnapshot): List<List<Int>> = emptyList()
}

/**
 * Reads Navidrome's own database (read-only) and the analyzer's features.db. Navidrome's API can't
 * tell an admin what other people played, but its database can. Reloads only when something changed.
 */
class NavidromeLibrary(private val navidromeDb: String, private val featuresDb: String?) : MusicSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    @Volatile private var cached: LibrarySnapshot? = null
    @Volatile private var checkedAt = 0L

    override suspend fun snapshot(): LibrarySnapshot? = mutex.withLock {
        if (now() - checkedAt < CHECK_EVERY_MS && cached != null) return cached
        checkedAt = now()
        withContext(Dispatchers.IO) {
            runCatching {
                val version = libraryVersion() + "/" + featuresVersion()
                if (cached?.version != version) {
                    cached = load(version)
                    log.info("Library loaded: ${cached!!.songs.size} songs, ${cached!!.analyzed} analyzed")
                }
            }.onFailure { log.warn("Couldn't read the library", it) }
        }
        cached
    }

    override suspend fun history(navidromeUserId: String, snapshot: LibrarySnapshot): History = withContext(Dispatchers.IO) {
        navidrome { c ->
            val playCount = mutableMapOf<Int, Int>()
            val lastPlayed = mutableMapOf<Int, Long>()
            val starred = mutableSetOf<Int>()
            val rating = mutableMapOf<Int, Int>()
            val starredAlbums = mutableSetOf<String>()
            val starredArtists = mutableSetOf<String>()
            c.query(
                "SELECT item_id, item_type, play_count, play_date, starred, rating FROM annotation WHERE user_id = ?", navidromeUserId,
            ) { rs ->
                val id = rs.getString(1)
                when (rs.getString(2)) {
                    "media_file" -> snapshot.index[id]?.let { i ->
                        if (rs.getInt(3) > 0) playCount[i] = rs.getInt(3)
                        parseTime(rs.getString(4))?.let { lastPlayed[i] = it }
                        if (rs.getBoolean(5)) starred += i
                        if (rs.getInt(6) > 0) rating[i] = rs.getInt(6)
                    }
                    "album" -> if (rs.getBoolean(5)) starredAlbums += id
                    "artist" -> if (rs.getBoolean(5)) starredArtists += id
                }
            }
            // Navidrome keeps every play with its time since version 0.58; older versions have only counts.
            val plays = runCatching {
                c.query(
                    "SELECT media_file_id, submission_time FROM scrobbles WHERE user_id = ? ORDER BY submission_time DESC LIMIT 5000",
                    navidromeUserId,
                ) { rs -> snapshot.index[rs.getString(1)]?.let { it to rs.getLong(2) * 1000 } }.filterNotNull()
            }.getOrDefault(emptyList())
            History(playCount, lastPlayed, plays, starred, starredAlbums, starredArtists, rating)
        } ?: History.EMPTY
    }

    override suspend fun popularity(snapshot: LibrarySnapshot): Map<Int, Int> = withContext(Dispatchers.IO) {
        navidrome { c ->
            c.query("SELECT item_id, sum(play_count) FROM annotation WHERE item_type = 'media_file' GROUP BY item_id") { rs ->
                snapshot.index[rs.getString(1)]?.let { it to rs.getInt(2) }
            }.filterNotNull().filter { it.second > 0 }.toMap()
        } ?: emptyMap()
    }

    override suspend fun playlists(snapshot: LibrarySnapshot): List<List<Int>> = withContext(Dispatchers.IO) {
        navidrome { c ->
            c.query("SELECT playlist_id, media_file_id FROM playlist_tracks ORDER BY playlist_id, id") { rs ->
                rs.getString(1) to snapshot.index[rs.getString(2)]
            }.filter { it.second != null }.groupBy({ it.first }, { it.second!! }).values.toList()
        } ?: emptyList()
    }

    override suspend fun navidromeUserId(username: String): String? = withContext(Dispatchers.IO) {
        navidrome { c -> c.queryOne("SELECT id FROM user WHERE user_name = ? COLLATE NOCASE", username) { it.getString(1) } }
    }

    private fun libraryVersion(): String = navidrome { c ->
        c.queryOne("SELECT count(*), max(updated_at) FROM media_file WHERE missing = 0") { "${it.getInt(1)}-${it.getString(2)}" }
    }.orEmpty()

    private fun featuresVersion(): String = features { c ->
        c.queryOne("SELECT count(*), max(analyzed_at) FROM songs") { "${it.getInt(1)}-${it.getLong(2)}" } +
            "-" + c.queryOne("SELECT value FROM meta WHERE key = 'prompts'") { it.getString(1).hashCode() }
    } ?: "none"

    private fun load(version: String): LibrarySnapshot {
        val lyricists = mutableMapOf<String, Int>()
        val raw = navidrome { c ->
            c.query(
                """SELECT id, title, album, album_id, artist, album_artist, album_artist_id, participants, year, duration,
                          genre, created_at, path FROM media_file WHERE missing = 0 ORDER BY album, track_number""",
            ) { rs -> song(rs, lyricists) }
        }.orEmpty()
        // Some files credit the lyricist as an artist too. Whoever is mostly credited for lyrics isn't a singer.
        val sung = raw.flatMap { s -> s.singers.map { it.id } }.groupingBy { it }.eachCount()
        val songs = raw.map { s -> s.copy(singers = s.singers.filter { (lyricists[it.id] ?: 0) <= (sung[it.id] ?: 0) }) }
        val index = songs.withIndex().associate { (i, s) -> s.id to i }
        val sound = arrayOfNulls<FloatArray>(songs.size)
        val tempo = FloatArray(songs.size) { Float.NaN }
        val energy = FloatArray(songs.size) { Float.NaN }
        val rhythm = FloatArray(songs.size) { Float.NaN }
        val prompts = mutableMapOf<String, FloatArray>()
        features { c ->
            c.query("SELECT id, tempo, energy, embedding, rhythm FROM songs WHERE embedding IS NOT NULL") { rs ->
                val i = index[rs.getString(1)] ?: return@query
                // Silence means the file is longer than its music (the analyzer measured past the end): unknown.
                if (rs.getFloat(3) > -100f && rs.getFloat(2) > 0f) {
                    tempo[i] = rs.getFloat(2)
                    energy[i] = rs.getFloat(3)
                    rhythm[i] = rs.getFloat(5)
                }
                sound[i] = floats(rs.getBytes(4))
            }
            c.query("SELECT key, embedding FROM prompts") { rs -> prompts[rs.getString(1)] = floats(rs.getBytes(2)) }
        }
        return LibrarySnapshot(songs, sound, moodScores(sound, prompts), prompts, tempo, energy, version, rhythm)
    }

    /** One song; counts lyricist credits into [lyricists]. */
    private fun song(rs: java.sql.ResultSet, lyricists: MutableMap<String, Int>): LibrarySong {
        val participants = runCatching { json.parseToJsonElement(rs.getString("participants") ?: "{}") as JsonObject }.getOrNull()
        fun people(role: String) = (participants?.get(role) as? JsonArray).orEmpty().mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Person(id, o["name"]?.jsonPrimitive?.content.orEmpty())
        }
        people("lyricist").forEach { lyricists.merge(it.id, 1, Int::plus) }
        val composerName = rs.getString("album_artist").orEmpty()
        val composer = rs.getString("album_artist_id")
            ?.takeIf { it.isNotBlank() && composerName.isNotBlank() && !composerName.startsWith("[Unknown") }
            ?.let { Person(it, composerName) }
        val genre = rs.getString("genre").orEmpty()
        val path = rs.getString("path").orEmpty()
        return LibrarySong(
            id = rs.getString("id"),
            title = rs.getString("title").orEmpty(),
            album = rs.getString("album").orEmpty(),
            albumId = rs.getString("album_id").orEmpty(),
            artist = rs.getString("artist").orEmpty(),
            singers = people("artist").filterNot { it.name.startsWith("[Unknown") },
            composer = composer,
            year = rs.getInt("year"),
            duration = rs.getDouble("duration").toInt(),
            genre = genre,
            addedAt = parseTime(rs.getString("created_at")) ?: 0,
            karaoke = genre.equals("Karaoke", true) || path.startsWith("Karaoke/") || composerName.equals("Karaoke", true),
            lyricists = people("lyricist").filterNot { it.name.startsWith("[Unknown") },
        )
    }

    /** Opens Navidrome's database read-only for one piece of work. */
    private fun <T> navidrome(block: (Connection) -> T): T? = readOnly(navidromeDb, block)

    private fun <T> features(block: (Connection) -> T): T? = featuresDb?.takeIf { File(it).isFile }?.let { readOnly(it, block) }

    private fun <T> readOnly(path: String, block: (Connection) -> T): T? =
        DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro").use { c ->
            c.createStatement().use { it.execute("PRAGMA busy_timeout = 10000") }
            block(c)
        }

    private companion object {
        const val CHECK_EVERY_MS = 60_000L
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun floats(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }

        /** Navidrome's times look like "2026-09-23 04:48:32.098666609+00:00" (always UTC). */
        fun parseTime(text: String?): Long? {
            if (text == null || text.length < 19) return null
            return runCatching {
                LocalDateTime.parse(text.substring(0, 19).replace('T', ' '), TIME).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
        }
    }
}

/**
 * How well each description fits each song, compared with how it fits songs in general (a z-score):
 * +2 means "fits much better than most songs". Raw CLAP scores are all similar, so only this comparison says anything.
 */
fun moodScores(sound: Array<FloatArray?>, prompts: Map<String, FloatArray>): Map<String, FloatArray> =
    prompts.mapValues { (_, prompt) ->
        val raw = FloatArray(sound.size) { i -> sound[i]?.let { dot(it, prompt) } ?: Float.NaN }
        zScores(raw)
    }

fun zScores(values: FloatArray): FloatArray {
    val known = values.filter { !it.isNaN() }
    if (known.size < 2) return FloatArray(values.size) { Float.NaN }
    val mean = known.average()
    val sd = kotlin.math.sqrt(known.sumOf { (it - mean) * (it - mean) } / known.size).coerceAtLeast(1e-6)
    return FloatArray(values.size) { if (values[it].isNaN()) Float.NaN else ((values[it] - mean) / sd).toFloat() }
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
    return s
}
