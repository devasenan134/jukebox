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
}
