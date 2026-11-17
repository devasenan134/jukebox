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

        // The catalog's 2013 is a re-release's year, but the library's composer is among its artists ("A.R. Rahman & Vaali").
        assertEquals("m2005", requests.complete(admin, album.id).albumId)
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
    fun `an admin picks the album a download joins, relinks a closed request, and old album ids get repaired`(): Unit = runBlocking {
        val (requests, music, push) = setUp()
        val r = requests.request(bob, "itunes-movie-21")
        requests.submitReview(admin, r.id, RequestReview("Anbe Aaruyire", 2005, destination = "new", destinationFolder = "Anbe Aaruyire (2005)"))
        music.songs = music.songs + LibrarySong("x1", "Aararai Kodi", "Anbe Aaruyire", "m2005", "", emptyList(), null, 2005, 300, "Tamil", 0, false)

        assertFailsWith<ApiError> { requests.setDestination(alice, r.id, "m2005") }
        // No files on disk in this test, so the album's folder can't be found.
        assertFailsWith<ApiError> { requests.setDestination(admin, r.id, "m2005") }
        val back = requests.setDestination(admin, r.id, null).review!!
        assertEquals(Triple("new", "Anbe Aaruyire (2005)", true), Triple(back.destination, back.destinationFolder, back.destinationChosen))
        assertEquals(listOf("Aararai Kodi"), requests.place(admin, r.id, null, null, null, albumId = "m2005").songs)

        // Closed by hand with an album, then pointed at another: only the first tells Bob.
        requests.complete(admin, r.id, CompleteMusicRequest("m2005"))
        requests.complete(admin, r.id, CompleteMusicRequest("m1"))
        assertEquals(1, push.sent.count { it.second["type"] == "musicReady" })
        assertEquals("m1", requests.all(admin).first { it.id == r.id }.albumId)

        // A request closed back when the ids were Navidrome's points at nothing: it's found again.
        val kadal = requests.request(alice, "itunes-movie-11")
        music.songs = music.songs + LibrarySong("k1", "Nenjukkulle", "Kadal", "m-kadal", "", emptyList(), null, 2012, 300, "Tamil", 0, false)
        requests.complete(admin, kadal.id)
        val db = requests.javaClass.getDeclaredField("db").apply { isAccessible = true }.get(requests) as Db
        db.tx { update("UPDATE music_requests SET album_id = 'navidrome-id' WHERE id = ?", kadal.id) }
        assertEquals("m-kadal", requests.mine(alice).first { it.id == kadal.id }.albumId)
    }

    @Test
    fun `search knows a score album, a re-release and a filled request are in the library, and a remake isn't`(): Unit = runBlocking {
        val items = listOf(
            CatalogItem("itunes-movie-30", "movie", "Remo Special (Original Background Score + Additional Song)", "Remo Special (Original Background Score + Additional Song)", 2016, "Anirudh Ravichander"),
            CatalogItem("itunes-movie-31", "movie", "Pichaikkaran", "Pichaikkaran", 2021, "Vijay Antony"),
            CatalogItem("itunes-movie-32", "movie", "Anbe Aaruyire", "Anbe Aaruyire", 2013, "A.R. Rahman & Vaali"),
            CatalogItem("itunes-movie-33", "movie", "Vishwaroopam II", "Vishwaroopam II", 2018, "Ghibran"),
        )
        val db = Db(File.createTempFile("jukebox-search", ".db").apply { delete(); deleteOnExit() }.path)
        db.tx { update("INSERT INTO users (id, username, display_name, created_at) VALUES (3, 'admin', 'Admin', 0)") }
        val music = GrowingMusic().apply {
            songs = listOf(
                LibrarySong("r1", "Remo Nee Kadhalan", "Remo", "m-remo", "", emptyList(), Person("p-ani", "Anirudh"), 2016, 240, "Tamil", 0, false),
                LibrarySong("r2", "First Sight", "Remo (Original Background Score)", "m-remo-score", "", emptyList(), Person("p-ani", "Anirudh"), 2016, 120, "Tamil", 0, false, score = true),
                LibrarySong("p1", "Nooru Samigal", "Pichaikkaran", "m-pichai", "", emptyList(), Person("p-va", "Vijay Antony"), 2016, 240, "Tamil", 0, false),
                LibrarySong("a1", "Neeyum Naanum", "Anbe Aaruyire", "m-1975", "", emptyList(), Person("p-msv", "M. S. Viswanathan"), 1975, 240, "Tamil", 0, false),
                LibrarySong("v1", "Your Sinless Eyes", "Vishwaroopam (Original Background Score)", "m-v1", "", emptyList(), Person("p-gh", "Ghibran"), 2013, 240, "Tamil", 0, false, score = true),
                LibrarySong("v2", "Dark Is My Day", "Vishwaroopam II (Original Background Score)", "m-v2-score", "", emptyList(), Person("p-gh", "Ghibran"), 2018, 240, "Tamil", 0, false, score = true),
            )
        }
        val requests = MusicRequests(db, music, object : Catalog {
            override suspend fun search(query: String) = items
            override suspend fun lookup(id: String) = items.firstOrNull { it.id == id }
        }, Push(db, NoPush), isAdmin = { true }, adminIds = { listOf(3L) })
        fun shown(q: String) = runBlocking { requests.search(admin, q).movies.map { it.item.id } }

        assertEquals(emptyList(), shown("remo")) // filed as Remo's background score
        assertEquals(emptyList(), shown("pichaikkaran")) // 2021 is a re-release of the 2016 film, by the same composer
        assertEquals(listOf("itunes-movie-32"), shown("anbe aaruyire")) // the library's is the 1975 film by someone else
        assertEquals(listOf("itunes-movie-33"), shown("vishwaroopam")) // only the sequel's score is here, not its songs

        // Filled and closed, though the library's name for it doesn't match: not offered again.
        val r = requests.request(admin, "itunes-movie-33")
        requests.complete(admin, r.id, CompleteMusicRequest("m-v2-score"))
        assertEquals(emptyList(), shown("vishwaroopam"))

        // That was the score, not the songs: reopened, it's offered again, and nobody was told twice.
        val reopened = requests.reopen(admin, r.id)
        assertEquals(Triple("open", null, null), Triple(reopened.status, reopened.albumId, reopened.closedAt))
        assertFailsWith<ApiError> { requests.reopen(admin, r.id) }
        assertEquals(listOf("itunes-movie-33"), shown("vishwaroopam"))
        assertEquals(true, requests.place(admin, r.id, 2018, "Ghibran", null, score = true).score)
        assertFailsWith<ApiError> { requests.place(admin, r.id, 2018, "Ghibran", null) } // no songs album yet
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

class PlaylistMissingTest {
    private val alice = UserDto(1, "alice", "Alice")
    private val admin = UserDto(3, "admin", "Admin")

    @Test
    fun `an import keeps its missing songs in place, they're requested, and they fill in when they arrive`(): Unit = runBlocking {
        val db = Db(File.createTempFile("jukebox-missing", ".db").apply { delete(); deleteOnExit() }.path)
        db.tx { listOf(1L to "alice", 3L to "admin").forEach { (id, name) -> update("INSERT INTO users (id, username, display_name, created_at) VALUES (?, ?, ?, 0)", id, name, name) } }
        val music = GrowingMusic()
        val requests = MusicRequests(
            db, music, FakeCatalog(listOf(CatalogItem("itunes-song-3", "song", "Nenjukkulle", "Kadal", 2012))), Push(db, RecordingPush()),
            isAdmin = { it.id == 3L }, adminIds = { listOf(3L) },
        )
        val listening = io.github.devasenan134.jukebox.server.library.Listening(db)
        val imports = PlaylistImports(db, music, listening, requests, object : PlaylistReader { override suspend fun read(url: String) = error("no links") })

        val created = imports.create(
            alice,
            ImportCreateRequest(
                "From Spotify",
                items = listOf(
                    ImportItem(track = ImportedTrack("Nenjukkulle", listOf("A.R. Rahman"), "Kadal")),
                    ImportItem(songId = "s1"),
                    ImportItem(track = ImportedTrack("A song nobody has")),
                ),
            ),
        )
        assertEquals(1, created.songCount)
        assertEquals(2, created.missingCount)
        val id = created.playlistId
        var missing = imports.missing(alice, id)
        assertEquals(listOf(0, 1), missing.songs.map { it.before }) // before Kanave Kanave, and at the end
        assertEquals(listOf("s1"), listening.playlist(1, id).songIds) // players only ever see real songs

        assertTrue(imports.requestMissing(alice, id, missing.songs[0].id).queued) // waits in the queue
        while (imports.queue!!.next()) Unit
        val asked = imports.missing(alice, id).songs[0]
        assertEquals("open", asked.request!!.status)
        assertEquals(asked.request.id, imports.missing(alice, id).songs[0].request?.id)

        music.songs = music.songs + LibrarySong("s2", "Nenjukkulle", "Kadal", "m2", "", emptyList(), null, 2012, 300, "Tamil", 0, false)
        requests.complete(admin, asked.request.id)
        missing = imports.missing(alice, id)
        assertEquals(1, missing.filled)
        assertEquals(listOf("s2", "s1"), listening.playlist(1, id).songIds) // in the place it had
        assertEquals(listOf(2), missing.songs.map { it.before })

        // Taking out a song before it keeps it at the end; then it can go too.
        listening.update(1, id, null, null, null, emptyList(), listOf(0))
        assertEquals(listOf(1), imports.missing(alice, id).songs.map { it.before })
        listening.removeMissing(1, id, missing.songs[0].id)
        assertEquals(emptyList(), imports.missing(alice, id).songs)
    }
}

class RequestQueueTest {
    /** A catalog that's busy (finds nothing, says so) for its first [busyFor] searches, and counts its searches. */
    private class BusyCatalog(val items: List<CatalogItem>, var busyFor: Int = 0) : Catalog {
        var busyNow = false
        var searches = 0
        override suspend fun search(query: String): List<CatalogItem> {
            searches++
            busyNow = busyFor-- > 0
            if (busyNow) return emptyList()
            val q = Fuzzy.words(query)
            return items.filter { Fuzzy.match(q, Fuzzy.words(it.title)) > 0 || Fuzzy.match(q, Fuzzy.words(it.movie)) > 0 }
        }
        override suspend fun lookup(id: String) = items.firstOrNull { it.id == id }
        override suspend fun busy() = busyNow
    }

    private val alice = UserDto(1, "alice", "Alice")
    private val bob = UserDto(2, "bob", "Bob")

    private class Setup(val db: Db, val catalog: BusyCatalog, val music: GrowingMusic, val requests: MusicRequests, val listening: io.github.devasenan134.jukebox.server.library.Listening, val imports: PlaylistImports) {
        val queue get() = imports.queue!!
        suspend fun drain() { while (queue.next()) Unit }
    }

    private fun setUp(catalog: BusyCatalog): Setup {
        RequestQueue.BUSY_WAIT_MS = 0
        val db = Db(File.createTempFile("jukebox-queue", ".db").apply { delete(); deleteOnExit() }.path)
        runBlocking { db.tx { listOf(1L to "alice", 2L to "bob").forEach { (id, n) -> update("INSERT INTO users (id, username, display_name, created_at) VALUES (?, ?, ?, 0)", id, n, n) } } }
        val music = GrowingMusic()
        val requests = MusicRequests(db, music, catalog, Push(db, RecordingPush()), isAdmin = { false }, adminIds = { emptyList() })
        val listening = io.github.devasenan134.jukebox.server.library.Listening(db)
        val imports = PlaylistImports(db, music, listening, requests, object : PlaylistReader { override suspend fun read(url: String) = error("no links") })
        return Setup(db, catalog, music, requests, listening, imports)
    }

    @Test
    fun `requests wait in the queue, a busy catalog is waited out, and a song it doesn't have says so`(): Unit = runBlocking {
        val s = setUp(
            BusyCatalog(
                listOf(CatalogItem("itunes-song-3", "song", "Nenjukkulle", "Kadal", 2012), CatalogItem("itunes-song-4", "song", "Moongil Thottam", "Kadal", 2012)),
                busyFor = 1,
            ),
        )
        val id = s.imports.create(
            alice,
            ImportCreateRequest(
                "Mix",
                items = listOf(ImportedTrack("Nenjukkulle"), ImportedTrack("Moongil Thottam"), ImportedTrack("Nothing like it")).map { ImportItem(track = it) },
                requestAll = true,
            ),
        ).playlistId
        assertTrue(s.imports.missing(alice, id).songs.all { it.queued })
        assertEquals(0, s.catalog.searches) // nothing asked of the catalog while you wait

        s.drain()
        val songs = s.imports.missing(alice, id).songs
        assertEquals(listOf("open", "open", null), songs.map { it.request?.status })
        assertEquals(listOf(false, false, false), songs.map { it.queued })
        assertTrue(songs[2].note!!.startsWith("Couldn't find"))
        // The requests it made are held: not in anyone's Requests (nor the fulfil script's) until the queue is done.
        assertEquals(emptyList(), s.requests.mine(alice))
        assertEquals(2, s.requests.releaseHeld())
        assertEquals(2, s.requests.mine(alice).size)
        assertEquals(0, s.requests.releaseHeld())

        // Asking again for one that's requested changes nothing; the one it couldn't find is tried again.
        assertEquals("requested", s.imports.requestTrack(alice, ImportRequestMusic("Nenjukkulle")).status)
        assertEquals("queued", s.imports.requestMissing(alice, id, songs[2].id).let { if (it.queued) "queued" else "?" })
    }

    @Test
    fun `a song in the library is filled in, and a song someone asked for already is joined, without the catalog`(): Unit = runBlocking {
        val s = setUp(BusyCatalog(listOf(CatalogItem("itunes-song-3", "song", "Nenjukkulle", "Kadal", 2012))))
        s.imports.requestTrack(bob, ImportRequestMusic("Nenjukkulle"))
        s.drain()
        assertEquals(1, s.catalog.searches)

        val id = s.imports.create(
            alice,
            ImportCreateRequest("Mix", items = listOf(ImportItem(track = ImportedTrack("Nenjukkulle")), ImportItem(track = ImportedTrack("Kanave Kanave", listOf("Anirudh"), "David")))),
        ).playlistId
        // Kanave Kanave is in the library, but wasn't sure at import time: pretend it was queued anyway.
        s.imports.requestAll(alice, id)
        s.drain()
        assertEquals(1, s.catalog.searches) // Alice joined Bob's request
        val songs = s.imports.missing(alice, id).songs
        assertEquals(listOf("Nenjukkulle"), songs.map { it.track.title }) // Kanave Kanave became a song of the playlist
        assertEquals(listOf("bob", "alice"), songs.single().request!!.askedBy)
    }

    @Test
    fun `two songs of one album are requested as the album`(): Unit = runBlocking {
        val s = setUp(
            BusyCatalog(
                listOf(
                    CatalogItem("itunes-movie-11", "movie", "Minsara Kanavu", "Minsara Kanavu", 1997, "A.R. Rahman", trackCount = 7),
                    CatalogItem("itunes-song-21", "song", "Vennilave Vennilave", "Minsara Kanavu", 1997),
                    CatalogItem("itunes-song-22", "song", "Strawberry Kanne", "Minsara Kanavu", 1997),
                    CatalogItem("itunes-song-31", "song", "Narumugaye", "Iruvar", 1997),
                ),
            ),
        )
        val album = "Minsara Kanavu (Original Motion Picture Soundtrack)"
        val id = s.imports.create(
            alice,
            ImportCreateRequest(
                "90s",
                items = listOf(
                    ImportedTrack("Vennilave Vennilave", listOf("Hariharan"), album),
                    ImportedTrack("Narumugaye", listOf("Unnikrishnan"), "Iruvar"),
                    ImportedTrack("Strawberry Kanne", listOf("Krishnakumar"), album),
                ).map { ImportItem(track = it) },
                requestAll = true,
            ),
        ).playlistId
        s.drain()
        val songs = s.imports.missing(alice, id).songs
        assertEquals(listOf("movie", "song", "movie"), songs.map { it.request?.item?.kind })
        assertEquals(songs[0].request!!.id, songs[2].request!!.id) // one album request for both
    }
}
