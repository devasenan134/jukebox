package io.github.devasenan134.jukebox.server.subsonic

import io.github.devasenan134.jukebox.server.Config
import io.github.devasenan134.jukebox.server.Navidrome
import io.github.devasenan134.jukebox.server.jukeboxServer
import io.github.devasenan134.jukebox.server.library.TestAudio
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class SubsonicFakeNavidrome : Navidrome(Config(0, "", "http://unused", "", "")) {
    override suspend fun users() = null
    override suspend fun checkLogin(username: String, salt: String, token: String) =
        username == "alice" && token == SubsonicApi.md5("secret$salt")
}

class SubsonicTest {
    private val root = Files.createTempDirectory("jukebox-music").toFile()
    private val data = Files.createTempDirectory("jukebox-data").toFile()

    private fun auth(user: String = "alice", password: String = "secret") = "u=$user&s=abc&t=${SubsonicApi.md5(password + "abc")}&v=1.16.1&c=test&f=json"

    private suspend fun ApplicationTestBuilder.rest(call: String, params: String = "", user: String = "alice", password: String = "secret"): JsonObject {
        val text = client.get("/rest/$call?${auth(user, password)}&$params").bodyAsText()
        return Json.parseToJsonElement(text).jsonObject["subsonic-response"]!!.jsonObject
    }

    private suspend fun ApplicationTestBuilder.raw(call: String, params: String, range: String? = null): HttpResponse =
        client.get("/rest/$call.view?${auth()}&$params") { range?.let { header(HttpHeaders.Range, it) } }

    @Test
    fun `the app can browse, search, read lyrics and play from Jukebox's own library`() = testApplication {
        TestAudio.airaa(root, 1, "Kaariga", 300, singers = "Sathya Prakash, Chinmayi")
        TestAudio.airaa(root, 2, "Megathoodham", 500)
        TestAudio.airaa(root, 1, "She Hates You", 700, score = true)
        TestAudio.jpeg(File(root, "Airaa (2019)/cover.jpg"))
        File(root, "Airaa (2019)/01 - Kaariga.lrc").writeText("[ar:Sathya Prakash]\n[00:01.50] Kaariga kaariga\n[00:05.00][00:09.25] La la")
        application {
            jukeboxServer(
                Config(0, File(data, "jukebox.db").path, "http://unused", "", "", libraries = "tamil=${root.path}:film:tamil", fingerprints = false),
                navidrome = SubsonicFakeNavidrome(), music = null,
            )
        }

        // Sign-in goes through Navidrome; a wrong password gets error 40.
        assertEquals("ok", rest("ping")["status"]!!.jsonPrimitive.content)
        val wrong = rest("ping", password = "nope")
        assertEquals("failed", wrong["status"]!!.jsonPrimitive.content)
        assertEquals(40, wrong["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        // The library is scanned in the background when the server starts.
        var albums = emptyList<JsonObject>()
        repeat(100) {
            albums = rest("getAlbumList2", "type=alphabeticalByName&size=10")["albumList2"]!!.jsonObject["album"]!!.jsonArray.map { it.jsonObject }
            if (albums.size == 2) return@repeat
            delay(200)
        }
        assertEquals(listOf("Airaa", "Airaa (Original Background Score)"), albums.map { it["name"]!!.jsonPrimitive.content })
        val airaa = albums.first()
        assertEquals("Sundaramurthy K.S.", airaa["artist"]!!.jsonPrimitive.content)
        assertEquals(2019, airaa["year"]!!.jsonPrimitive.int)

        val album = rest("getAlbum", "id=${airaa["id"]!!.jsonPrimitive.content}")["album"]!!.jsonObject
        val songs = album["song"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Kaariga", "Megathoodham"), songs.map { it["title"]!!.jsonPrimitive.content })
        val kaariga = songs.first()
        assertEquals("Sathya Prakash, Chinmayi", kaariga["artist"]!!.jsonPrimitive.content)
        assertEquals(2, kaariga["artists"]!!.jsonArray.size)
        assertEquals(20, kaariga["duration"]!!.jsonPrimitive.int)
        assertEquals(1, kaariga["track"]!!.jsonPrimitive.int)
        assertEquals("audio/mp4", kaariga["contentType"]!!.jsonPrimitive.content)
        val id = kaariga["id"]!!.jsonPrimitive.content

        val song = rest("getSong", "id=$id")["song"]!!.jsonObject
        assertEquals("Sundaramurthy K.S.", song["displayComposer"]!!.jsonPrimitive.content)
        assertEquals("Tamil", song["genre"]!!.jsonPrimitive.content)
        assertTrue(song["contributors"]!!.jsonArray.any { it.jsonObject["role"]!!.jsonPrimitive.content == "composer" })

        // Composers are album artists (the app's composer pages); singers are artists.
        val people = rest("getArtists")["artists"]!!.jsonObject["index"]!!.jsonArray.flatMap { it.jsonObject["artist"]!!.jsonArray }.map { it.jsonObject }
        val composer = people.first { it["name"]!!.jsonPrimitive.content == "Sundaramurthy K.S." }
        assertTrue("albumartist" in composer["roles"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, composer["albumCount"]!!.jsonPrimitive.int)
        val artist = rest("getArtist", "id=${composer["id"]!!.jsonPrimitive.content}")["artist"]!!.jsonObject
        assertEquals(2, artist["album"]!!.jsonArray.size)

        // Search forgives spelling, and a singer's name finds their songs.
        val byTitle = rest("search3", "query=kariga")["searchResult3"]!!.jsonObject
        assertEquals(id, byTitle["song"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content)
        val bySinger = rest("search3", "query=chinmayi&songCount=50")["searchResult3"]!!.jsonObject
        assertEquals(listOf(id), bySinger["song"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })

        // Synced lyrics, with every timestamp of a line.
        val lyrics = rest("getLyricsBySongId", "id=$id")["lyricsList"]!!.jsonObject["structuredLyrics"]!!.jsonArray.first().jsonObject
        assertEquals(true, lyrics["synced"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf(1500L, 5000L, 9250L), lyrics["line"]!!.jsonArray.map { it.jsonObject["start"]!!.jsonPrimitive.long })

        // Audio with byte ranges (seeking), and covers at the size asked for.
        val full = raw("stream", "id=$id")
        assertEquals(HttpStatusCode.OK, full.status)
        assertEquals("audio/mp4", full.headers[HttpHeaders.ContentType])
        val part = raw("stream", "id=$id", range = "bytes=0-99")
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals(100, part.readRawBytes().size)
        val cover = raw("getCoverArt", "id=${airaa["coverArt"]!!.jsonPrimitive.content}&size=64")
        assertEquals("image/jpeg", cover.headers[HttpHeaders.ContentType])
        assertTrue(cover.readRawBytes().size in 100..20_000)

        // Likes come with milestone 2.
        assertEquals("failed", rest("star", "id=$id")["status"]!!.jsonPrimitive.content)
        assertEquals("ok", rest("getStarred2")["status"]!!.jsonPrimitive.content)
    }
}
