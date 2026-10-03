package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
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
data class DeepPassReport(val hashed: Int, val fingerprinted: Int, val merged: Int, val left: Int)

private val log = LoggerFactory.getLogger("jukebox.fingerprints")

/**
 * The slow part of scanning, done in the background after a scan: the audio hash of every file (so a moved
 * file is recognized next time), a fingerprint of every recording, and merging recordings that are the
 * same performance (a "From X" single and the soundtrack's copy, a better-quality re-download).
 *
 * Two recordings are merged when their titles match (title_key), their lengths are within 2 s, they are
 * the same version, and their fingerprints agree on at least [SAME] of the bits. The older id is kept.
 */
class Fingerprints(private val db: Db, private val tools: AudioTools, private val libraryRoots: () -> Map<Long, String>) {
    suspend fun run(limit: Int = 200): DeepPassReport = withContext(Dispatchers.IO) {
        val roots = libraryRoots()
        fun file(libraryId: Long, path: String) = roots[libraryId]?.let { File(it, path) }?.takeIf { it.isFile }

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
        val left = db.tx {
            query("SELECT (SELECT count(*) FROM files WHERE audio_md5 IS NULL AND missing_since IS NULL) + " +
                "(SELECT count(*) FROM recordings WHERE fingerprint IS NULL AND merged_into IS NULL)") { it.getInt(1) }.first()
        }
        if (merged > 0) db.catalogChanged()
        DeepPassReport(hashed, fingerprinted, merged, left).also { if (hashed + fingerprinted > 0) log.info("Deep pass: {}", it) }
    }

    /** Merges recording [id] with an earlier one that is the same performance. True if it did. */
    private fun Connection.mergeSame(id: String, print: IntArray): Boolean {
        val me = query("SELECT title_key, version, duration_ms, created_at FROM recordings WHERE id = ?", id) {
            listOf(it.getString(1), it.getString(2), it.getLong(3), it.getLong(4))
        }.firstOrNull() ?: return false
        val (titleKey, version, duration, created) = me
        val candidates = query(
            """SELECT id, fingerprint, created_at FROM recordings WHERE title_key = ? AND version = ? AND id != ?
               AND merged_into IS NULL AND length(fingerprint) > 0 AND abs(duration_ms - ?) <= 2000""",
            titleKey, version, id, duration,
        ) { Triple(it.getString(1), fromBytes(it.getBytes(2)), it.getLong(3)) }
        val same = candidates.filter { AudioTools.similarity(print, it.second) >= SAME }.minByOrNull { it.third } ?: return false
        val (keep, drop) = if (same.third <= created as Long) same.first to id else id to same.first
        merge(drop, keep)
        log.info("Merged recording {} into {} (same performance)", drop, keep)
        return true
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

        fun toBytes(print: IntArray): ByteArray =
            ByteBuffer.allocate(print.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(print) }.array()

        fun fromBytes(bytes: ByteArray): IntArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            return IntArray(buffer.remaining()).also(buffer::get)
        }
    }
}
