package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.Fuzzy
import io.github.devasenan134.jukebox.server.Titles
import io.github.devasenan134.jukebox.server.insert
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection

@Serializable
data class DeepPassReport(val hashed: Int, val fingerprinted: Int, val merged: Int, val left: Int, val split: Int = 0)

private val log = LoggerFactory.getLogger("jukebox.fingerprints")

/**
 * The slow part of scanning, done in the background after a scan: the audio hash of every file (so a moved
 * file is recognized next time), a fingerprint of every recording, and merging recordings that are the
 * same performance (a "From X" single and the soundtrack's copy, a better-quality re-download).
 *
 * Two recordings are merged when their titles match (title_key), their lengths are within 2 s, they are
 * the same version with the same labels ([labels]: "Sangu (Area Version)" isn't "Sangu"), and their fingerprints
 * agree on at least [SAME] of the bits. The older id is kept. A solo, repeat or "version 2" shares the backing track,
 * so its fingerprint is close: before labels were compared, such versions were merged, and [splitVersions] parts them.
 */
class Fingerprints(private val db: Db, private val tools: AudioTools, private val libraryRoots: () -> Map<Long, String>) {
    suspend fun run(limit: Int = 200): DeepPassReport = withContext(Dispatchers.IO) {
        val roots = libraryRoots()
        fun file(libraryId: Long, path: String) = roots[libraryId]?.let { File(it, path) }?.takeIf { it.isFile }

        val split = db.tx { splitVersions() }

        var hashed = 0
        val toHash = db.tx {
            query("SELECT id, library_id, path FROM files WHERE audio_md5 IS NULL AND missing_since IS NULL LIMIT ?", limit) {
                Triple(it.getLong(1), it.getLong(2), it.getString(3))
            }
        }
        for ((id, lib, path) in toHash) {
            val md5 = file(lib, path)?.let(tools::audioMd5) ?: "-" // "-": tried and failed, don't try every time
            db.tx { update("UPDATE files SET audio_md5 = ? WHERE id = ?", md5, id) }
            hashed++
        }

        var fingerprinted = 0
        var merged = 0
        val toPrint = db.tx {
            query("""SELECT r.id, f.library_id, f.path FROM recordings r JOIN files f ON f.recording_id = r.id
                     WHERE r.fingerprint IS NULL AND r.merged_into IS NULL AND f.missing_since IS NULL
                     GROUP BY r.id LIMIT ?""", limit) { Triple(it.getString(1), it.getLong(2), it.getString(3)) }
        }
        for ((id, lib, path) in toPrint) {
            val print = file(lib, path)?.let(tools::fingerprint)
            db.tx {
                update("UPDATE recordings SET fingerprint = ? WHERE id = ?", print?.let(::toBytes) ?: ByteArray(0), id)
                if (print != null && mergeSame(id, print)) merged++
            }
            fingerprinted++
        }
        // Only what the queries above can pick up: a recording with no file on disk can't be fingerprinted, and
        // counting it kept the caller looping without a pause (and holding the database) forever.
        val left = db.tx {
            query("""SELECT (SELECT count(*) FROM files WHERE audio_md5 IS NULL AND missing_since IS NULL) +
                     (SELECT count(*) FROM recordings r WHERE r.fingerprint IS NULL AND r.merged_into IS NULL
                      AND EXISTS (SELECT 1 FROM files f WHERE f.recording_id = r.id AND f.missing_since IS NULL))""") { it.getInt(1) }.first()
        }
        if (merged + split > 0) db.catalogChanged()
        DeepPassReport(hashed, fingerprinted, merged, left, split).also { if (hashed + fingerprinted + split > 0) log.info("Deep pass: {}", it) }
    }

    /** Merges recording [id] with an earlier one that is the same performance. True if it did. */
    private fun Connection.mergeSame(id: String, print: IntArray): Boolean {
        val me = query("SELECT title_key, version, duration_ms, created_at, title FROM recordings WHERE id = ?", id) {
            listOf(it.getString(1), it.getString(2), it.getLong(3), it.getLong(4), it.getString(5))
        }.firstOrNull() ?: return false
        val (titleKey, version, duration, created) = me
        val mine = labels(me[4] as String)
        val candidates = query(
            """SELECT id, fingerprint, created_at, title FROM recordings WHERE title_key = ? AND version = ? AND id != ?
               AND merged_into IS NULL AND length(fingerprint) > 0 AND abs(duration_ms - ?) <= 2000""",
            titleKey, version, id, duration,
        ) { listOf(it.getString(1), fromBytes(it.getBytes(2)), it.getLong(3), it.getString(4)) }
            .filter { labels(it[3] as String) == mine }
            .map { Triple(it[0] as String, it[1] as IntArray, it[2] as Long) }
        val same = candidates.filter { AudioTools.similarity(print, it.second) >= SAME }.minByOrNull { it.third } ?: return false
        val (keep, drop) = if (same.third <= created as Long) same.first to id else id to same.first
        merge(drop, keep)
        log.info("Merged recording {} into {} (same performance)", drop, keep)
        return true
    }

