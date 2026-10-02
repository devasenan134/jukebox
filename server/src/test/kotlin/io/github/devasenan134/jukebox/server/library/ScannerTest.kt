package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.queryOne
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamesTest {
    @Test
    fun `album tags lose the parts that only name the release`() {
        assertEquals("Airaa" to true, Names.album("Airaa (Original Background Score)"))
        assertEquals("Airaa" to false, Names.album("Airaa (Original Motion Picture Soundtrack)"))
        assertEquals("96" to false, Names.album("96 - Single"))
        assertEquals("Theme of Airaa" to false, Names.album("Theme of Airaa"))
    }

    @Test
    fun `versions of a song are found in brackets and dash tails only`() {
        assertEquals("Kanave" to "karaoke", Names.version("Kanave (Karaoke Version)"))
        assertEquals("Merku Thodarchi Mala" to "female", Names.version("Merku Thodarchi Mala - Female"))
        assertEquals("Tamil Tamil" to "remix", Names.version("Tamil Tamil (Remix / From \"Pokkiri\")"))
        assertEquals("Aeriyil" to "original", Names.version("Aeriyil (Original Motion Picture Soundtrack)"))
        assertEquals("Theme of Airaa" to "original", Names.version("Theme of Airaa"))
        assertEquals("Male Version Blues" to "original", Names.version("Male Version Blues"))
    }

    @Test
    fun `people are split out of a tag once each`() {
        assertEquals(listOf("Hariharan", "Sujatha", "A. R. Rahman"), Names.people("Hariharan, Sujatha & A. R. Rahman"))
        assertEquals(listOf("M.S. Viswanathan", "T.K. Ramamoorthy"), Names.people("M.S. Viswanathan and T.K. Ramamoorthy, M. S. Viswanathan"))
        assertEquals(emptyList(), Names.people("Various Artists"))
        assertEquals(Names.personKey("M.S. Viswanathan"), Names.personKey("M. S. Viswanathan"))
    }

    @Test
    fun `tamil script is told apart from english letters`() {
        assertEquals("ta", Names.script("காதல் காதல்\nகாதலில் நெஞ்சம்"))
        assertEquals("en", Names.script("[00:01.00] Kaadhal kaadhal"))
    }
}

class ScannerTest {
    private val tools = AudioTools()
    private val root = Files.createTempDirectory("jukebox-music").toFile()
    private val data = Files.createTempDirectory("jukebox-data").toFile()
    private val db = Db(File(data, "jukebox.db").path)
    private val saavnIds = mutableMapOf<String, String>()
    private val scanner = Scanner(db, tools, File(data, "artwork"), externalIds = { _, path -> saavnIds[path] }, threads = 2)
    private val fingerprints = Fingerprints(db, tools) { mapOf(1L to root.path) }
    private val library = LibraryDef("tamil", root.path, kind = "film", language = "tamil")

    private fun song(path: String, hz: Int, tags: Map<String, String>, bitrate: String = "128k") = TestAudio.song(root, path, hz, tags, bitrate)
    private fun airaa(n: Int, title: String, hz: Int, score: Boolean = false, singers: String = "Sathya Prakash") =
        TestAudio.airaa(root, n, title, hz, score, singers)

    private fun recordingOf(path: String): String = runBlocking {
        db.tx { queryOne("SELECT recording_id FROM files WHERE path = ?", path) { it.getString(1) } }!!
    }

    private fun count(sql: String): Int = runBlocking { db.tx { query(sql) { it.getInt(1) }.first() } }

    @Test
    fun `a scan builds albums, releases, tracks, people, lyrics and covers`() = runBlocking {
        airaa(1, "Kaariga", 300, singers = "Sathya Prakash, Chinmayi")
        airaa(2, "Megathoodham", 500)
        airaa(1, "She Hates You", 700, score = true)
        File(root, "Airaa (2019)/cover.jpg").writeBytes(jpeg())
        File(root, "Airaa (2019)/01 - Kaariga.lrc").writeText("[00:01.00] Kaariga kaariga\n[00:05.00] la la")

        val report = scanner.scan(library)
        assertEquals(3, report.added)
        assertEquals(1, count("SELECT count(*) FROM albums"))
        assertEquals("film", db.tx { queryOne("SELECT kind FROM albums") { it.getString(1) } })
        val releases = db.tx { query("SELECT kind, title FROM releases ORDER BY kind") { it.getString(1) to it.getString(2) } }
        assertEquals(listOf("score" to "Background Score", "soundtrack" to "Soundtrack"), releases)
        assertEquals(3, count("SELECT count(*) FROM recordings"))
        assertEquals(3, count("SELECT count(*) FROM tracks"))
        val kaariga = recordingOf("Airaa (2019)/01 - Kaariga.m4a")
        val singers = db.tx {
            query("""SELECT p.name FROM recording_credits c JOIN people p ON p.id = c.person_id
                     WHERE c.recording_id = ? AND c.role = 'singer' ORDER BY c.position""", kaariga) { it.getString(1) }
        }
        assertEquals(listOf("Sathya Prakash", "Chinmayi"), singers)
        assertEquals(1, count("SELECT count(*) FROM people WHERE name = 'Sundaramurthy K.S.'"))
        assertEquals(1, count("SELECT count(*) FROM lyrics WHERE synced = 1"))
        assertNotNull(db.tx { queryOne("SELECT cover_id FROM albums") { it.getString(1) } })

        // Nothing changed: nothing is read again.
        val again = scanner.scan(library)
        assertEquals(3, again.unchanged)
        assertEquals(0, again.added + again.updated + again.moved)

        // Lyrics added later are picked up without the audio changing.
        File(root, "Airaa (2019)/02 - Megathoodham.txt").writeText("Megathoodham\nvaa vaa")
        assertEquals(1, scanner.scan(library).lyricsUpdated)
        assertEquals(2, count("SELECT count(*) FROM lyrics"))
    }

