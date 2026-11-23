package io.github.devasenan134.jukebox.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JioSaavn's replies (as its search and lookups give them, trimmed) as catalog items. */
class SaavnCatalogTest {
    private fun parse(text: String, languages: Set<String> = setOf("tamil")) = SaavnCatalog.parse(Json.parseToJsonElement(text), languages)

    private fun song(id: String, title: String, album: String, language: String = "tamil", year: Int = 2023) =
        """{"id":"$id","title":"$title","type":"song","year":"$year","language":"$language","image":"https://c.saavncdn.com/187/x-150x150.jpg",
           "more_info":{"album":"$album","duration":"207","artistMap":{"primary_artists":[{"name":"Anirudh Ravichander"},{"name":"Super Subu"}]}}}"""

    @Test
    fun `a song of a film's album`() {
        val item = parse(song("or8LPjW6", "Hukum - Thalaivar Alappara", "Jailer"))!!
        assertEquals("saavn-song-or8LPjW6", item.id)
        assertEquals("song", item.kind)
        assertEquals("Hukum - Thalaivar Alappara" to "Jailer", item.title to item.movie)
        assertEquals(2023, item.year)
        assertEquals("Anirudh Ravichander, Super Subu", item.artist)
        assertEquals(207_000L, item.durationMs)
        assertEquals("https://c.saavncdn.com/187/x-500x500.jpg", item.artworkUrl)
        assertEquals("tamil", item.language)
        assertFalse(item.compilation)
    }

    @Test
    fun `a single says its film, and its name is the library's`() {
        val item = parse(song("qd8xWKJ4", "Hukum Reloaded - Tamil (From &quot;Jailer 2&quot;)", "Hukum Reloaded - Tamil (From &quot;Jailer 2&quot;)", year = 2025))!!
        assertEquals("Hukum Reloaded" to "Jailer 2", item.title to item.movie)
        // Written without a space, on a compilation of the composer's songs: still Baasha's.
        val old = parse(song("PnRsjRB_", "Style Stylethe Idhu Super (From&quot;Baasha&quot;)", "Thenisai Thendral", year = 2019))!!
        assertEquals("Style Stylethe Idhu Super" to "Baasha", old.title to old.movie)
    }

    @Test
    fun `another language's version of the film isn't offered`() {
        assertNull(parse(song("rtIe2EtQ", "Hukum", "Jailer", language = "telugu")))
        // With a Telugu library it is, and says so.
        assertEquals("telugu", parse(song("rtIe2EtQ", "Hukum", "Jailer", language = "telugu"), setOf("tamil", "telugu"))!!.language)
    }

    @Test
    fun `albums are films, compilations and singles aren't`() {
        val film = parse("""{"id":"81308476","title":"Jailer 2","type":"album","year":"2026","language":"tamil","more_info":{"music":"Anirudh Ravichander","song_count":"12"}}""")!!
        assertEquals("saavn-movie-81308476", film.id)
        assertEquals("Jailer 2" to "Jailer 2", film.title to film.movie)
        assertEquals("Anirudh Ravichander", film.artist)
        assertEquals(12, film.trackCount)
        assertEquals("DC", parse("""{"id":"1","title":"DC (Original Motion Picture Soundtrack)","type":"album","language":"tamil"}""")!!.movie)
        assertEquals("96", parse("""{"id":"2","title":"96","type":"album","language":"tamil"}""")!!.movie)
        assertNull(parse("""{"id":"3","title":"Thalapathy Vijay Top 20 Hits","type":"album","language":"tamil"}"""))
        assertNull(parse("""{"id":"4","title":"One Name (From &quot;Jailer 2&quot;)","type":"album","language":"tamil"}"""))
        // A compilation's song is only offered when it says which film it's from.
        assertNull(parse(song("5", "Kanave Kanave", "Kaadhalin Inimai Feel Good Songs")))
        assertTrue(parse(song("6", "Kanave Kanave (From &quot;David&quot;)", "Kaadhalin Inimai Feel Good Songs"))!!.compilation)
    }

    @Test
    fun `ids from before are looked up in the old catalog`() = runBlocking {
        val old = CatalogItem("itunes-song-1", "song", "Paadal", "Airaa", 2019)
        val legacy = object : Catalog {
            override suspend fun search(query: String) = emptyList<CatalogItem>()
            override suspend fun lookup(id: String) = old.takeIf { it.id == id }
        }
        assertEquals(old, SaavnCatalog(listOf("tamil"), legacy).lookup("itunes-song-1"))
    }

    @Test
    fun `the libraries' languages, the first library's first`() {
        assertEquals(listOf("tamil", "malayalam", "telugu"), SaavnCatalog.languagesOf("tamil=/m/t:film:tamil;malayalam=/m/m:film:malayalam;songs=/m/s:film:telugu"))
    }
}
