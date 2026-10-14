package io.github.devasenan134.jukebox.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fulfil script's calls and the admin's check, over HTTP, the way the script and the apps make them. */
class RequestReviewApiTest {
    private class OneSongLibrary : MusicSource {
        var songs = listOf(LibrarySong("s1", "Kanave Kanave", "David", "m1", "", emptyList(), null, 2013, 240, "Tamil", 0, false))
        override suspend fun snapshot() = LibrarySnapshot(songs, arrayOfNulls(songs.size), emptyMap(), emptyMap(), FloatArray(songs.size), FloatArray(songs.size), "v${songs.size}")
        override suspend fun history(userId: Long, snapshot: LibrarySnapshot) = History.EMPTY
        override suspend fun popularity(snapshot: LibrarySnapshot) = emptyMap<Int, Int>()
    }

    private val catalog = object : Catalog {
        val items = listOf(CatalogItem("itunes-movie-21", "movie", "Anbe Aaruyire", "Anbe Aaruyire", 2013, "A.R. Rahman & Vaali", trackCount = 6))
        override suspend fun search(query: String) = items
        override suspend fun lookup(id: String) = items.firstOrNull { it.id == id }
    }

    @Test
    fun `hand in, listen, approve and close`() = testApplication {
        val dir = Files.createTempDirectory("jukebox-review-api").toFile()
        val dbPath = File(dir, "jukebox.db").path
        val downloads = File(dir, "requests")
        addAccount(dbPath, "admin", admin = true)
        addAccount(dbPath, "alice")
        val music = OneSongLibrary()
        application { jukeboxServer(Config(port = 0, dbPath = dbPath, requestsDir = downloads.path), music = music, catalog = catalog) }
        val client = createClient { install(ContentNegotiation) { json(eventJson) } }
        suspend fun token(name: String) = client.post("/auth/login") { contentType(ContentType.Application.Json); setBody(loginRequest(name)) }.body<SessionResponse>().sessionToken
        val admin = token("admin")
        val alice = token("alice")

        val request = client.post("/requests") { bearerAuth(alice); contentType(ContentType.Application.Json); setBody(NewMusicRequest("itunes-movie-21")) }.body<MusicRequestDto>()
        File(downloads, "${request.id}/01 - Aararai Kodi.mp3").apply { parentFile.mkdirs(); writeBytes(ByteArray(2048) { 7 }) }

        // What fulfil.py sends (extra fields it may add later are ignored).
        val handIn = """{"album":"Anbe Aaruyire","year":2005,"composer":"A.R.Rahman","destination":"new","destinationFolder":"Anbe Aaruyire (2005)",
            "tracks":[{"n":1,"file":"01 - Aararai Kodi.mp3","title":"Aararai Kodi","durationMs":321000,"bitrate":320,"lyrics":"synced"}],"someday":true}"""
        assertEquals(HttpStatusCode.Forbidden, client.post("/admin/requests/${request.id}/review") { bearerAuth(alice); contentType(ContentType.Application.Json); setBody(handIn) }.status)
        assertEquals("review", client.post("/admin/requests/${request.id}/review") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody(handIn) }.body<MusicRequestDto>().stage)
        assertEquals(HttpStatusCode.NoContent, client.post("/admin/requests/reviews-ready") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody(ReviewsReady(listOf(request.id))) }.status)

        // Listening: the web's <audio> signs in with ?token, which works only on /stream addresses.
        assertEquals(2048, client.get("/admin/requests/${request.id}/review/1/stream?token=$admin").body<ByteArray>().size)
        assertEquals(HttpStatusCode.Forbidden, client.get("/admin/requests/${request.id}/review/1/stream?token=$alice").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/requests?token=$admin").status)

        assertEquals("approved", client.post("/admin/requests/${request.id}/approve") { bearerAuth(admin) }.body<MusicRequestDto>().stage)

        // The script moved it and the scan found it; the apps' "Added" (no body) can't, the script's (with the album) can.
        music.songs = music.songs + LibrarySong("s2", "Aararai Kodi", "Anbe Aaruyire", "m2005", "", emptyList(), null, 2005, 321, "Tamil", 0, false)
        assertEquals(HttpStatusCode.Conflict, client.post("/admin/requests/${request.id}/done") { bearerAuth(admin) }.status)
        val done = client.post("/admin/requests/${request.id}/done") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody("""{"albumId":"m2005"}""") }.body<MusicRequestDto>()
        assertEquals(Triple("done", "m2005", null), Triple(done.status, done.albumId, done.stage))
    }
}
