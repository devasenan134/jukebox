package io.github.devasenan134.jukebox.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Navidrome with one playlist, "pl1", made by alice (Navidrome id "nd-alice"). */
private class CoverNavidrome : Navidrome(Config(0, "", "http://unused", "bot", "secret")) {
    val covers = mutableMapOf<String, ByteArray?>()
    override suspend fun users() = listOf(NavidromeUser("nd-alice", "alice"), NavidromeUser("nd-bob", "bob"))
    override suspend fun checkLogin(username: String, salt: String, token: String) = token == "ok-$username"
    override suspend fun playlistOwner(playlistId: String) = if (playlistId == "pl1") "nd-alice" else null
    override suspend fun setPlaylistImage(playlistId: String, picture: Picture) { covers[playlistId] = picture.bytes }
    override suspend fun removePlaylistImage(playlistId: String) { covers[playlistId] = null }
}

class PicturesTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100) { it.toByte() }

    @Test
    fun `profile pictures and playlist covers`() = testApplication {
        val navidrome = CoverNavidrome()
        val dir = kotlin.io.path.createTempDirectory("jukebox-pictures").toFile().apply { deleteOnExit() }
        application { jukeboxServer(Config(0, File(dir, "social.db").path, "http://unused", "bot", "secret"), navidrome, music = null) }
        val client = createClient { install(ContentNegotiation) { json(eventJson) } }
        suspend fun login(name: String) = client.post("/auth/login") {
            contentType(ContentType.Application.Json); setBody(LoginRequest(name, "s", "ok-$name"))
        }.body<SessionResponse>()
        val alice = login("alice")
        val bob = login("bob")
        assertNull(alice.user.avatar)

        // Alice sets a picture; anyone logged in can see it, and her profile says when it was set.
        val me = client.put("/me/avatar") { bearerAuth(alice.sessionToken); setBody(jpeg) }.body<UserDto>()
        assertNotNull(me.avatar)
        val fetched = client.get("/users/${alice.user.id}/avatar?v=${me.avatar}") { bearerAuth(bob.sessionToken) }
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertContentEquals(jpeg, fetched.readRawBytes())
        assertEquals(me.avatar, client.get("/me") { bearerAuth(alice.sessionToken) }.body<UserDto>().avatar)
        // Not a picture: refused.
        assertEquals(HttpStatusCode.BadRequest, client.put("/me/avatar") { bearerAuth(alice.sessionToken); setBody("hello".toByteArray()) }.status)
        // Removing it.
        assertNull(client.delete("/me/avatar") { bearerAuth(alice.sessionToken) }.body<UserDto>().avatar)
        assertEquals(HttpStatusCode.NotFound, client.get("/users/${alice.user.id}/avatar") { bearerAuth(bob.sessionToken) }.status)

        // Only the playlist's owner can change its cover.
        assertEquals(HttpStatusCode.Forbidden, client.put("/playlists/pl1/cover") { bearerAuth(bob.sessionToken); setBody(jpeg) }.status)
        assertEquals(HttpStatusCode.NotFound, client.put("/playlists/nope/cover") { bearerAuth(alice.sessionToken); setBody(jpeg) }.status)
        assertEquals(HttpStatusCode.NoContent, client.put("/playlists/pl1/cover") { bearerAuth(alice.sessionToken); setBody(jpeg) }.status)
        assertContentEquals(jpeg, navidrome.covers["pl1"])
        assertEquals(HttpStatusCode.NoContent, client.delete("/playlists/pl1/cover") { bearerAuth(alice.sessionToken) }.status)
        assertNull(navidrome.covers["pl1"])

        // Group photos: any member can set one; others can't see it; DMs have none.
        client.post("/friends/requests") { bearerAuth(alice.sessionToken); contentType(ContentType.Application.Json); setBody(AddFriendRequest("bob")) }
        client.post("/friends/requests/${alice.user.id}/accept") { bearerAuth(bob.sessionToken) }
        val carol = login("carol")
        val group = client.post("/conversations/group") {
            bearerAuth(alice.sessionToken); contentType(ContentType.Application.Json); setBody(NewGroupRequest("Band", listOf(bob.user.id)))
        }.body<ConversationDto>()
        assertNull(group.picture)
        val changed = client.put("/conversations/${group.id}/picture") { bearerAuth(bob.sessionToken); setBody(jpeg) }.body<ConversationDto>()
        assertNotNull(changed.picture)
        assertContentEquals(jpeg, client.get("/conversations/${group.id}/picture") { bearerAuth(alice.sessionToken) }.readRawBytes())
        assertEquals(HttpStatusCode.NotFound, client.get("/conversations/${group.id}/picture") { bearerAuth(carol.sessionToken) }.status)
        assertEquals(HttpStatusCode.NotFound, client.put("/conversations/${group.id}/picture") { bearerAuth(carol.sessionToken); setBody(jpeg) }.status)
        val messages = client.get("/conversations/${group.id}/messages") { bearerAuth(alice.sessionToken) }.body<List<MessageDto>>()
        assertEquals("changed the group photo", messages.last().body)
        val dm = client.post("/conversations/dm") { bearerAuth(alice.sessionToken); contentType(ContentType.Application.Json); setBody(NewDmRequest(bob.user.id)) }.body<ConversationDto>()
        assertEquals(HttpStatusCode.BadRequest, client.put("/conversations/${dm.id}/picture") { bearerAuth(alice.sessionToken); setBody(jpeg) }.status)
        assertNull(client.delete("/conversations/${group.id}/picture") { bearerAuth(alice.sessionToken) }.body<ConversationDto>().picture)
    }
}