    /**
     * Parts the versions merged into one recording before labels were compared: the files of a recording whose track
     * titles name different versions ("Sangu" and "Sangu (Area Version)") get a recording each, of the same song. The
     * file with the fewest labels (the song itself) keeps the recording, and the playlists and likes on it. How many
     * recordings it made.
     */
    private fun Connection.splitVersions(): Int {
        class Part(val trackId: String, val title: String, val durationMs: Long)
        val byRecording = query(
            """SELECT t.recording_id, t.id, t.title, f.duration_ms, r.title, r.song_id FROM tracks t JOIN files f ON f.track_id = t.id
               JOIN recordings r ON r.id = t.recording_id
               WHERE f.missing_since IS NULL AND r.merged_into IS NULL AND t.recording_id IN
                 (SELECT t2.recording_id FROM tracks t2 JOIN files f2 ON f2.track_id = t2.id WHERE f2.missing_since IS NULL
                  GROUP BY t2.recording_id HAVING count(DISTINCT t2.title) > 1)""",
        ) { Triple(it.getString(1), it.getString(5), it.getString(6)) to Part(it.getString(2), it.getString(3), it.getLong(4)) }
            .groupBy({ it.first }, { it.second })
        var made = 0
        val now = System.currentTimeMillis()
        for ((recording, parts) in byRecording) {
            val (id, title, songId) = recording
            val groups = parts.groupBy { labels(it.title) }
            if (groups.size < 2) continue
            val own = labels(title)
            val keep = groups.keys.minWith(compareBy<Set<String>> { it.size }.thenBy { if (it == own) 0 else 1 }.thenBy { it.sorted().joinToString() })
            for ((key, group) in groups) {
                if (key == keep) continue
                val (base, version) = Names.version(group.first().title)
                val newId = newId()
                insert(
                    "INSERT INTO recordings (id, song_id, title, title_key, version, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    newId, songId, base, Fuzzy.key(base), version, group.first().durationMs, now,
                )
                group.forEach { part ->
                    update("UPDATE tracks SET recording_id = ? WHERE id = ?", newId, part.trackId)
                    update("UPDATE files SET recording_id = ? WHERE track_id = ?", newId, part.trackId)
                }
                update("INSERT OR IGNORE INTO recording_credits SELECT ?, person_id, role, position FROM recording_credits WHERE recording_id = ?", newId, id)
                update("INSERT OR IGNORE INTO lyrics SELECT ?, source, script, synced, text FROM lyrics WHERE recording_id = ?", newId, id)
                log.info("Split {} off recording {}: \"{}\" is another version", newId, id, group.first().title)
                made++
            }
            Names.version(groups.getValue(keep).first().title).first.let { base ->
                update("UPDATE recordings SET title = ? WHERE id = ?", base, id)
            }
        }
        return made
    }

    private fun Connection.merge(from: String, into: String) {
        update("UPDATE tracks SET recording_id = ? WHERE recording_id = ?", into, from)
        update("UPDATE files SET recording_id = ? WHERE recording_id = ?", into, from)
        update("INSERT OR IGNORE INTO recording_credits SELECT ?, person_id, role, position FROM recording_credits WHERE recording_id = ?", into, from)
        update("DELETE FROM recording_credits WHERE recording_id = ?", from)
        update("INSERT OR IGNORE INTO lyrics SELECT ?, source, script, synced, text FROM lyrics WHERE recording_id = ?", into, from)
        update("DELETE FROM lyrics WHERE recording_id = ?", from)
        update("""UPDATE recordings SET isrc = coalesce(isrc, (SELECT isrc FROM recordings WHERE id = ?)),
                  mbid = coalesce(mbid, (SELECT mbid FROM recordings WHERE id = ?)),
                  saavn_id = coalesce(saavn_id, (SELECT saavn_id FROM recordings WHERE id = ?)) WHERE id = ?""", from, from, from, into)
        update("UPDATE recordings SET merged_into = ? WHERE id = ?", into, from)
        update("UPDATE recordings SET merged_into = ? WHERE merged_into = ?", into, from)
    }

    companion object {
        const val SAME = 0.85
        private val bracket = Regex("""[(\[]([^)\]]*)[)\]]""")

        /**
         * What a title says about which version it is, beyond the version [Names.version] knows: the labels in its
         * brackets ("Area Version", "Repeat", "Version, 2", "Hariharan"), and that version. Its film ("From …"), "feat."
         * and a language tag ("(Tamil)": JioSaavn's single of the album's song) aren't.
         */
        fun labels(title: String): Set<String> {
            val (base, version) = Names.version(title)
            val keys = bracket.findAll(Titles.song(base).first).map { Fuzzy.key(it.groupValues[1]) }.filter { it.isNotEmpty() }.toSet()
            return if (version == "original") keys else keys + version
        }

        fun toBytes(print: IntArray): ByteArray =
            ByteBuffer.allocate(print.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(print) }.array()

        fun fromBytes(bytes: ByteArray): IntArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            return IntArray(buffer.remaining()).also(buffer::get)
        }
    }
}
