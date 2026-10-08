package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.Fuzzy
import io.github.devasenan134.jukebox.server.insert
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.queryOne
import io.github.devasenan134.jukebox.server.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager
import kotlin.math.exp

private val log = LoggerFactory.getLogger("jukebox.scores")

@Serializable
data class ScoreSplitReport(val moved: Int, val movedBack: Int, val byTitle: Int, val bySound: Int, val byHand: Int)

/**
 * Background music that sits in a film's song album goes to the film's background score, after each scan.
 *
 * Folders only say "(Original Background Score)" when JioSaavn had a separate score album; many soundtracks mix
 * the two. A track goes to the score when:
 * - its title says so ([Names.isBgmTitle]: "Title Music", "Mass BGM", "End Credits"; never "Theme Song"), or
 * - it sounds like score and nothing says it's sung: no lyrics, no singer but the composer, not a karaoke. "Sounds
 *   like" is a small model learnt from this library each time the analyzer's data changes: the score albums'
 *   tracks against songs with lyrics (98% right on held-out tracks, 2026-10-08), or
 * - `score-overrides.txt` in the data folder says so. Lines are `score: <path>` or `song: <path>`, with the path
 *   inside the library as `score-split.csv` lists it; `song:` keeps a track with the songs whatever the rules say.
 *
 * Files are never touched: a moved track only points at another release, and `score_moves` remembers where it
 * came from, so changing the rules or an override moves it back. `score-split.csv` lists every move and its reason.
 */
