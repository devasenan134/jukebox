package io.github.devasenan134.jukebox.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogParseTest {
    private fun parse(text: String) = ITunesCatalog.parse(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun `a song on its movie's album`() {
        val item = parse(
            """{"wrapperType":"track","kind":"song","trackId":1,"trackName":"Kanave Kanave","collectionName":"David (Original Motion Picture Soundtrack)",
               "artistName":"Anirudh","releaseDate":"2013-01-03T12:00:00Z","artworkUrl100":"https://x/100x100bb.jpg","trackTimeMillis":284784}""",
        )!!
        assertEquals(CatalogItem("itunes-song-1", "song", "Kanave Kanave", "David", 2013, "Anirudh", "https://x/600x600bb.jpg", durationMs = 284784), item)
    }

    @Test
    fun `a song on a compilation says its movie`() {
        val item = parse(
            """{"wrapperType":"track","kind":"song","trackId":2,"trackName":"Kanave Kanave (From \"David\")","collectionName":"Kollywood's Top 10 Unforgettable Hits Of 2020"}""",
        )!!
        assertEquals("Kanave Kanave", item.title)
        assertEquals("David", item.movie)
        assertTrue(item.compilation)
    }

    @Test
    fun `compilations are not movies`() {
        assertNull(parse("""{"wrapperType":"track","kind":"song","trackId":3,"trackName":"Kanave Kanave","collectionName":"Kaadhalin Inimai Feel Good Songs"}"""))
        assertNull(parse("""{"wrapperType":"collection","collectionId":4,"collectionName":"Siddharth X Chiyaan Vikram Birthday Beats"}"""))
        val movie = parse("""{"wrapperType":"collection","collectionId":5,"collectionName":"Vikram (Original Motion Picture Soundtrack) - EP","trackCount":5,"releaseDate":"1986-01-01T08:00:00Z"}""")!!
        assertEquals("Vikram", movie.movie)
        assertEquals("movie", movie.kind)
        assertEquals(1986, movie.year)
        // A single "from" a movie isn't the movie's album; square brackets are soundtracks too.
        assertNull(parse("""{"wrapperType":"collection","collectionId":6,"collectionName":"Adheeraa (From \"Cobra\")"}"""))
        assertEquals("I (Hindi)", parse("""{"wrapperType":"collection","collectionId":7,"collectionName":"I (Hindi) [Original Motion Picture Soundtrack]"}""")!!.movie)
        assertNull(parse("""{"wrapperType":"track","kind":"song","trackId":8,"trackName":"Munbe Vaa","collectionName":"A. R. Rahman Vibration","collectionArtistName":"Various Artists"}"""))
    }
}

/** A library that can grow during a test, the way new files show up at a scan. */
private class GrowingMusic : MusicSource {
    var songs = listOf(
        LibrarySong("s1", "Kanave Kanave", "David", "m1", "Singer", emptyList(), Person("p-a", "Anirudh"), 2013, 240, "Tamil", 0, false),
    )
    override suspend fun snapshot() = LibrarySnapshot(songs, arrayOfNulls(songs.size), emptyMap(), emptyMap(), FloatArray(songs.size), FloatArray(songs.size), "v${songs.size}")
    override suspend fun history(userId: Long, snapshot: LibrarySnapshot) = History.EMPTY
    override suspend fun popularity(snapshot: LibrarySnapshot) = emptyMap<Int, Int>()
}

private class FakeCatalog(val items: List<CatalogItem>) : Catalog {
    override suspend fun search(query: String) = items
    override suspend fun lookup(id: String) = items.firstOrNull { it.id == id }
}

private class RecordingPush : PushSender {
    val sent = mutableListOf<Pair<String, Map<String, String>>>()
    override suspend fun send(deviceToken: String, data: Map<String, String>): PushSender.Result {
        sent += deviceToken to data
        return PushSender.Result.Sent
    }
}

class MusicRequestsTest {
    private val catalog = FakeCatalog(
        listOf(
            CatalogItem("itunes-song-1", "song", "Kanave Kanave", "David", 2013),
            CatalogItem("itunes-song-2", "song", "Nenjukkulle", "Kadal", 2012, compilation = true),
            CatalogItem("itunes-song-3", "song", "Nenjukulle", "Kadal", 2012),
            CatalogItem("itunes-movie-10", "movie", "David", "David", 2013, "Anirudh"),
            CatalogItem("itunes-movie-11", "movie", "Kadal", "Kadal", 2012, "A.R. Rahman", trackCount = 8),
            // iTunes has the 2005 film as a 2013 re-release, and the library has a 1975 film of the same name.
            CatalogItem("itunes-song-20", "song", "Mayilrage", "Anbe Aaruyire", 2013, "A.R. Rahman, Madhushree, Naresh Iyer & Vaali"),
            CatalogItem("itunes-movie-21", "movie", "Anbe Aaruyire", "Anbe Aaruyire", 2013, "A.R. Rahman & Vaali", trackCount = 6),
        ),
    )

    private val downloads = java.nio.file.Files.createTempDirectory("jukebox-requests-dl").toFile()

    private fun setUp(): Triple<MusicRequests, GrowingMusic, RecordingPush> {
        val db = Db(File.createTempFile("jukebox-requests", ".db").apply { delete(); deleteOnExit() }.path)
        runBlocking {
            db.tx {
                listOf(1L to "alice", 2L to "bob", 3L to "admin").forEach { (id, name) ->
                    update("INSERT INTO users (id, username, display_name, created_at) VALUES (?, ?, ?, 0)", id, name, name.replaceFirstChar(Char::uppercase))
                    update("INSERT INTO devices (token, user_id, updated_at) VALUES (?, ?, 0)", "phone-$name", id)
                }
            }
        }
        val music = GrowingMusic()
        val sender = RecordingPush()
        val requests = MusicRequests(db, music, catalog, Push(db, sender), isAdmin = { it.id == 3L }, adminIds = { listOf(3L) }, requestsDir = downloads)
        return Triple(requests, music, sender)
    }

    private val alice = UserDto(1, "alice", "Alice")
    private val bob = UserDto(2, "bob", "Bob")
    private val admin = UserDto(3, "admin", "Admin")

    @Test
    fun `search shows only what the library doesn't have, once each`() = runBlocking {
        val (requests) = setUp()
        val found = requests.search(alice, "kadal")
        assertEquals(listOf("itunes-song-3"), found.songs.map { it.item.id }) // the movie's own album, not the compilation
        assertEquals(listOf("itunes-movie-11"), found.movies.map { it.item.id })
    }

    @Test
    fun `asking, answering and being told`() = runBlocking {
        val (requests, music, push) = setUp()
        val first = requests.request(alice, "itunes-song-3")
        // Bob asks for the same song through another catalog entry: it's the same request.
        val second = requests.request(bob, "itunes-song-2")
        assertEquals(first.id, second.id)
        assertEquals(listOf("Alice", "Bob"), second.askedBy)
        assertEquals(1, push.sent.count { it.second["type"] == "musicRequest" }) // the admin hears once
        assertTrue(requests.search(bob, "kadal").songs.single().request!!.mine)

        assertFailsWith<ApiError> { requests.complete(alice, first.id) } // not an admin
        assertFailsWith<ApiError> { requests.complete(admin, first.id) } // not in the library yet

        music.songs = music.songs + LibrarySong("s2", "Nenjukkulle", "Kadal", "m2", "", emptyList(), null, 2012, 300, "Tamil", 0, false)
        val done = requests.complete(admin, first.id)
        assertEquals("done", done.status)
        assertEquals("m2", done.albumId)
        assertEquals("s2", done.songId)
        val ready = push.sent.filter { it.second["type"] == "musicReady" }
        assertEquals(setOf("phone-alice", "phone-bob"), ready.map { it.first }.toSet())
        assertEquals("m2", ready.first().second["albumId"])
        assertEquals(emptyList(), requests.search(alice, "kadal").songs) // in the library now
        assertEquals("done", requests.mine(alice).single().status)
    }

    @Test
    fun `filling a request finds the right film, then closes with the album the script filled`() = runBlocking {
        val (requests, music, push) = setUp()
        music.songs = music.songs + LibrarySong("old1", "Neeyum Naanum", "Anbe Aaruyire", "m1975", "", emptyList(), Person("p-msv", "M. S. Viswanathan"), 1975, 200, "Tamil", 0, false)
        val song = requests.request(alice, "itunes-song-20")
        val album = requests.request(bob, "itunes-movie-21")

        // The 1975 film has the same name but another year and composer: not this album.
        assertFailsWith<ApiError> { requests.place(admin, song.id, 2005, "A.R.Rahman", "Mayiliragae") }
        assertFailsWith<ApiError> { requests.place(alice, song.id, 2005, "A.R.Rahman", null) } // admins only
        assertEquals("m1975", requests.place(admin, song.id, 1975, null, null).albumId)

        // The script files the 2005 album; after the scan both requests find it.
        music.songs = music.songs + listOf("Aararai Kodi", "Mayiliragae", "Varugirai").mapIndexed { i, t ->
            LibrarySong("new$i", t, "Anbe Aaruyire", "m2005", "", emptyList(), Person("p-arr", "A. R. Rahman"), 2005, 300, "Tamil", 0, false)
        }
        val place = requests.place(admin, song.id, 2005, "A.R.Rahman", "Mayiliragae")
        assertEquals("m2005", place.albumId)
        assertEquals("new1", place.songId)
        assertEquals("m2005", requests.place(admin, album.id, null, "A.R.Rahman", null).albumId) // the composer is enough
        assertNull(requests.place(admin, album.id, 2005, null, null).songId)

        // The catalog's 2013 / "A.R. Rahman & Vaali" don't find it, so the script says where it is.
        assertFailsWith<ApiError> { requests.complete(admin, album.id) }
        assertFailsWith<ApiError> { requests.complete(admin, song.id, CompleteMusicRequest("m2005", "old1")) } // not on that album
        assertFailsWith<ApiError> { requests.complete(admin, song.id, CompleteMusicRequest("nope")) }
        val done = requests.complete(admin, song.id, CompleteMusicRequest(place.albumId, place.songId))
        assertEquals("done" to "new1", done.status to done.songId)
        assertEquals("done", requests.complete(admin, album.id, CompleteMusicRequest("m2005")).status)
        assertEquals(setOf("phone-alice", "phone-bob"), push.sent.filter { it.second["type"] == "musicReady" }.map { it.first }.toSet())
    }

    @Test
    fun `a download is checked by an admin, and a song that comes with its album follows the album`(): Unit = runBlocking {
        val (requests, _, push) = setUp()
        val album = requests.request(bob, "itunes-movie-21")
        val song = requests.request(alice, "itunes-song-20")
        val review = RequestReview(
            "Anbe Aaruyire", 2005, "A.R.Rahman", destination = "new", destinationFolder = "Anbe Aaruyire (2005)",
            tracks = listOf(ReviewTrack(1, "01 - Aararai Kodi.mp3", "Aararai Kodi", durationMs = 321_000, bitrate = 320, lyrics = "synced")),
        )
        File(downloads, "${album.id}").mkdirs()
        File(downloads, "${album.id}/01 - Aararai Kodi.mp3").writeText("pretend mp3")

        assertFailsWith<ApiError> { requests.submitReview(alice, album.id, review) } // admins (the script) only
        assertFailsWith<ApiError> { requests.submitReview(admin, album.id, review.copy(tracks = listOf(ReviewTrack(1, "../secret.key", "x")))) }
        assertEquals("review", requests.submitReview(admin, album.id, review).stage)
        requests.submitReview(admin, song.id, review.copy(coveredBy = album.id))
        requests.reviewsReady(admin, listOf(album.id, song.id))
        assertEquals(listOf("Ready to check"), push.sent.filter { it.second["title"] == "Ready to check" }.map { it.second["title"] }) // one, for both

        // Askers see it's being added, but not the download itself.
        val mine = requests.mine(bob).single()
        assertEquals("review" to null, mine.stage to mine.review)
        assertEquals(321_000L, requests.all(admin).first { it.id == album.id }.review!!.tracks.single().durationMs)

        assertEquals("pretend mp3", requests.reviewFile(admin, album.id, 1).readText())
        assertFailsWith<ApiError> { requests.reviewFile(alice, album.id, 1) }
        assertFailsWith<ApiError> { requests.reviewFile(admin, album.id, 2) }

        assertFailsWith<ApiError> { requests.approve(alice, album.id) }
        assertEquals("approved", requests.approve(admin, album.id).stage)
        assertEquals("approved", requests.all(admin).first { it.id == song.id }.stage) // comes with it
        assertFailsWith<ApiError> { requests.approve(admin, album.id) } // already decided
    }

    @Test
    fun `a rejected download is cleared and the request waits again, with the note for admins`() = runBlocking {
        val (requests) = setUp()
        val r = requests.request(alice, "itunes-movie-21")
        requests.submitReview(admin, r.id, RequestReview("Anbe Aaruyire", 1975))
        val rejected = requests.reject(admin, r.id, "  That's the 1975 film  ")
        assertEquals("rejected" to "That's the 1975 film", rejected.stage to rejected.reviewNote)
        assertNull(requests.mine(alice).single().reviewNote)
        val cleared = requests.clearReview(admin, r.id)
        assertEquals(Triple("open", null, null), Triple(cleared.status, cleared.stage, cleared.review))
        assertEquals("That's the 1975 film", cleared.reviewNote)
        // Declining or completing ends any check.
        requests.submitReview(admin, r.id, RequestReview("Anbe Aaruyire", 2005))
        assertNull(requests.decline(admin, r.id, null).stage)
    }

    @Test
    fun `declining, asking again and taking a request back`(): Unit = runBlocking {
        val (requests, _, push) = setUp()
        val movie = requests.request(alice, "itunes-movie-11")
        assertEquals("declined", requests.decline(admin, movie.id, "Nobody has it").status)
        assertEquals("Nobody has it", push.sent.last { it.second["type"] == "musicDeclined" }.second["body"])
        assertEquals("open", requests.request(bob, "itunes-movie-11").status) // open again
        assertEquals(1, requests.all(admin).size)
        requests.cancel(alice, movie.id)
        requests.cancel(bob, movie.id)
        assertEquals(emptyList(), requests.all(admin)) // nobody wants it any more
        assertFailsWith<ApiError> { requests.request(alice, "itunes-movie-10") } // David is in the library
    }
}
