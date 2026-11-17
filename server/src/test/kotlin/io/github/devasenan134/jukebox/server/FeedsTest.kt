package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.AudioTools
import io.github.devasenan134.jukebox.server.library.JukeboxLibrary
import io.github.devasenan134.jukebox.server.library.LibraryDef
import io.github.devasenan134.jukebox.server.library.Scanner
import io.github.devasenan134.jukebox.server.library.TestAudio
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Home's lists from the music site: what the sync sends, and the albums of them Home shows. */
class FeedsTest {
    private val root = Files.createTempDirectory("jukebox-music").toFile()
    private val data = Files.createTempDirectory("jukebox-data").toFile()
    private val db = Db(File(data, "jukebox.db").path)
    private val scanner = Scanner(db, AudioTools(), File(data, "artwork"), threads = 2)
    private val tamil = LibraryDef("tamil", root.path, "film")
    private val catalog = object : Catalog {
        override suspend fun search(query: String) = emptyList<CatalogItem>()
        override suspend fun lookup(id: String) = null
    }
    private val admin = UserDto(1, "admin", "Admin")
    private val listener = UserDto(2, "listener", "Listener")
    private var clock = 1_000L

    private fun feeds(): Pair<Feeds, MusicRequests> {
        val requests = MusicRequests(db, JukeboxLibrary(db), catalog, Push(db, NoPush), isAdmin = { it.id == 1L }, adminIds = { listOf(1L) })
        val feeds = Feeds(db, Hub { emptyList() }, { it.id == 1L }, requests::findAlbumId, requests::hasAlbum,
            { ids -> ids.map { buildJsonObject { put("id", JsonPrimitive(it)) } } }, clock = { clock })
        return feeds to requests
    }

    @Test
    fun `a list shows the albums the library has, in the site's order, and only changes when the site's does`(): Unit = runBlocking {
        TestAudio.airaa(root, 1, "Kaariga", 300)
        scanner.scan(tamil)
        val (feeds, requests) = feeds()
        val airaa = requests.findAlbumId("Airaa", 2019, null)
        assertNotNull(airaa)

        feeds.update(admin, "trending", FeedUpdate("Trending on MassTamilan", listOf(
            FeedEntry("Jailer 2", 2026, "Anirudh Ravichander"),
            FeedEntry("Airaa", 2019, "Sundaramurthy K.S."),
        )))
        val first = feeds.list(listener).single()
        assertEquals("trending", first.id)
        assertEquals(listOf(airaa), first.albums.map { it["id"]!!.jsonPrimitive.content })
        assertNull(first.entries) // only admins see what isn't in the library
        assertEquals(2, feeds.list(admin).single().entries!!.size)

        // The same list again: checked, not changed.
        clock = 2_000L
        feeds.update(admin, "trending", FeedUpdate("Trending on MassTamilan", listOf(FeedEntry("Jailer 2", 2026), FeedEntry("Airaa", 2019))))
        feeds.list(admin).single().let { assertEquals(1_000L, it.changedAt); assertEquals(2_000L, it.checkedAt) }

        // A new order is a change; lists keep the order they were first sent in.
        clock = 3_000L
        feeds.update(admin, "trending", FeedUpdate("Trending on MassTamilan", listOf(FeedEntry("Airaa", 2019), FeedEntry("Jailer 2", 2026))))
        feeds.update(admin, "latest", FeedUpdate("Latest Tamil songs", listOf(FeedEntry("Airaa", 2019))))
        val lists = feeds.list(listener)
        assertEquals(listOf("trending", "latest"), lists.map { it.id })
        assertEquals(3_000L, lists.first().changedAt)
    }

    @Test
    fun `only admins change the lists`(): Unit = runBlocking {
        val (feeds, _) = feeds()
        val e = assertFailsWith<ApiError> { feeds.update(listener, "trending", FeedUpdate("Trending", emptyList())) }
        assertEquals(HttpStatusCode.Forbidden, e.status)
        assertFailsWith<ApiError> { feeds.update(admin, "Not an id!", FeedUpdate("Trending", emptyList())) }
    }

    @Test
    fun `the sync finds an album and its folder without a request`(): Unit = runBlocking {
        TestAudio.airaa(root, 1, "Kaariga", 300)
        scanner.scan(tamil)
        db.tx { update("INSERT INTO users (id, username, display_name, created_at) VALUES (1, 'admin', 'Admin', 0)") }
        val (_, requests) = feeds()
        val place = requests.placeAlbum(admin, "Airaa", 2019, null)
        assertEquals("tamil" to "Airaa (2019)", place.library to place.folder)
        assertEquals(listOf("Kaariga"), place.songs)
        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiError> { requests.placeAlbum(admin, "Jailer 2", 2026, null) }.status)
    }
}
