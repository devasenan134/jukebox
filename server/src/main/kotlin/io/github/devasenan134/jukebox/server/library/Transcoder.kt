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

    /** Find an existing transcoded mobile copy of [recordingId] if available. Marks it used, for [enforceQuota]. */
    fun find(recordingId: String): File? {
        val file = File(transcodeDir, "$recordingId.opus")
        return file.takeIf { it.isFile && it.length() > 0 }?.also { it.setLastModified(System.currentTimeMillis()) }
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

    /**
     * Transcodes a batch of un-transcoded recordings, most played and newest first. All recordings are looked at
     * (skipping the ones with a copy), so the work goes on past the first few hundred; once the quota is full, it
     * stops instead of making copies that [enforceQuota] would delete again.
     */
    suspend fun processBatch(batchSize: Int = 10): Int {
        if (usedBytes() >= maxBytes * 95 / 100) return 0
        val done = transcodeDir.listFiles()?.filter { it.extension == "opus" }?.map { it.nameWithoutExtension }?.toHashSet().orEmpty()
        val candidates = db.read {
            query(
                """SELECT r.id FROM recordings r
                   JOIN files f ON f.recording_id = r.id AND f.missing_since IS NULL
                   LEFT JOIN play_counts p ON p.recording_id = r.id
                   WHERE r.merged_into IS NULL
                   GROUP BY r.id
                   ORDER BY coalesce(sum(p.count), 0) DESC, r.created_at DESC"""
            ) { it.getString(1) }
        }.filter { it !in done }

        var count = 0
        for (recId in candidates) {
            val target = File(transcodeDir, "$recId.opus")
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

    private val maxBytes get() = maxCacheGigabytes * 1024L * 1024L * 1024L

    private fun usedBytes() = transcodeDir.listFiles()?.filter { it.isFile && it.extension == "opus" }?.sumOf { it.length() } ?: 0L

    /** Evicts the least recently used copies ([find] marks a copy used) if the cache exceeds [maxCacheGigabytes]. */
    fun enforceQuota() {
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