class ScoreSplitter(
    private val db: Db,
    private val featuresDb: String?,
    private val dataDir: File,
) {
    private class Track(
        val id: String, val releaseId: String, val releaseKind: String, val albumKey: String, val albumYear: Int?,
        val title: String, val albumTitle: String, val recordingId: String, val karaoke: Boolean, val lyrics: Boolean,
        val singerIsComposer: Boolean, val path: String,
    )

    private class Model(val version: String, val weights: FloatArray, val bias: Float)

    @Volatile private var model: Model? = null

    suspend fun run(): ScoreSplitReport {
        val (forced, kept) = readOverrides()
        val moved = db.read { query("SELECT track_id, from_release_id FROM score_moves") { it.getString(1) to it.getString(2) }.toMap() }
        val tracks = db.read { tracks() }
        // A track's own kind is the release it came from, not the score it was moved to.
        val releaseKinds = db.read { query("SELECT id, kind FROM releases") { it.getString(1) to it.getString(2) }.toMap() }
        fun ownKind(t: Track) = moved[t.id]?.let { releaseKinds[it] } ?: t.releaseKind

        val songs = tracks.filter { ownKind(it) != "score" }
        val model = withContext(Dispatchers.IO) { runCatching { model(tracks.filter { ownKind(it) == "score" }, songs) }
            .onFailure { log.warn("Couldn't learn what score sounds like; only titles and overrides count", it) }.getOrNull() }
        val unsure = songs.filter { !it.lyrics && it.singerIsComposer && !it.karaoke }
        val sounds = if (model == null) emptyMap() else withContext(Dispatchers.IO) { probabilities(model, unsure.map { it.recordingId }.toSet()) }

        val reasons = HashMap<String, String>()
        for (t in songs) {
            reasons[t.id] = when {
                t.path in kept -> continue
                t.path in forced -> "by hand"
                Names.isBgmTitle(t.title) -> "title"
                !t.lyrics && t.singerIsComposer && !t.karaoke && (sounds[t.recordingId] ?: 0f) >= SOUND_BAR -> "sound"
                else -> continue
            }
        }

        var movedNow = 0
        var movedBack = 0
        db.tx {
            val now = System.currentTimeMillis()
            for (t in songs) {
                val reason = reasons[t.id]
                if (reason != null && t.releaseKind != "score") {
                    val score = scoreRelease(t.albumKey, t.albumYear)
                    update("UPDATE tracks SET release_id = ? WHERE id = ?", score, t.id)
                    update("INSERT OR REPLACE INTO score_moves (track_id, from_release_id, reason, moved_at) VALUES (?, ?, ?, ?)", t.id, t.releaseId, reason, now)
                    movedNow++
                } else if (reason == null && t.id in moved) {
                    // A rule or an override changed: back to the release it came from.
                    val from = moved.getValue(t.id)
                    if (t.releaseKind == "score" && from in releaseKinds) update("UPDATE tracks SET release_id = ? WHERE id = ?", from, t.id)
                    update("DELETE FROM score_moves WHERE track_id = ?", t.id)
                    movedBack++
                } else if (reason != null) {
                    update("UPDATE score_moves SET reason = ? WHERE track_id = ? AND reason != ?", reason, t.id, reason)
                }
            }
            // Moves of tracks that no longer exist, and score releases left empty by moving tracks back.
            update("DELETE FROM score_moves WHERE track_id NOT IN (SELECT id FROM tracks)")
            update("DELETE FROM releases WHERE kind = 'score' AND id NOT IN (SELECT release_id FROM tracks)")
        }
        if (movedNow + movedBack > 0) db.catalogChanged()
        writeList(tracks.associateBy { it.id }, reasons)
        val report = ScoreSplitReport(movedNow, movedBack, reasons.values.count { it == "title" }, reasons.values.count { it == "sound" },
            reasons.values.count { it == "by hand" })
        log.info("Background score: {} tracks moved now, {} moved back; in score by title {}, by sound {}, by hand {}",
            movedNow, movedBack, report.byTitle, report.bySound, report.byHand)
        return report
    }

    /** Every track of a film library with a file, and what the rules need to know about it. */
    private fun Connection.tracks(): List<Track> = query(
        """SELECT t.id, rl.id, rl.kind, a.group_key, a.year, t.title, a.title, r.id, r.version = 'karaoke',
                  EXISTS (SELECT 1 FROM lyrics l WHERE l.recording_id = r.id),
                  NOT EXISTS (SELECT 1 FROM recording_credits s WHERE s.recording_id = r.id AND s.role = 'singer'
                                AND s.person_id NOT IN (SELECT person_id FROM recording_credits WHERE recording_id = r.id AND role = 'composer')
                                AND s.person_id NOT IN (SELECT person_id FROM album_credits WHERE album_id = a.id AND role = 'composer')),
                  f.path
             FROM tracks t JOIN releases rl ON rl.id = t.release_id JOIN albums a ON a.id = rl.album_id
             JOIN recordings r ON r.id = t.recording_id
             JOIN files f ON f.track_id = t.id AND f.missing_since IS NULL
             JOIN libraries l ON l.id = f.library_id AND l.kind = 'film'""",
    ) {
        Track(it.getString(1), it.getString(2), it.getString(3), it.getString(4), (it.getObject(5) as Number?)?.toInt(),
            it.getString(6), it.getString(7), it.getString(8), it.getInt(9) == 1, it.getInt(10) == 1, it.getInt(11) == 1, it.getString(12))
    }.distinctBy { it.id }

    /** The album's background score release, made the way the scanner makes it for a score folder. */
    private fun Connection.scoreRelease(albumKey: String, year: Int?): String {
        val key = "$albumKey|score|${Fuzzy.key("Background Score")}"
        return queryOne("SELECT id FROM releases WHERE group_key = ?", key) { it.getString(1) } ?: newId().also {
            val albumId = queryOne("SELECT id FROM albums WHERE group_key = ?", albumKey) { r -> r.getString(1) }
            insert("INSERT INTO releases (id, album_id, title, kind, year, group_key) VALUES (?, ?, 'Background Score', 'score', ?, ?)", it, albumId, year, key)
        }
    }

    /**
     * Logistic regression on the analyzer's sound embeddings: the score albums' tracks (that weren't moved there by
     * this class) against songs with lyrics. Learnt again only when the analyzer's data or those sets change.
     */
    private fun model(scores: List<Track>, songs: List<Track>): Model? {
        val path = featuresDb?.takeIf { File(it).isFile } ?: return null
        val positives = scores.map { it.recordingId }.distinct()
        val negatives = songs.filter { it.lyrics && !it.karaoke }.map { it.recordingId }.distinct()
            .sortedBy { it.hashCode() }.take(MAX_NEGATIVES)
        if (positives.size < 50 || negatives.size < 50) return null
        val version = readOnly(path) { c -> c.queryOne("SELECT count(*), max(analyzed_at) FROM songs") { "${it.getInt(1)}-${it.getLong(2)}" } } +
            "/${positives.size}/${negatives.size}"
        model?.takeIf { it.version == version }?.let { return it }

        val vectors = embeddings(path, (positives + negatives).toSet())
        val x = ArrayList<FloatArray>()
        val y = ArrayList<Int>()
        positives.forEach { id -> vectors[id]?.let { x += it; y += 1 } }
        negatives.forEach { id -> vectors[id]?.let { x += it; y += 0 } }
        if (y.count { it == 1 } < 50 || y.count { it == 0 } < 50) return null
        val (w, b) = fit(x, y)
        log.info("Learnt what background score sounds like from {} score tracks and {} songs", y.count { it == 1 }, y.count { it == 0 })
        return Model(version, w, b).also { model = it }
    }

    private fun probabilities(model: Model, ids: Set<String>): Map<String, Float> {
        val path = featuresDb ?: return emptyMap()
        return embeddings(path, ids).mapValues { (_, v) -> sigmoid(SCALE * dot(v, model.weights) + model.bias) }
    }

    private fun embeddings(path: String, ids: Set<String>): Map<String, FloatArray> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, FloatArray>()
        readOnly(path) { c ->
            if (runCatching { c.queryOne("SELECT value FROM meta WHERE key = 'ids'") { it.getString(1) } }.getOrNull() != "recording") return@readOnly
            c.query("SELECT id, embedding FROM songs WHERE embedding IS NOT NULL") { rs ->
                val id = rs.getString(1)
                if (id in ids) out[id] = floats(rs.getBytes(2))
            }
        }
        return out
    }

    private fun readOverrides(): Pair<Set<String>, Set<String>> {
        val file = File(dataDir, "score-overrides.txt").takeIf { it.isFile } ?: return emptySet<String>() to emptySet()
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        fun paths(prefix: String) = lines.filter { it.startsWith(prefix, ignoreCase = true) }.map { it.substring(prefix.length).trim() }.toSet()
        return paths("score:") to paths("song:")
    }

    /** score-split.csv: every track on a score release because of this class, and why. */
    private fun writeList(tracks: Map<String, Track>, reasons: Map<String, String>) {
        runCatching {
            fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
            val rows = reasons.mapNotNull { (id, reason) -> tracks[id]?.let { reason to it } }
                .sortedWith(compareBy({ it.second.albumTitle }, { it.second.path }))
                .joinToString("") { (reason, t) -> listOf(reason, t.albumTitle, t.title, t.path).joinToString(",", transform = ::cell) + "\n" }
            File(dataDir, "score-split.csv").writeText("reason,album,title,path\n$rows")
        }.onFailure { log.warn("Couldn't write score-split.csv", it) }
    }

    private fun <T> readOnly(path: String, block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro").use { c ->
            c.createStatement().use { it.execute("PRAGMA busy_timeout = 10000") }
            block(c)
        }

    companion object {
        /** How sure the sound model must be. At 0.9 it was right 95% of the time on held-out tracks (2026-10-08). */
        const val SOUND_BAR = 0.9f
        private const val SCALE = 10f
        private const val MAX_NEGATIVES = 8000

        private fun floats(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
            return s
        }

        private fun sigmoid(z: Float) = (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()

        /** Class-balanced logistic regression by gradient descent (the embeddings are unit length, hence [SCALE]). */
        internal fun fit(x: List<FloatArray>, y: List<Int>, iterations: Int = 300, rate: Float = 0.5f, l2: Float = 1e-3f): Pair<FloatArray, Float> {
            val dims = x.first().size
            val w = FloatArray(dims)
            var b = 0f
            val n = y.size
            val ones = y.count { it == 1 }
            val weightOne = n / (2f * ones)
            val weightZero = n / (2f * (n - ones))
            val grad = FloatArray(dims)
            repeat(iterations) {
                grad.fill(0f)
                var gb = 0f
                for (i in 0 until n) {
                    val g = (sigmoid(SCALE * dot(x[i], w) + b) - y[i]) * if (y[i] == 1) weightOne else weightZero
                    val xi = x[i]
                    for (d in 0 until dims) grad[d] += g * xi[d]
                    gb += g
                }
                for (d in 0 until dims) w[d] -= rate * (grad[d] * SCALE / n + l2 * w[d])
                b -= rate * gb / n
            }
            return w to b
        }
    }
}
