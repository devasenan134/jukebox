package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.queryOne
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BgmTitleTest {
    @Test
    fun `titles that name background music, never a sung theme song`() {
        listOf(
            "Title Music", "Theame Music - 1", "Kodai Mazhai Title Score", "End Credits", "Music Bit 3", "Mass BGM",
            "Escape (Theme) (Karaoke)", "Dance Music (Shyamala)", "Vidaamuyarchi Theme", "Hey Mama (Instrumental Version)",
        ).forEach { assertTrue(Names.isBgmTitle(it), it) }
        listOf(
            "Pancharaaksharam (Theme Song)", "Michael - Theme Song (Tamil)", "Kaariga", "Music Is My Life", "Scorpion King",
        ).forEach { assertFalse(Names.isBgmTitle(it), it) }
    }

    @Test
    fun `instrumental is not a person`() {
        assertEquals(emptyList(), Names.people("Instrumental"))
    }
}

class ScoreSplitterTest {
    private val tools = AudioTools()
    private val root = Files.createTempDirectory("jukebox-music").toFile()
    private val data = Files.createTempDirectory("jukebox-data").toFile()
    private val db = Db(File(data, "jukebox.db").path)
    private val scanner = Scanner(db, tools, File(data, "artwork"), threads = 2)
    private val library = LibraryDef("tamil", root.path, kind = "film", language = "tamil")
    private val splitter = ScoreSplitter(db, featuresDb = null, dataDir = data)

    private fun releaseKindOf(title: String): String = runBlocking {
        db.read { queryOne("SELECT rl.kind FROM tracks t JOIN releases rl ON rl.id = t.release_id WHERE t.title = ?", title) { it.getString(1) } }!!
    }

    @Test
    fun `background music in a song album moves to the score, and back when an override says so`() = runBlocking {
        TestAudio.airaa(root, 1, "Kaariga", 300)
        TestAudio.airaa(root, 2, "Airaa Title Music", 500)
        TestAudio.airaa(root, 3, "Megathoodham", 700)
        scanner.scan(library)
        assertEquals("soundtrack", releaseKindOf("Airaa Title Music"))

        val report = splitter.run()
        assertEquals(1, report.moved)
        assertEquals("score", releaseKindOf("Airaa Title Music"))
        assertEquals("soundtrack", releaseKindOf("Kaariga"))
        assertTrue(File(data, "score-split.csv").readText().contains("title,Airaa,Airaa Title Music"))

        // Running again changes nothing; a rescan of the changed file puts it back on the soundtrack, the splitter moves it again.
        assertEquals(0, splitter.run().moved)

        // By hand: one song to the score, the title-music one kept with the songs.
        File(data, "score-overrides.txt").writeText("# fixes\nscore: Airaa (2019)/03 - Megathoodham.m4a\nsong: Airaa (2019)/02 - Airaa Title Music.m4a\n")
        val again = splitter.run()
        assertEquals(1, again.moved)
        assertEquals(1, again.movedBack)
        assertEquals("score", releaseKindOf("Megathoodham"))
        assertEquals("soundtrack", releaseKindOf("Airaa Title Music"))
        assertEquals(1, db.read { query("SELECT count(*) FROM score_moves") { it.getInt(1) }.first() })
    }

    @Test
    fun `people's photos are found by name and taken away with the file`() = runBlocking {
        TestAudio.airaa(root, 1, "Kaariga", 300)
        scanner.scan(library)
        val photos = PeoplePhotos(db, File(data, "people-photos"), File(data, "artwork"))
        val photo = TestAudio.jpeg(File(data, "people-photos/Sundaramurthy K S.jpg"))
        assertEquals(1, photos.run())
        val person = db.read { queryOne("SELECT photo_id FROM people WHERE name = 'Sundaramurthy K.S.'") { it.getString(1) } }
        assertNotNull(person)
        assertEquals(0, photos.run())
        photo.delete()
        assertEquals(1, photos.run())
        assertNull(db.read { queryOne("SELECT photo_id FROM people WHERE name = 'Sundaramurthy K.S.'") { it.getString(1) } })
    }

    @Test
    fun `the sound model learns score from song`() {
        // Two clusters: score near one direction, songs near another.
        val random = java.util.Random(1)
        fun unit(v: FloatArray) = v.also { val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); for (i in v.indices) v[i] /= n }
        fun near(axis: Int) = unit(FloatArray(16) { (if (it == axis) 1f else 0f) + 0.3f * random.nextGaussian().toFloat() })
        val x = List(100) { near(0) } + List(100) { near(1) }
        val y = List(100) { 1 } + List(100) { 0 }
        val (w, b) = ScoreSplitter.fit(x, y)
        fun p(v: FloatArray) = 1 / (1 + kotlin.math.exp(-(10 * v.indices.sumOf { (v[it] * w[it]).toDouble() } + b)))
        assertTrue(p(near(0)) > 0.9)
        assertTrue(p(near(1)) < 0.1)
    }
}
