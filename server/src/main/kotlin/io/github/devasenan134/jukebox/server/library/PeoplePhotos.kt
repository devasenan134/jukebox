package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.update
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("jukebox.people")

/**
 * People's photos: `people-photos/<name>.jpg` (or .png) in the data folder, matched to a person by name or by any
 * spelling merged into them, after each scan. scripts/metadata/people_photos.py fills the folder from JioSaavn's
 * artist pictures; a file put there by hand replaces one, and deleting a file takes the photo away.
 */
class PeoplePhotos(private val db: Db, private val folder: File, private val artworkDir: File) {
    /** Files already stored, by path and modification time, so a rescan doesn't read every photo again. */
    private val stored = HashMap<String, Pair<Long, String>>()

    suspend fun run(): Int {
        val files = folder.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") }.orEmpty()
        if (files.isEmpty() && stored.isEmpty()) {
            val any = db.read { query("SELECT 1 FROM people WHERE photo_id IS NOT NULL LIMIT 1") { 1 } }.isNotEmpty()
            if (!any) return 0
        }
        val changed = db.tx {
            // Every spelling of a person leads to them.
            val byKey = HashMap<String, String>()
            query("SELECT id, name, coalesce(aliases, '') FROM people WHERE merged_into IS NULL") { rs ->
                val id = rs.getString(1)
                (listOf(rs.getString(2)) + rs.getString(3).split('\n')).filter { it.isNotBlank() }.forEach { byKey.putIfAbsent(Names.personKey(it), id) }
            }
            val photos = HashMap<String, String>()
            for (f in files.sortedBy { it.name }) {
                val person = byKey[Names.personKey(f.nameWithoutExtension)] ?: continue
                val artwork = stored[f.path]?.takeIf { it.first == f.lastModified() }?.second
                    ?: runCatching { storeArtwork(artworkDir, f.readBytes(), "person") }.onFailure { log.warn("Couldn't read {}", f, it) }.getOrNull()
                    ?: continue
                stored[f.path] = f.lastModified() to artwork
                photos[person] = artwork
            }
            var n = 0
            val current = query("SELECT id, photo_id FROM people WHERE photo_id IS NOT NULL") { it.getString(1) to it.getString(2) }.toMap()
            for ((person, artwork) in photos) if (current[person] != artwork) n += update("UPDATE people SET photo_id = ? WHERE id = ?", artwork, person)
            for (person in current.keys - photos.keys) n += update("UPDATE people SET photo_id = NULL WHERE id = ?", person)
            n
        }
        if (changed > 0) {
            db.catalogChanged()
            log.info("People's photos: {} changed, {} files in {}", changed, files.size, folder)
        }
        return changed
    }
}
