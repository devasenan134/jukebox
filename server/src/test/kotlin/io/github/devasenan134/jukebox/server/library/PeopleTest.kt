package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.query
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonNamesTest {
    private fun same(main: String, mainCredits: Int, other: String, otherCredits: Int) = PersonNames.same(main, mainCredits, other, otherCredits)

    @Test
    fun `spellings of one person are the same person`() {
        assertTrue(same("Ilaiyaraaja", 6448, "Ilayaraja", 25))
        assertTrue(same("S.P. Balasubrahmanyam", 1500, "S.P.Balasubramaniam", 280))
        assertTrue(same("T.M. Soundararajan", 1136, "T. M. Sounderrajan", 96))
        assertTrue(same("Malaysia Vasudevan", 836, "Malayasia Vasudevan", 69))
        assertTrue(same("P. Susheela", 1618, "Sushila P.", 3))
        assertTrue(same("S. Thaman", 215, "Thaman S", 136))
        assertTrue(same("Karthik", 546, "Karhik", 1))
        assertTrue(same("Swarnalatha", 361, "Swaranalatha", 3))
        assertTrue(same("Premgi Amaren", 49, "Premji Amaran", 7))
    }

    @Test
    fun `different people with similar names stay apart`() {
        assertFalse(same("Hariharan", 339, "Haricharan", 286)) // both well known
        assertFalse(same("Vivek", 97, "Viveka", 39))
        assertFalse(same("Mahathi", 42, "Malathi", 19))
        assertFalse(same("Ghibran", 451, "Kiran", 1)) // first letter differs
        assertFalse(same("Harini", 152, "Hashini", 1)) // a consonant for a consonant
        assertFalse(same("S.P. Balasubrahmanyam", 1500, "S. Balasubramanian", 12)) // other initials
        assertFalse(same("Karthik", 546, "Karthi", 4)) // last letter differs
        assertFalse(same("Sujatha", 139, "Suchithra", 100))
    }
}

class PeopleMergerTest {
    private val root = Files.createTempDirectory("jukebox-music").toFile()
    private val data = Files.createTempDirectory("jukebox-data").toFile()
    private val db = Db(File(data, "jukebox.db").path)
    private val overrides = File(data, "people-overrides.txt")
    private val scanner = Scanner(db, AudioTools(), File(data, "artwork"), threads = 2)
    private val merger = PeopleMerger(db, overrides)
    private val library = LibraryDef("tamil", root.path, kind = "film")

    private fun song(n: Int, singer: String) = TestAudio.song(root, "Film (1990)/0$n - Song $n.m4a", 200 + 50 * n,
        mapOf("title" to "Song $n", "album" to "Film", "artist" to singer, "album_artist" to "Ilaiyaraaja", "date" to "1990", "track" to "$n"), seconds = 5)

    private fun singers(): List<String> = runBlocking {
        db.tx {
            query("""SELECT DISTINCT p.name FROM recording_credits c JOIN people p ON p.id = c.person_id WHERE c.role = 'singer' ORDER BY p.name""") { it.getString(1) }
        }
    }

    @Test
    fun `variants fold into the main spelling, and the overrides file corrects it both ways`() = runBlocking {
        (1..4).forEach { song(it, "S.P. Balasubrahmanyam") }
        song(5, "S.P.Balasubramaniam")
        song(6, "Hariharan")
        song(7, "Haricharan")
        scanner.scan(library)
        assertEquals(listOf("Haricharan", "Hariharan", "S.P. Balasubrahmanyam", "S.P.Balasubramaniam"), singers())

        assertEquals(1, merger.run().merged)
        assertEquals(listOf("Haricharan", "Hariharan", "S.P. Balasubrahmanyam"), singers())
        // A new song with the variant spelling credits the main spelling right away.
        song(8, "S.P.Balasubramaniam")
        scanner.scan(library)
        assertEquals(listOf("Haricharan", "Hariharan", "S.P. Balasubrahmanyam"), singers())

        // By hand: these two are the same after all; and S.P.B.'s variant is someone else.
        overrides.writeText("# corrections\nsame: Hariharan = Haricharan\ndifferent: S.P. Balasubrahmanyam != S.P.Balasubramaniam\n")
        val report = merger.run()
        assertEquals(1, report.forced)
        scanner.scan(library) // songs credited to S.P.B. are read again
        assertEquals(listOf("Hariharan", "S.P. Balasubrahmanyam", "S.P.Balasubramaniam"), singers())
        // And the rule doesn't merge them again.
        merger.run()
        assertEquals(listOf("Hariharan", "S.P. Balasubrahmanyam", "S.P.Balasubramaniam"), singers())
    }
}
