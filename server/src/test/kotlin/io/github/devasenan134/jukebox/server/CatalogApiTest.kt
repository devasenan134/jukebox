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
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogApiTest {

    private fun seedCatalog(db: Db) = runBlocking {
        db.tx {
            // Libraries
            insert("INSERT INTO libraries (id, name, path, kind, language) VALUES (1, 'tamil', '/music/tamil', 'film', 'tamil')")

            // People
            insert("INSERT INTO people (id, name, sort_name, sound_key) VALUES ('p-arr', 'A.R. Rahman', 'Rahman, A.R.', 'arrahman')")
            insert("INSERT INTO people (id, name, sort_name, sound_key) VALUES ('p-spb', 'S. P. Balasubrahmanyam', 'Balasubrahmanyam, S. P.', 'spb')")
            insert("INSERT INTO people (id, name, sort_name, sound_key) VALUES ('p-vairamuthu', 'Vairamuthu', 'Vairamuthu', 'vairamuthu')")

            // Artwork
            insert("INSERT INTO artwork (id, hash, source, mime) VALUES ('cov-roja', 'hash-roja', 'folder', 'image/jpeg')")

            // Albums
            insert("INSERT INTO albums (id, library_id, title, sort_title, year, kind, cover_id, group_key, created_at, updated_at) VALUES ('alb-roja', 1, 'Roja', 'Roja', 1992, 'film', 'cov-roja', 'roja-1992', 1000, 1000)")

            // Album credits
            insert("INSERT INTO album_credits (album_id, person_id, role, position) VALUES ('alb-roja', 'p-arr', 'composer', 1)")

            // Releases (Soundtrack & Background score)
            insert("INSERT INTO releases (id, album_id, title, kind, year, group_key) VALUES ('rel-roja-ost', 'alb-roja', 'Roja (Original Motion Picture Soundtrack)', 'soundtrack', 1992, 'roja-ost')")
            insert("INSERT INTO releases (id, album_id, title, kind, year, group_key) VALUES ('rel-roja-score', 'alb-roja', 'Roja (Original Background Score)', 'score', 1992, 'roja-score')")

            // Songs (grouping) & Recordings
            insert("INSERT INTO songs (id, title, song_key) VALUES ('song-chinna', 'Chinna Chinna Aasai', 'chinnachinnaaasai')")
            insert("INSERT INTO recordings (id, song_id, title, title_key, version, duration_ms, created_at) VALUES ('rec-chinna-orig', 'song-chinna', 'Chinna Chinna Aasai', 'chinna', 'original', 295000, 1000)")
            insert("INSERT INTO recordings (id, song_id, title, title_key, version, duration_ms, created_at) VALUES ('rec-chinna-inst', 'song-chinna', 'Chinna Chinna Aasai (Instrumental)', 'chinnainst', 'instrumental', 290000, 1000)")
            insert("INSERT INTO recordings (id, song_id, title, title_key, version, duration_ms, created_at) VALUES ('rec-kadhal-roja', null, 'Kadhal Rojave', 'kadhalroja', 'original', 302000, 1000)")
            insert("INSERT INTO recordings (id, song_id, title, title_key, version, duration_ms, created_at) VALUES ('rec-theme', null, 'Roja Theme', 'rojatheme', 'original', 180000, 1000)")

            // Recording credits
            insert("INSERT INTO recording_credits (recording_id, person_id, role, position) VALUES ('rec-chinna-orig', 'p-arr', 'composer', 1)")
            insert("INSERT INTO recording_credits (recording_id, person_id, role, position) VALUES ('rec-chinna-orig', 'p-vairamuthu', 'lyricist', 1)")
            insert("INSERT INTO recording_credits (recording_id, person_id, role, position) VALUES ('rec-kadhal-roja', 'p-spb', 'singer', 1)")
            insert("INSERT INTO recording_credits (recording_id, person_id, role, position) VALUES ('rec-kadhal-roja', 'p-arr', 'composer', 1)")
            insert("INSERT INTO recording_credits (recording_id, person_id, role, position) VALUES ('rec-theme', 'p-arr', 'composer', 1)")

            // Tracks
            insert("INSERT INTO tracks (id, release_id, recording_id, disc, number, title) VALUES ('trk-1', 'rel-roja-ost', 'rec-chinna-orig', 1, 1, 'Chinna Chinna Aasai')")
            insert("INSERT INTO tracks (id, release_id, recording_id, disc, number, title) VALUES ('trk-2', 'rel-roja-ost', 'rec-kadhal-roja', 1, 2, 'Kadhal Rojave')")
            insert("INSERT INTO tracks (id, release_id, recording_id, disc, number, title) VALUES ('trk-3', 'rel-roja-score', 'rec-theme', 1, 1, 'Roja Theme')")

            // Files
            insert("INSERT INTO files (library_id, path, size, mtime, recording_id, track_id, format, duration_ms, scanned_at) VALUES (1, 'Roja/01.m4a', 1000, 1000, 'rec-chinna-orig', 'trk-1', 'm4a', 295000, 1000)")
            insert("INSERT INTO files (library_id, path, size, mtime, recording_id, track_id, format, duration_ms, scanned_at) VALUES (1, 'Roja/02.m4a', 1000, 1000, 'rec-kadhal-roja', 'trk-2', 'm4a', 302000, 1000)")
            insert("INSERT INTO files (library_id, path, size, mtime, recording_id, track_id, format, duration_ms, scanned_at) VALUES (1, 'Roja/03.m4a', 1000, 1000, 'rec-theme', 'trk-3', 'm4a', 180000, 1000)")

            // Lyrics (both synced LRC and text)
            val lrc = """
                [00:14.20]Chinna chinna aasai
                [00:18.50]Siragadikkum aasai
            """.trimIndent()
            insert("INSERT INTO lyrics (recording_id, source, script, synced, text) VALUES ('rec-chinna-orig', 'test', 'ta', 1, ?)", lrc)
        }
    }

    @Test
    fun `albums endpoint returns films with summary, releases and cast`() = testApplication {
        val dir = File.createTempFile("jukebox-catalog-test", "").apply { delete(); mkdirs(); deleteOnExit() }
        val castFile = File(dir, "movie-cast.jsonl").apply {
            writeText("""{"album": "Roja", "year": 1992, "starring": ["Arvind Swami", "Madhoo"]}""" + "\n")
        }
        val dbFile = File(dir, "jukebox.db").path
        val db = Db(dbFile)
        seedCatalog(db)
        addAccount(dbFile, "alice")

        application { jukeboxServer(Config(port = 0, dbPath = dbFile, castFile = castFile.path)) }
        val client = createClient { install(ContentNegotiation) { json(eventJson) } }
        val token = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginRequest("alice"))
        }.body<SessionResponse>().sessionToken

        // 1. List albums
        val albumsResp = client.get("/api/v2/albums") { bearerAuth(token) }.body<AlbumsResponse>()
        assertEquals(1, albumsResp.total)
        val roja = albumsResp.albums.first()
        assertEquals("alb-roja", roja.id)
        assertEquals("Roja", roja.title)
        assertEquals(1992, roja.year)
        assertEquals("A.R. Rahman", roja.composers.first().name)
        assertTrue(roja.cast.contains("Arvind Swami"))
        assertTrue(roja.songCount >= 2)

        // 2. Full album details with releases
        val albumDetail = client.get("/api/v2/albums/alb-roja") { bearerAuth(token) }.body<AlbumDetailDto>()
        assertEquals("alb-roja", albumDetail.id)
        assertEquals(2, albumDetail.releases.size) // Soundtrack + Score

        val ost = albumDetail.releases.first { it.kind == "soundtrack" }
        assertEquals(2, ost.tracks.size)
        val trk1 = ost.tracks.first { it.title == "Chinna Chinna Aasai" }
        assertEquals("rec-chinna-orig", trk1.recording.id)
        assertEquals("Vairamuthu", trk1.recording.lyricists.first().name)
        assertTrue(trk1.recording.hasSyncedLyrics)

        val score = albumDetail.releases.first { it.kind == "score" }
        assertEquals(1, score.tracks.size)
        assertEquals("Roja Theme", score.tracks.first().title)
    }

    @Test
    fun `people endpoint returns filmography and discography`() = testApplication {
        val dir = File.createTempFile("jukebox-catalog-people-test", "").apply { delete(); mkdirs(); deleteOnExit() }
        val dbFile = File(dir, "jukebox.db").path
        val db = Db(dbFile)
        seedCatalog(db)
        addAccount(dbFile, "alice")

        application { jukeboxServer(Config(port = 0, dbPath = dbFile)) }
        val client = createClient { install(ContentNegotiation) { json(eventJson) } }
        val token = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginRequest("alice"))
        }.body<SessionResponse>().sessionToken

        // 1. People list
        val peopleResp = client.get("/api/v2/people") { bearerAuth(token) }.body<PeopleResponse>()
        assertTrue(peopleResp.total >= 3)
        val arr = peopleResp.people.first { it.id == "p-arr" }
        assertTrue(arr.roles.contains("composer"))

        // 2. Person details
        val arrDetail = client.get("/api/v2/people/p-arr") { bearerAuth(token) }.body<PersonDetailDto>()
        assertEquals("p-arr", arrDetail.id)
        assertEquals("A.R. Rahman", arrDetail.name)
        assertTrue(arrDetail.albums.any { it.id == "alb-roja" })
        assertTrue(arrDetail.songs.any { it.id == "rec-chinna-orig" })
    }

    @Test
    fun `songs endpoint returns song details, versions, and synced lyrics`() = testApplication {
        val dir = File.createTempFile("jukebox-catalog-song-test", "").apply { delete(); mkdirs(); deleteOnExit() }
        val dbFile = File(dir, "jukebox.db").path
        val db = Db(dbFile)
        seedCatalog(db)
        addAccount(dbFile, "alice")

        application { jukeboxServer(Config(port = 0, dbPath = dbFile)) }
        val client = createClient { install(ContentNegotiation) { json(eventJson) } }
        val token = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginRequest("alice"))
        }.body<SessionResponse>().sessionToken

        val song = client.get("/api/v2/songs/rec-chinna-orig") { bearerAuth(token) }.body<SongDetailDto>()
        assertEquals("rec-chinna-orig", song.id)
        assertEquals("Chinna Chinna Aasai", song.title)
        assertEquals("Vairamuthu", song.lyricists.first().name)
        assertEquals("A.R. Rahman", song.composers.first().name)

        // Versions
        assertEquals(1, song.versions.size)
        assertEquals("rec-chinna-inst", song.versions.first().id)
        assertEquals("instrumental", song.versions.first().version)

        // Lyrics
        assertEquals(1, song.lyrics.size)
        assertTrue(song.lyrics.first().synced)
        assertEquals(2, song.lyrics.first().lines.size)
        assertEquals(14200L, song.lyrics.first().lines.first().startMs)
        assertEquals("Chinna chinna aasai", song.lyrics.first().lines.first().text)
    }

    @Test
    fun `lyrics search finds matches with timestamps`() = testApplication {
        val dir = File.createTempFile("jukebox-catalog-lyrics-test", "").apply { delete(); mkdirs(); deleteOnExit() }
        val dbFile = File(dir, "jukebox.db").path
        val db = Db(dbFile)
        seedCatalog(db)
        addAccount(dbFile, "alice")

        application { jukeboxServer(Config(port = 0, dbPath = dbFile)) }
        val client = createClient { install(ContentNegotiation) { json(eventJson) } }
        val token = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginRequest("alice"))
        }.body<SessionResponse>().sessionToken

        val resp = client.get("/api/v2/search/lyrics?q=siragadikkum") { bearerAuth(token) }.body<LyricsSearchResponse>()
        assertEquals("siragadikkum", resp.query)
        assertEquals(1, resp.total)
        val match = resp.matches.first()
        assertEquals("rec-chinna-orig", match.recordingId)
        assertEquals("Chinna Chinna Aasai", match.songTitle)
        assertEquals("Siragadikkum aasai", match.matchedLine)
        assertEquals(18500L, match.startMs)
    }
}
