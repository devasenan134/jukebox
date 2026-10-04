package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.query
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sound features for mixes, from Jukebox's own analyzer (recording ids) or the Isaipetti one (Navidrome ids). */
class JukeboxLibraryTest {
    private val root = Files.createTempDirectory("jukebox-music").toFile()
    private val data = Files.createTempDirectory("jukebox-data").toFile()
    private val db = Db(File(data, "jukebox.db").path)

    private fun sql(path: String, vararg statements: String, blobs: List<ByteArray> = emptyList()) = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
        statements.forEach { st -> c.prepareStatement(st).use { p -> if ("?" in st) blobs.forEachIndexed { i, b -> p.setBytes(i + 1, b) }; p.execute() } }
    }

    private val embedding = ByteBuffer.allocate(512 * 4).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(512) { putFloat(0.044f) } }.array()

    private fun features(ids: String, songId: String): String {
        val path = File(data, "features-$ids.db").path
        sql(path,
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE songs (id TEXT PRIMARY KEY, path TEXT, file_version TEXT, analyzed_at INTEGER, tempo REAL, energy REAL, brightness REAL, rhythm REAL, embedding BLOB, error TEXT)",
            "CREATE TABLE prompts (key TEXT PRIMARY KEY, embedding BLOB NOT NULL)",
            "INSERT INTO meta VALUES ('model', 'test')",
        )
        if (ids == "recording") sql(path, "INSERT INTO meta VALUES ('ids', 'recording')")
        sql(path, "INSERT INTO songs VALUES ('$songId', 'x', 'v', 1, 120, -12, 2000, 1.5, ?, NULL)", blobs = listOf(embedding))
        return path
    }

    @Test
    fun `features keyed by recording are read directly, and Navidrome-keyed ones through the file path`() = runBlocking {
        TestAudio.airaa(root, 1, "Kaariga", 300)
        TestAudio.airaa(root, 2, "Megathoodham", 500)
        Scanner(db, AudioTools(), File(data, "artwork"), threads = 2).scan(LibraryDef("tamil", root.path, "film"))
        val (recording, path) = db.tx { query("SELECT recording_id, path FROM files WHERE path LIKE '%Kaariga%'") { it.getString(1) to it.getString(2) }.first() }

        val own = JukeboxLibrary(db, navidromeDb = null, featuresDb = features("recording", recording)).snapshot()!!
        assertEquals(1, own.analyzed)

        val nd = File(data, "navidrome.db").path
        sql(nd, "CREATE TABLE media_file (id TEXT, path TEXT, missing BOOL)", "INSERT INTO media_file VALUES ('ndKaariga', '$path', 0)")
        val old = JukeboxLibrary(db, navidromeDb = nd, featuresDb = features("navidrome", "ndKaariga")).snapshot()!!
        assertEquals(1, old.analyzed)
    }
}