    @Test
    fun `moving, renaming and retagging keep the recording id`() = runBlocking {
        val file = airaa(1, "Kaariga", 300)
        scanner.scan(library)
        val id = recordingOf("Airaa (2019)/01 - Kaariga.m4a")

        // The whole library moves to another folder name (like MusicLibrary_Fresh → MusicLibrary/tamil).
        val moved = File(root, "Airaa (2019) [new]/01 - Kaariga.m4a").apply { parentFile.mkdirs() }
        file.renameTo(moved)
        val report = scanner.scan(library)
        assertEquals(1, report.moved)
        assertEquals(0, report.added)
        assertEquals(id, recordingOf("Airaa (2019) [new]/01 - Kaariga.m4a"))

        // Retagged in place: same recording, new title.
        song("Airaa (2019) [new]/01 - Kaariga.m4a", 300, mapOf("title" to "Kaariga (Karaoke)", "album" to "Airaa", "date" to "2019"))
        scanner.scan(library)
        assertEquals(id, recordingOf("Airaa (2019) [new]/01 - Kaariga.m4a"))
        assertEquals("karaoke", db.tx { queryOne("SELECT version FROM recordings WHERE id = ?", id) { it.getString(1) } })
    }

    @Test
    fun `a file that goes missing keeps its recording and comes back to it`() = runBlocking {
        val file = airaa(2, "Megathoodham", 500)
        val saved = File(data, "saved.m4a")
        scanner.scan(library)
        val id = recordingOf("Airaa (2019)/02 - Megathoodham.m4a")
        file.copyTo(saved)
        file.delete()
        assertEquals(1, scanner.scan(library).missing)
        assertNotNull(db.tx { queryOne("SELECT missing_since FROM files") { it.getLong(1) } })
        assertEquals(1, count("SELECT count(*) FROM recordings"))
        saved.copyTo(file)
        file.setLastModified(saved.lastModified())
        scanner.scan(library)
        assertEquals(id, recordingOf("Airaa (2019)/02 - Megathoodham.m4a"))
        assertNull(db.tx { queryOne("SELECT missing_since FROM files") { it.getObject(1) } })
    }

    @Test
    fun `a JioSaavn id from the import finds the recording a new file belongs to`() = runBlocking {
        saavnIds["Airaa (2019)/01 - Kaariga.m4a"] = "abc123"
        airaa(1, "Kaariga", 300)
        scanner.scan(library)
        val id = recordingOf("Airaa (2019)/01 - Kaariga.m4a")
        // A better copy of the same song, from the same JioSaavn song, in another folder.
        saavnIds["Better/Kaariga.m4a"] = "abc123"
        song("Better/Kaariga.m4a", 300, mapOf("title" to "Kaariga", "album" to "Airaa", "date" to "2019"), bitrate = "256k")
        scanner.scan(library)
        assertEquals(id, recordingOf("Better/Kaariga.m4a"))
    }

    @Test
    fun `the same performance on a single and the soundtrack becomes one recording`() = runBlocking {
        airaa(1, "Kaariga", 300)
        song("Kaariga (From Airaa)/Kaariga.m4a", 300, mapOf("title" to "Kaariga (From \"Airaa\")", "album" to "Kaariga (From \"Airaa\")",
            "artist" to "Sathya Prakash", "date" to "2019"), bitrate = "96k")
        airaa(2, "Megathoodham", 500)
        scanner.scan(library)
        assertEquals(3, count("SELECT count(*) FROM recordings"))
        val single = recordingOf("Kaariga (From Airaa)/Kaariga.m4a")
        val soundtrack = recordingOf("Airaa (2019)/01 - Kaariga.m4a")
        assertNotEquals(single, soundtrack)

        val report = fingerprints.run()
        assertEquals(1, report.merged)
        assertEquals(0, report.left)
        assertEquals(recordingOf("Airaa (2019)/01 - Kaariga.m4a"), recordingOf("Kaariga (From Airaa)/Kaariga.m4a"))
        assertEquals(2, count("SELECT count(*) FROM recordings WHERE merged_into IS NULL"))
        // Both appearances stay: the single is still its own track.
        assertEquals(3, count("SELECT count(*) FROM tracks"))
        // A different song with a different sound is never merged.
        assertNotEquals(recordingOf("Airaa (2019)/01 - Kaariga.m4a"), recordingOf("Airaa (2019)/02 - Megathoodham.m4a"))
    }

    @Test
    fun `fingerprints tell the same audio from different audio`() {
        val a = tools.fingerprint(airaa(1, "Kaariga", 300))!!
        val b = tools.fingerprint(song("x/a.m4a", 300, emptyMap(), bitrate = "64k"))!!
        val c = tools.fingerprint(song("x/c.m4a", 650, emptyMap()))!!
        assertTrue(AudioTools.similarity(a, b) >= Fingerprints.SAME, "same audio: ${AudioTools.similarity(a, b)}")
        assertTrue(AudioTools.similarity(a, c) < Fingerprints.SAME, "other audio: ${AudioTools.similarity(a, c)}")
    }

    private fun jpeg(): ByteArray = TestAudio.jpeg(File(data, "cover.jpg")).readBytes()
}
