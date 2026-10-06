package io.github.devasenan134.jukebox.server.subsonic

import io.github.devasenan134.jukebox.server.Config
import io.github.devasenan134.jukebox.server.addAccount
import io.github.devasenan134.jukebox.server.jukeboxServer
import io.github.devasenan134.jukebox.server.library.TestAudio
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** How the Android app uses the server beyond plain Subsonic: playlist covers are kept by Jukebox. */
class AppCompatTest {
    private val root = Files.createTempDirectory("jukebox-music").toFile()
    private val data = Files.createTempDirectory("jukebox-data").toFile()

    init {
        listOf("alice", "bob").forEach { addAccount(File(data, "jukebox.db").path, it, password = "secret") }
    }

    private fun ApplicationTestBuilder.start() {
        TestAudio.airaa(root, 1, "Kaariga", 300)
        TestAudio.jpeg(File(root, "Airaa (2019)/cover.jpg"))
        application {
            jukeboxServer(
                Config(0, File(data, "jukebox.db").path, libraries = "tamil=${root.path}:film:tamil", fingerprints = false),
                music = null,
            )
        }
    }

    private suspend fun ApplicationTestBuilder.rest(call: String, params: String = "", user: String = "alice", password: String = "secret"): JsonObject {
        val text = client.get("/rest/$call?u=$user&s=abc&t=${SubsonicApi.md5(password + "abc")}&v=1.16.1&c=test&f=json&$params").bodyAsText()
        return Json.parseToJsonElement(text).jsonObject["subsonic-response"]!!.jsonObject
    }

    private suspend fun ApplicationTestBuilder.json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a playlist's own cover is kept by Jukebox`() = testApplication {
        start()
        repeat(100) {
            if (rest("getAlbumList2", "type=newest&size=10")["albumList2"]!!.jsonObject["album"]!!.jsonArray.isNotEmpty()) return@repeat
            delay(200)
        }
        val albumId = rest("getAlbumList2", "type=newest&size=1")["albumList2"]!!.jsonObject["album"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
        val song = rest("getAlbum", "id=$albumId")["album"]!!.jsonObject["song"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
        val playlist = rest("createPlaylist", "name=Road&songId=$song")["playlist"]!!.jsonObject
        val playlistId = playlist["id"]!!.jsonPrimitive.content
        val songCover = playlist["coverArt"]!!.jsonPrimitive.content

        val salt = "s1"
        val session = json(client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","salt":"$salt","token":"${SubsonicApi.md5("secret$salt")}"}""")
        }.bodyAsText())["sessionToken"]!!.jsonPrimitive.content
        val picture = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

        val set = client.put("/playlists/$playlistId/cover") { header(HttpHeaders.Authorization, "Bearer $session"); setBody(picture) }
        assertEquals(HttpStatusCode.NoContent, set.status)
        val withCover = rest("getPlaylist", "id=$playlistId")["playlist"]!!.jsonObject["coverArt"]!!.jsonPrimitive.content
        assertTrue(withCover.startsWith("pl-$playlistId"), withCover)
        val image = client.get("/rest/getCoverArt.view?u=alice&s=abc&t=${SubsonicApi.md5("secretabc")}&v=1.16.1&c=test&id=$withCover&size=64")
        assertEquals(HttpStatusCode.OK, image.status)

        // Only its owner can change it.
        val bobSession = json(client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"bob","salt":"$salt","token":"${SubsonicApi.md5("secret$salt")}"}""")
        }.bodyAsText())["sessionToken"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Forbidden, client.delete("/playlists/$playlistId/cover") { header(HttpHeaders.Authorization, "Bearer $bobSession") }.status)

        // Removed, it shows its first song's cover again.
        assertEquals(HttpStatusCode.NoContent, client.delete("/playlists/$playlistId/cover") { header(HttpHeaders.Authorization, "Bearer $session") }.status)
        val after = rest("getPlaylist", "id=$playlistId")["playlist"]!!.jsonObject["coverArt"]!!.jsonPrimitive.content
        assertEquals(songCover, after)
        assertNotEquals(withCover, after)
    }
}
