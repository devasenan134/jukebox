package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("jukebox.library")

@Serializable
data class LibraryStatus(
    val libraries: List<String>,
    val scanning: Boolean,
    val lastScans: List<ScanReport>,
    val albums: Int,
    val recordings: Int,
    val files: Int,
    val missingFiles: Int,
    val waitingForFingerprint: Int,
)

/**
 * The music folders, scanned when the server starts (and every [rescanEveryMinutes]), with the slow
 * fingerprint pass running in the background in between.
 *
 * Settings (environment): LIBRARIES="name=path:kind:language;..." (kind "film" or "album"),
 * SAAVN_ID_MAP=a CSV with path and saavn_id columns (paths inside the library), FINGERPRINTS=off to skip
 * the background pass.
 */
class MusicLibrary(
    private val db: Db,
    val libraries: List<LibraryDef>,
    val tools: AudioTools,
    val artworkDir: File,
    saavnIdMap: File? = null,
    private val fingerprintsOn: Boolean = true,
    private val rescanEveryMinutes: Long = 60,
    featuresDb: String? = null,
) {
    private val saavnIds: Map<String, String> = saavnIdMap?.takeIf { it.isFile }?.let(::readSaavnIds).orEmpty()
    val scanner = Scanner(db, tools, artworkDir, externalIds = { _, path -> saavnIds[path] }, threads = 3)
    private val fingerprints = Fingerprints(db, tools) { roots }
    /** One person, one entry: spellings folded together after each scan; `people-overrides.txt` next to the artwork corrects it. */
    val people = PeopleMerger(db, File(artworkDir.parentFile, "people-overrides.txt"))
    val covers = Covers(artworkDir, tools)
    /** Background music in a song album goes to the film's score (`score-overrides.txt` next to the artwork corrects it). */
    val scores = ScoreSplitter(db, featuresDb, artworkDir.parentFile)
    val photos = PeoplePhotos(db, File(artworkDir.parentFile, "people-photos"), artworkDir)
    private val lock = Mutex()
    @Volatile private var scanning = false
    @Volatile private var roots: Map<Long, String> = emptyMap()

    /** Library id → folder, for turning a file's path into a file. */
    fun roots(): Map<Long, String> = roots

    suspend fun scanAll(): List<ScanReport> = lock.withLock {
        scanning = true
        try {
            libraries.mapNotNull { l -> runCatching { scanner.scan(l) }.onFailure { log.error("Scanning {} failed", l.name, it) }.getOrNull() }
                .also {
                    refreshRoots()
                    runCatching { people.run() }.onFailure { log.error("Merging people failed", it) }
                    runCatching { scores.run() }.onFailure { log.error("Sorting out background score failed", it) }
                    runCatching { photos.run() }.onFailure { log.warn("Reading people's photos failed", it) }
                    runCatching { covers.warm(db) }.onFailure { log.warn("Making small covers failed", it) }
                }
        } finally {
            scanning = false
        }
    }

    suspend fun refreshRoots() {
        val byName = libraries.associate { it.name to it.path }
        roots = db.tx { query("SELECT id, name FROM libraries") { it.getLong(1) to it.getString(2) } }
            .mapNotNull { (id, name) -> byName[name]?.let { id to it } }.toMap()
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            refreshRoots()
            while (isActive) {
                scanAll()
                // Fingerprint in batches until done or it's time to rescan (0: scan only at start and when asked).
                val until = if (rescanEveryMinutes > 0) System.currentTimeMillis() + rescanEveryMinutes * 60_000 else Long.MAX_VALUE
                while (isActive && System.currentTimeMillis() < until) {
                    val left = if (fingerprintsOn) runCatching { lock.withLock { fingerprints.run(limit = 50) }.left }
                        .onFailure { log.warn("Fingerprint pass failed", it) }.getOrDefault(0) else 0
                    if (left == 0) delay(minOf(5 * 60_000L, until - System.currentTimeMillis()).coerceAtLeast(1_000))
                }
            }
        }
    }

    suspend fun status(): LibraryStatus = db.tx {
        fun count(sql: String) = query(sql) { it.getInt(1) }.first()
        LibraryStatus(
            libraries = libraries.map { "${it.name} (${it.kind})" },
            scanning = scanning,
            lastScans = query("SELECT info FROM scans WHERE finished_at IS NOT NULL ORDER BY id DESC LIMIT ?", libraries.size.coerceAtLeast(1)) {
                runCatching { kotlinx.serialization.json.Json.decodeFromString(ScanReport.serializer(), it.getString(1)) }.getOrNull()
            }.filterNotNull(),
            albums = count("SELECT count(*) FROM albums"),
            recordings = count("SELECT count(*) FROM recordings WHERE merged_into IS NULL"),
            files = count("SELECT count(*) FROM files WHERE missing_since IS NULL"),
            missingFiles = count("SELECT count(*) FROM files WHERE missing_since IS NOT NULL"),
            waitingForFingerprint = count("SELECT count(*) FROM recordings WHERE fingerprint IS NULL AND merged_into IS NULL"),
        )
    }

    companion object {
        /** "tamil=/music/tamil:film:tamil;english=/music/english" → libraries. */
        fun parse(setting: String?): List<LibraryDef> = setting.orEmpty().split(';').map(String::trim).filter { '=' in it }.map { entry ->
            val name = entry.substringBefore('=').trim()
            val parts = entry.substringAfter('=').split(':')
            LibraryDef(name, parts[0].trim(), parts.getOrNull(1)?.trim()?.ifEmpty { null } ?: "album", parts.getOrNull(2)?.trim()?.ifEmpty { null })
        }

        /** A CSV with a header naming "path" and "saavn_id" columns (like the collectors' songs.csv). */
        fun readSaavnIds(file: File): Map<String, String> {
            val lines = file.readLines()
            if (lines.isEmpty()) return emptyMap()
            val header = csv(lines.first())
            val pathAt = header.indexOf("path")
            val idAt = header.indexOf("saavn_id")
            if (pathAt < 0 || idAt < 0) return emptyMap()
            return lines.drop(1).map(::csv).filter { it.size > maxOf(pathAt, idAt) && it[idAt].isNotEmpty() }.associate { it[pathAt] to it[idAt] }
        }

        private fun csv(line: String): List<String> {
            val out = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out += cell.toString(); cell.clear() }
                    else -> cell.append(c)
                }
                i++
            }
            out += cell.toString()
            return out
        }
    }
}
