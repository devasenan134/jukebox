package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.AudioTools
import io.github.devasenan134.jukebox.server.library.JukeboxLibrary
import io.github.devasenan134.jukebox.server.library.LibraryDef
import io.github.devasenan134.jukebox.server.library.Scanner
import io.github.devasenan134.jukebox.server.library.TestAudio
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fulfil script's view of a real scanned library: where an album's folder is, and a song filed into it joining it. */
class RequestPlaceTest {
    private val root = Files.createTempDirectory("jukebox-music").toFile()
    private val data = Files.createTempDirectory("jukebox-data").toFile()
    private val db = Db(File(data, "jukebox.db").path)
    private val scanner = Scanner(db, AudioTools(), File(data, "artwork"), threads = 2)
    private val tamil = LibraryDef("tamil", root.path, "film")

    private val catalog = object : Catalog {
        val items = listOf(CatalogItem("itunes-song-1", "song", "Paadal", "Airaa", 2019, "Someone & Sundaramurthy K.S."))
        override suspend fun search(query: String) = items
        override suspend fun lookup(id: String) = items.firstOrNull { it.id == id }
    }

    /** What the fulfil script writes: an mp3 with the album tags copied from a file already in the folder. */
    private fun mp3(path: String, tags: Map<String, String>) {
        val file = File(root, path)
        val cmd = listOf("ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i", "sine=frequency=880:duration=20", "-c:a", "libmp3lame", "-b:a", "320k", "-id3v2_version", "3") +
            tags.flatMap { (k, v) -> listOf("-metadata", "$k=$v") } + file.path
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }
    }

    @Test
    fun `a requested song filed into its album's folder joins that album`() = runBlocking {
        TestAudio.airaa(root, 1, "Kaariga", 300)
        TestAudio.airaa(root, 2, "Megathoodham", 500)
        scanner.scan(tamil)
        db.tx { update("INSERT INTO users (id, username, display_name, created_at) VALUES (1, 'admin', 'Admin', 0)") }
        val admin = UserDto(1, "admin", "Admin")
        val requests = MusicRequests(db, JukeboxLibrary(db), catalog, Push(db, NoPush), isAdmin = { true }, adminIds = { listOf(1L) })
        val request = requests.request(admin, "itunes-song-1")

        val before = requests.place(admin, request.id, 2019, "Sundaramurthy K.S.", "Paadal")
        assertEquals("tamil" to "Airaa (2019)", before.library to before.folder)
        assertEquals("Airaa (2019)/01 - Kaariga.m4a", before.sampleFile)
        assertEquals(null, before.songId)

        mp3("Airaa (2019)/03 - Paadal.mp3", mapOf("title" to "Paadal", "album" to "Airaa", "date" to "2019", "artist" to "Someone",
            "album_artist" to "Sundaramurthy K.S.", "composer" to "Sundaramurthy K.S.", "track" to "3/3"))
        scanner.scan(tamil)
        val after = requests.place(admin, request.id, 2019, "Sundaramurthy K.S.", "Paadal")
        assertEquals(before.albumId, after.albumId) // the same album, not a second "Airaa"
        val done = requests.complete(admin, request.id, CompleteMusicRequest(after.albumId, after.songId!!))
        assertEquals("done", done.status)
    }
}
