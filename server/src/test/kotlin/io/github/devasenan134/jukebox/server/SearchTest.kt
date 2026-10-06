package io.github.devasenan134.jukebox.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTest {
    private fun score(query: String, name: String) = Fuzzy.match(Fuzzy.words(query), Fuzzy.words(name))

    @Test
    fun `spellings of the same Tamil word match`() {
        for ((query, name) in listOf(
            "kanavae" to "Kanave", "putu velai malai" to "Pudhu Vellai Mazhai", "thanush" to "Dhanush",
            "vairamutu" to "Vairamuthu", "ilayaraja" to "Ilaiyaraaja", "nenjukule" to "Nenjukkulle",
        )) assertTrue(score(query, name) > 0, "$query should find $name")
    }

    @Test
    fun `half typed words match`() {
        assertTrue(score("vairam", "Vairamuthu") > 0)
        assertTrue(score("munbe v", "Munbe Vaa") > 0)
    }

    @Test
    fun `different words don't`() {
        assertEquals(0.0, score("kamal", "Kadal"))
        assertEquals(0.0, score("roja", "Kanave Kanave"))
    }

    @Test
    fun `exact beats close, and a full match beats a longer name`() {
        assertTrue(score("roja", "Roja") > score("roja", "Roja Poonthottam"))
        assertTrue(score("kanave", "Kanave") > score("kanave", "Kanavu"))
    }

    @Test
    fun `the same name spelled differently has one key`() {
        assertEquals(Fuzzy.key("Vaali"), Fuzzy.key("Vaalee"))
        assertEquals(Fuzzy.key("Kamal Haasan"), Fuzzy.key("Kamal Hassan"))
    }
}

/** A tiny library: two movies, one lyricist, and a cast file with an actor who also sings. */
private class SearchMusic : MusicSource {
    private val vaali = Person("p-vaali", "Vaali")
    private val kamal = Person("p-kamal", "Kamal Haasan")
    private val songs = listOf(
        LibrarySong("s1", "Kanave Kanave", "David", "m1", "Singer", listOf(Person("p-singer", "Singer")), Person("p-comp", "Anirudh"), 2013, 240, "Tamil", 0, false, listOf(vaali)),
        LibrarySong("s2", "Unnai Kaanadhu", "Vishwaroopam", "m2", "Kamal Haasan", listOf(kamal), Person("p-sel", "Shankar-Ehsaan-Loy"), 2013, 300, "Tamil", 0, false),
    )
    override suspend fun snapshot() = LibrarySnapshot(songs, arrayOfNulls(songs.size), emptyMap(), emptyMap(), FloatArray(2), FloatArray(2), "v1")
    override suspend fun history(userId: Long, snapshot: LibrarySnapshot) = History.EMPTY
    override suspend fun popularity(snapshot: LibrarySnapshot) = emptyMap<Int, Int>()
}

class SearchApiTest {
    @Test
    fun `search by lyricist, by actor and with loose spelling`() = testApplication {
        val dir = File.createTempFile("jukebox-search", "").apply { delete(); mkdirs(); deleteOnExit() }
        File(dir, "movie-cast.jsonl").writeText(
            """{"album": "Vishwaroopam", "year": 2013, "starring": ["Kamal Haasan", "Pooja Kumar"]}""" + "\n",
        )
        val db = File(dir, "social.db").path
        addAccount(db, "alice")
        application { jukeboxServer(Config(0, db), music = SearchMusic()) }
        val client = createClient { install(ContentNegotiation) { json(eventJson) } }
        val token = client.post("/auth/login") {
            contentType(ContentType.Application.Json); setBody(loginRequest("alice"))
        }.body<SessionResponse>().sessionToken

        val byLyricist = client.get("/search?q=vaalee") { bearerAuth(token) }.body<SearchResults>()
        assertEquals("p-vaali", byLyricist.people.first().id)
        assertEquals("Lyrics by Vaali", byLyricist.songs.single().reason)

        val byActor = client.get("/search?q=pooja") { bearerAuth(token) }.body<SearchResults>()
        assertEquals("Starring Pooja Kumar", byActor.movies.single().reason)
        val pooja = byActor.people.single()
        assertEquals(listOf("actor"), pooja.roles)
        val page = client.get("/search/people/${pooja.id}") { bearerAuth(token) }.body<PersonPage>()
        assertEquals(listOf("m2"), page.movies.map { it.id })

        // Kamal Haasan sings here and acts in the movie: one person with both roles.
        val kamal = client.get("/search?q=kamal+hasan") { bearerAuth(token) }.body<SearchResults>().people.single()
        assertEquals(setOf("singer", "actor"), kamal.roles.toSet())

        assertEquals("s1", client.get("/search?q=kanavae") { bearerAuth(token) }.body<SearchResults>().songs.first().song.id)
    }
}
