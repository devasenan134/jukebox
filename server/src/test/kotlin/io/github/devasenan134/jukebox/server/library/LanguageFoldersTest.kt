package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.query
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** A language's own folder inside the Tamil one (`…/malayalam`) is its own library, not Tamil music. */
class LanguageFoldersTest {
    @Test
    fun `a library's folder inside another is left out of the outer one`() = runBlocking {
        val root = Files.createTempDirectory("jukebox-music").toFile()
        val data = Files.createTempDirectory("jukebox-data").toFile()
        val db = Db(File(data, "jukebox.db").path)
        TestAudio.airaa(root, 1, "Kaariga", 300)
        TestAudio.song(File(root, "malayalam"), "Premam (2015)/01 - Malare.m4a", 500, mapOf("title" to "Malare", "album" to "Premam", "date" to "2015", "genre" to "Malayalam"))
        val tamil = LibraryDef("tamil", root.path, "film", "tamil")
        val malayalam = LibraryDef("malayalam", File(root, "malayalam").path, "film", "malayalam")
        val scanner = Scanner(db, AudioTools(), File(data, "artwork"), threads = 2)
        assertEquals(1, scanner.scan(tamil, setOf(File(malayalam.path))).files)
        assertEquals(1, scanner.scan(malayalam, setOf(root)).files) // the outer folder isn't "inside" it
        val albums = db.tx { query("SELECT a.title, l.name FROM albums a JOIN libraries l ON l.id = a.library_id ORDER BY a.title") { it.getString(1) to it.getString(2) } }
        assertEquals(listOf("Airaa" to "tamil", "Premam" to "malayalam"), albums)
    }

    @Test
    fun `a folder of extra files adds its songs to the main library's albums`() = runBlocking {
        val base = Files.createTempDirectory("jukebox-music").toFile()
        val data = Files.createTempDirectory("jukebox-data").toFile()
        val db = Db(File(data, "jukebox.db").path)
        val jiosaavn = File(base, "jiosaavn_m4a").apply { mkdirs() }
        val extra = File(base, "masstamilan").apply { mkdirs() }
        TestAudio.airaa(jiosaavn, 1, "Kaariga", 300)
        TestAudio.song(extra, "Airaa (2019)/03 - Paadal.m4a", 700, mapOf("title" to "Paadal", "album" to "Airaa", "date" to "2019"))
        TestAudio.song(extra, "Theri (2016)/01 - Eena Meena Teeka.m4a", 900, mapOf("title" to "Eena Meena Teeka", "album" to "Theri", "date" to "2016"))
        val scanner = Scanner(db, AudioTools(), File(data, "artwork"), threads = 2)
        scanner.scan(LibraryDef("tamil", jiosaavn.path, "film", "tamil"))
        scanner.scan(LibraryDef("tamil-masstamilan", extra.path, "film", "tamil", albumsOf = "tamil"))
        val albums = db.tx {
            query("""SELECT a.title, count(t.id) FROM albums a JOIN releases r ON r.album_id = a.id JOIN tracks t ON t.release_id = r.id
                     GROUP BY a.id ORDER BY a.title""") { it.getString(1) to it.getInt(2) }
        }
        assertEquals(listOf("Airaa" to 2, "Theri" to 1), albums) // Paadal joined Airaa; no second "Airaa"
    }
}
