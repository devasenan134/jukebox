package io.github.devasenan134.jukebox.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebAppTest {
    @Test
    fun `the web app is served at its own addresses, and the API keeps its routes`() = testApplication {
        val web = Files.createTempDirectory("jukebox-web").toFile()
        File(web, "index.html").writeText("<html><title>Jukebox</title></html>")
        File(web, "assets").mkdirs()
        File(web, "assets/app.js").writeText("console.log('hi')")
        val db = File.createTempFile("jukebox-web", ".db").apply { delete(); deleteOnExit() }.path
        application { jukeboxServer(Config(0, db, webDir = web.path), music = null) }

        assertTrue(client.get("/").bodyAsText().contains("<title>Jukebox</title>"))
        assertTrue(client.get("/album/abc123").bodyAsText().contains("<title>Jukebox</title>"))
        assertEquals("console.log('hi')", client.get("/assets/app.js").bodyAsText())
        assertTrue(client.get("/health").bodyAsText().contains("ok"))
        // An API route that needs a session still answers as the API, not with the page.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/me").status)
        // /search is both a page and an API route: a browser opening it gets the page, an app gets the API.
        for (page in listOf("/search", "/friends", "/requests")) {
            assertTrue(client.get(page) { header(HttpHeaders.Accept, "text/html,application/xhtml+xml") }.bodyAsText().contains("<title>Jukebox</title>"), page)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/search?q=x") { header(HttpHeaders.Accept, "application/json") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/search?q=x") { header(HttpHeaders.Accept, "text/html"); header(HttpHeaders.Authorization, "Bearer nope") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/friends").status)
    }
}
