package io.github.devasenan134.jukebox.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
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

class SoundProfilesTest {
    @Test
    fun `equalizer profiles follow you, and the newest change wins`() = testApplication {
        val db = File.createTempFile("jukebox-sound", ".db").apply { delete(); deleteOnExit() }.path
        addAccount(db, "alice")
        addAccount(db, "bob")
        application { jukeboxServer(Config(0, db)) }
        val client = createClient { install(ContentNegotiation) { json(eventJson) } }
        suspend fun login(name: String) = client.post("/auth/login") {
            contentType(ContentType.Application.Json); setBody(loginRequest(name))
        }.body<SessionResponse>().sessionToken
        val phone = login("alice")
        val mac = login("alice")
        val bob = login("bob")

        suspend fun sync(token: String, profiles: List<SoundProfileDto>) = client.put("/me/sound-profiles") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(profiles)
        }
        suspend fun synced(token: String, profiles: List<SoundProfileDto>) = sync(token, profiles).body<List<SoundProfileDto>>()
        suspend fun list(token: String) = client.get("/me/sound-profiles") { bearerAuth(token) }.body<List<SoundProfileDto>>()

        val bass = List(10) { if (it < 3) 4f else 0f }
        val iem = SoundProfileDto("custom:iem", "KZ ZS10", bass, updatedAt = 100)

        // Saved on the phone, there on the Mac (and not Bob's).
        assertEquals(listOf("custom:iem"), synced(phone, listOf(iem)).map { it.id })
        assertEquals(listOf(iem), list(mac))
        assertTrue(list(bob).isEmpty())

        // The Mac adds one of its own and gets the phone's back with it.
        val desk = SoundProfileDto("custom:desk", "Desk speakers", List(10) { 0f }, updatedAt = 150)
        assertEquals(setOf("custom:iem", "custom:desk"), synced(mac, listOf(iem, desk)).map { it.id }.toSet())

        // The phone changes the IEM profile; the Mac still has the old one and syncs: the newer change stays.
        synced(phone, listOf(iem.copy(name = "KZ ZS10 Pro", updatedAt = 200)))
        val afterMac = synced(mac, listOf(iem, desk))
        assertEquals("KZ ZS10 Pro", afterMac.first { it.id == "custom:iem" }.name)

        // Deleted on the Mac: the deletion is kept, so the phone's older copy doesn't bring it back.
        synced(mac, listOf(desk.copy(deleted = true, updatedAt = 300)))
        val afterPhone = synced(phone, listOf(desk))
        assertTrue(afterPhone.first { it.id == "custom:desk" }.deleted)

        // Only real profiles: ten bands within ±12 dB, an id of your own, a name.
        assertEquals(HttpStatusCode.BadRequest, sync(phone, listOf(iem.copy(gains = listOf(1f)))).status)
        assertEquals(HttpStatusCode.BadRequest, sync(phone, listOf(iem.copy(gains = List(10) { 40f }))).status)
        assertEquals(HttpStatusCode.BadRequest, sync(phone, listOf(iem.copy(id = "builtin:flat"))).status)
        assertEquals(HttpStatusCode.BadRequest, sync(phone, listOf(iem.copy(name = " "))).status)
    }
}
