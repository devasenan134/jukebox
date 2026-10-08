package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.queryOne
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Manages ahead-of-time mobile audio copies (Opus 128 kbps).
 * Stores transcoded files in [transcodeDir] and generates them in the background
 * without touching or modifying the read-only music library.
 */
class Transcoder(
    private val db: Db,
    private val audioTools: AudioTools,
    val transcodeDir: File,
    private val roots: () -> Map<Long, File>,
    private val maxCacheGigabytes: Long = 50,
) {
    private val log = LoggerFactory.getLogger("jukebox.transcoder")

    init {
        transcodeDir.mkdirs()
    }

    /** Find an existing transcoded mobile copy of [recordingId] if available. */
    fun find(recordingId: String): File? {
        val file = File(transcodeDir, "$recordingId.opus")
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    private fun Connection.resolveRecordingId(id: String): String {
        var current = id
        repeat(10) {
            current = queryOne("SELECT merged_into FROM recordings WHERE id = ?", current) { it.getString(1) } ?: return current
        }
        return current
    }

    /** Resolves the best source audio file and format for a recording on disk. */
    suspend fun resolveSource(recordingId: String): Pair<File, String>? = db.read {
        val rid = resolveRecordingId(recordingId)
        val fileInfo = queryOne(
            """SELECT library_id, path, format FROM files WHERE recording_id = ? AND missing_since IS NULL
               ORDER BY coalesce(bitrate, 0) DESC LIMIT 1""", rid
        ) { Triple(it.getLong(1), it.getString(2), it.getString(3)) } ?: return@read null

        val root = roots()[fileInfo.first] ?: return@read null
        val file = File(root, fileInfo.second).takeIf { it.isFile } ?: return@read null
        file to fileInfo.third
    }

    /** Transcodes one recording synchronously (or returns existing file if already transcoded). */
    suspend fun transcodeOne(recordingId: String): File? {
        find(recordingId)?.let { return it }
        val source = resolveSource(recordingId) ?: return null
        val target = File(transcodeDir, "$recordingId.opus")
        val ok = audioTools.transcodeToOpus(source.first, target)
        return if (ok && target.isFile && target.length() > 0) target else null
    }

    /**
     * Starts the background transcoding worker.
     * Processes un-transcoded tracks in prioritized batches when idle.
     */
    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        log.info("Starting background transcoder in {}", transcodeDir.path)
        delay(30.seconds)
        while (isActive) {
            try {
                enforceQuota()
                processBatch()
            } catch (e: Throwable) {
                log.warn("Background transcoding error", e)
            }
            delay(5.minutes)
        }
    }

    /** Transcodes a batch of un-transcoded recordings, prioritized by play counts and recency. */
    suspend fun processBatch(batchSize: Int = 10): Int {
        val candidates = db.read {
            query(
                """SELECT r.id FROM recordings r
                   JOIN files f ON f.recording_id = r.id AND f.missing_since IS NULL
                   LEFT JOIN play_counts p ON p.recording_id = r.id
                   WHERE r.merged_into IS NULL
                   GROUP BY r.id
                   ORDER BY coalesce(sum(p.count), 0) DESC, r.created_at DESC
                   LIMIT 200"""
            ) { it.getString(1) }
        }

        var count = 0
        for (recId in candidates) {
            val target = File(transcodeDir, "$recId.opus")
            if (target.isFile && target.length() > 0) continue

            val source = resolveSource(recId) ?: continue
            val ok = audioTools.transcodeToOpus(source.first, target)
            if (ok) {
                count++
                if (count >= batchSize) break
            }
        }
        if (count > 0) {
            log.info("Transcoded {} mobile copies to Opus", count)
        }
        return count
    }

    /** Evicts least recently modified files if cache exceeds [maxCacheGigabytes]. */
    fun enforceQuota() {
        val maxBytes = maxCacheGigabytes * 1024L * 1024L * 1024L
        val files = transcodeDir.listFiles()?.filter { it.isFile && it.extension == "opus" } ?: return
        var totalSize = files.sumOf { it.length() }
        if (totalSize <= maxBytes) return

        val sortedByAge = files.sortedBy { it.lastModified() }
        for (f in sortedByAge) {
            if (totalSize <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                totalSize -= len
            }
        }
    }
}
