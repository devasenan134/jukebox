package io.github.devasenan134.jukebox.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentsTest {
    @Test
    fun `what one device plays, the others see, and Resume follows you`() = testApplication {
        val db = File.createTempFile("jukebox-recents", ".db").apply { delete(); deleteOnExit() }.path
        addAccount(db, "alice")
        addAccount(db, "bob")
        application { jukeboxServer(Config(0, db)) }
        val client = createClient { install(ContentNegotiation) { json(eventJson) } }
        suspend fun login(name: String) = client.post("/auth/login") {
            contentType(ContentType.Application.Json); setBody(loginRequest(name))
        }.body<SessionResponse>().sessionToken
        val phone = login("alice")
        val laptop = login("alice")
        val bob = login("bob")

        suspend fun played(token: String, item: RecentItemDto) = client.post("/me/recents") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(item)
        }.status
        suspend fun recents(token: String) = client.get("/me/recents") { bearerAuth(token) }.body<List<RecentItemDto>>()

        // The phone plays a playlist, then a song; the laptop sees both, newest first, and Bob sees nothing.
        assertEquals(HttpStatusCode.NoContent, played(phone, RecentItemDto("Playlist", "p1", "Road trip", "Playlist", "pl-p1")))
        Thread.sleep(5)
        played(phone, RecentItemDto("Song", "s1", "Mounamey", "SPB", "al-a1", SongRef("s1", "Mounamey", "SPB", duration = 300)))
        assertEquals(listOf("s1", "p1"), recents(laptop).map { it.id })
        assertEquals("Mounamey", recents(laptop).first().song?.title)
        assertTrue(recents(bob).isEmpty())

        // Playing it again moves it to the top rather than adding it twice.
        Thread.sleep(5)
        played(laptop, RecentItemDto("Playlist", "p1", "Road trip", "Playlist", "pl-p1"))
        assertEquals(listOf("p1", "s1"), recents(phone).map { it.id })

        // Only the newest 30 songs are kept (other things are kept apart).
        for (i in 0 until 35) played(phone, RecentItemDto("Song", "t$i", "Song $i"))
        val all = recents(phone)
        assertEquals(30, all.count { it.kind == "Song" })
        assertTrue(all.any { it.id == "p1" })
        assertEquals(HttpStatusCode.BadRequest, played(phone, RecentItemDto("Planet", "x", "Not a kind")))

        // Forget one; clear everything.
        client.delete("/me/recents/Playlist/p1") { bearerAuth(laptop) }
        assertTrue(recents(phone).none { it.id == "p1" })

        // Resume: saved on the phone, there on the laptop, not Bob's.
        val point = ResumePointDto("playlist:p1", listOf("s3", "s1", "s2"), "s1")
        assertEquals(HttpStatusCode.NoContent, client.put("/me/resume") { bearerAuth(phone); contentType(ContentType.Application.Json); setBody(point) }.status)
        val fromLaptop = client.get("/me/resume?source=playlist:p1") { bearerAuth(laptop) }.body<ResumePointDto>()
        assertEquals(listOf("s3", "s1", "s2") to "s1", fromLaptop.songIds to fromLaptop.currentId)
        assertEquals(HttpStatusCode.NotFound, client.get("/me/resume?source=playlist:p1") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/me/resume?source=playlist:other") { bearerAuth(laptop) }.status)

        client.delete("/me/recents") { bearerAuth(phone) }
        assertTrue(recents(laptop).isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("/me/resume?source=playlist:p1") { bearerAuth(laptop) }.status)
    }
}
