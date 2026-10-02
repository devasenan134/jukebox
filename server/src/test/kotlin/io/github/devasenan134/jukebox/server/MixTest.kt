package io.github.devasenan134.jukebox.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A pretend library of 3 styles ("melody", "kuthu", "classical"), each with its own sound and
 * composers, so we can check that mixes follow taste. Style s has songs s*100 until s*100+100.
 */
private object Pretend {
    const val DIM = 16
    val styles = listOf("melody", "kuthu", "classical")
    private val random = Random(1)
    private val styleSound = styles.indices.map { s -> FloatArray(DIM) { d -> if (d == s * 3) 1f else 0f } }

    fun sound(style: Int) = unitOf(FloatArray(DIM) { styleSound[style][it] + 0.25f * random.nextFloat() })

    fun song(i: Int, style: Int, added: Long = 0, karaoke: Boolean = false) = LibrarySong(
        id = "song$i", title = "Song $i", album = "Movie ${i / 4}", albumId = "movie${i / 4}",
        artist = "Singer ${i % 7}", singers = listOf(Person("singer${style * 10 + i % 3}", "Singer ${style * 10 + i % 3}")),
        composer = Person("composer${style * 10 + i % 5}", "Composer ${style * 10 + i % 5}"),
        year = 1980 + style * 10 + i % 10, duration = 240, genre = "Tamil", addedAt = added, karaoke = karaoke,
    )

    /** 300 songs (+ [extra]); mood "sad" fits the melody style, "party" the kuthu style. */
    fun library(extra: List<Pair<LibrarySong, FloatArray>> = emptyList(), version: String = "v1"): LibrarySnapshot {
        val base = (0 until 300).map { i -> song(i, i / 100, added = 1_000) to sound(i / 100) }
        val all = base + extra
        val sound = all.map { it.second }.toTypedArray<FloatArray?>()
        val prompts = mapOf("sad" to styleSound[0], "party" to styleSound[1], "kuthu" to styleSound[1], "chill" to styleSound[0], "melody" to styleSound[0])
        val energy = FloatArray(all.size) { i -> if (all[i].first.id.let { id -> id.removePrefix("song").toIntOrNull()?.let { it / 100 == 1 } } == true) -8f else -20f }
        return LibrarySnapshot(all.map { it.first }, sound, moodScores(sound, prompts), prompts, FloatArray(all.size) { 100f }, energy, version)
    }

    fun unitOf(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }
}

private fun style(id: String) = id.removePrefix("song").toInt() / 100

class MixMakerTest {
    private val now = 1_800_000_000_000L
    private val today = LocalDate.of(2027, 1, 15)

    /** Someone who plays melody songs (0..99) a lot and liked two of them. */
    private fun melodyFan(lib: LibrarySnapshot) = History(
        playCount = (0 until 40).associateWith { 3 + it % 4 },
        lastPlayed = (0 until 40).associateWith { now - MixMaker.DAY },
        plays = (0 until 40).map { it to now - it * 60_000L },
        starred = setOf(1, 2), starredAlbums = emptySet(), starredArtists = emptySet(), rating = emptyMap(),
    )

    private fun maker(lib: LibrarySnapshot, history: History = melodyFan(lib), skips: Map<Int, SkipStats> = emptyMap()) =
        MixMaker(lib, history, skips, popularity = (100 until 130).associateWith { 3 }, friendsPlays = mapOf(120 to 4), personSeed = 42, today = today, now = now)

    @Test
    fun `daily mixes follow your taste and are by Jukebox`() {
        val lib = Pretend.library()
        val daily = maker(lib).dailyMixes()
        assertTrue(daily.isNotEmpty())
        val songs = daily.first().songs
        assertTrue(songs.size >= 30, "a daily mix has plenty of songs")
        val melody = songs.count { style(it.id) == 0 }
        assertTrue(melody > songs.size * 0.8, "mostly melody: $melody of ${songs.size}")
        assertTrue(daily.all { it.author == "Jukebox" })
        assertTrue(maker(lib).home().first { it.id == "made-for-you" }.mixes.filter { it.kind == "daily" }.all { it.personal })
        // Never two songs from the same movie back to back.
        assertTrue(songs.zipWithNext().none { (a, b) -> a.albumId == b.albumId })
        // No more than 2 songs from one movie among the new picks... and each song once.
        assertEquals(songs.size, songs.map { it.id }.toSet().size)
    }

    @Test
    fun `the same day gives the same mixes, the next day a fresh selection`() {
        val lib = Pretend.library()
        val a = maker(lib).dailyMixes().first().songs.map { it.id }
        val b = maker(lib).dailyMixes().first().songs.map { it.id }
        assertEquals(a, b)
        val tomorrow = MixMaker(lib, melodyFan(lib), emptyMap(), emptyMap(), emptyMap(), 42, today.plusDays(1), now).dailyMixes().first().songs.map { it.id }
        assertTrue(a != tomorrow)
    }

    @Test
    fun `discover only has songs you haven't played`() {
        val lib = Pretend.library()
        val discover = assertNotNull(maker(lib).discover())
        val played = (0 until 40).map { "song$it" }.toSet()
        assertTrue(discover.songs.none { it.id in played })
        assertTrue(discover.songs.count { style(it.id) == 0 } >= discover.songs.size / 2)
        assertEquals("weekly", discover.refresh)
    }

    @Test
    fun `karaoke and songs you keep skipping stay out`() {
        val karaoke = Pretend.song(900, 0, karaoke = true) to Pretend.sound(0)
        val lib = Pretend.library(listOf(karaoke))
        val skipped = lib.index.getValue("song50")
        val maker = maker(lib, skips = mapOf(skipped to SkipStats(skips = 3, listens = 0)))
        val everything = maker.home().flatMap { it.mixes }.flatMap { it.songs }.map { it.id }
        assertTrue("song900" !in everything)
        assertTrue("song50" !in everything)
    }

    @Test
    fun `a new song that fits appears in the mixes on its own`() {
        val before = Pretend.library()
        val newSongs = (1000 until 1005).map { Pretend.song(it, 0, added = now - MixMaker.DAY) to Pretend.sound(0) }
        val after = Pretend.library(newSongs, version = "v2")
        assertTrue(maker(before).home().flatMap { it.mixes }.none { m -> m.songs.any { it.id == "song1000" } })
        val maker = maker(after)
        val newArrivals = assertNotNull(maker.newArrivals())
        assertEquals((1000 until 1005).map { "song$it" }.toSet(), newArrivals.songs.map { it.id }.toSet())
        // It also ranks among the songs most like a melody favourite (radio, Daily Mix and "sounds like" picks use this).
        val scores = maker.similarTo(listOf(1, 2, 3))
        val rank = scores.indices.sortedByDescending { scores[it] }.indexOf(after.index.getValue("song1000"))
        assertTrue(rank < 100, "rank $rank")
    }

    @Test
    fun `the same song in two movies, or its remix, is picked once`() {
        val original = Pretend.song(5, 0)
        val copies = listOf(
            original.copy(id = "song2000", album = "Old folder", albumId = "old") to Pretend.sound(0),
            original.copy(id = "song2001", title = "${original.title} (Remix)", albumId = "remix") to Pretend.sound(0),
        )
        val lib = Pretend.library(copies)
        val maker = maker(lib)
        val ids = listOf("song5", "song2000", "song2001").map { lib.index.getValue(it) }
        val picked = maker.pick(ids.associateWith { 1f }, 3, Random(1), pool = 3)
        assertEquals(1, picked.size)
    }

    @Test
    fun `showcases are the same for everyone, only mixes from your listening are made for you`() {
        val lib = Pretend.library()
        val fan = maker(lib).home()
        val stranger = MixMaker(lib, History.EMPTY, emptyMap(), (100 until 130).associateWith { 3 }, emptyMap(), personSeed = 7, today = today, now = now).home()
        val showcases = setOf("composers", "singers", "moods", "artist-stations", "charts", "decades")
        for (id in showcases) {
            val a = fan.firstOrNull { it.id == id } ?: continue
            val b = stranger.first { it.id == id }
            assertEquals(a.mixes.map { it.id to it.songs.map { s -> s.id } }, b.mixes.map { it.id to it.songs.map { s -> s.id } }, id)
            assertTrue(a.mixes.none { it.personal }, id)
        }
        assertTrue(fan.first { it.id == "made-for-you" }.mixes.all { it.personal })
        assertTrue(fan.first { it.id == "your-stations" }.mixes.all { it.personal && it.endless })
        assertTrue(fan.first { it.id == "composers" }.mixes.all { it.title.startsWith("This Is ") })
    }

    @Test
    fun `mood mixes use how the songs sound`() {
        val lib = Pretend.library()
        val maker = maker(lib)
        val party = assertNotNull(maker.mood(MixMaker.MOODS.first { it.key == "party" }))
        assertTrue(party.songs.all { style(it.id) == 1 }, "party = kuthu style")
        val sad = assertNotNull(maker.mood(MixMaker.MOODS.first { it.key == "sad" }))
        assertTrue(sad.songs.all { style(it.id) == 0 })
        // Moods that need a description the analyzer didn't make are left out.
        assertEquals(null, maker.mood(MixMaker.MOODS.first { it.key == "devotional" }))
    }

    @Test
    fun `stations never run out and skip what was played`() {
        val lib = Pretend.library()
        val maker = maker(lib)
        val first = assertNotNull(maker.radio("radio-song-song5", emptySet(), 20, Random(1)))
        assertEquals("song5", first.songs.first().id)
        assertTrue(first.endless)
        val played = first.songs.map { it.id }.toSet()
        val next = assertNotNull(maker.radio("radio-song-song5", played, 20, Random(2)))
        assertEquals(20, next.songs.size)
        assertTrue(next.songs.none { it.id in played })
        assertEquals(null, maker.radio("radio-song-nope", emptySet(), 20, Random(1)))
    }

    @Test
    fun `without play history you still get mixes`() {
        val lib = Pretend.library()
        val home = maker(lib, History.EMPTY).home()
        val ids = home.flatMap { it.mixes }.map { it.id }
        assertTrue("popular" in ids)
        assertTrue(ids.any { it.startsWith("mood-") })
        assertTrue(ids.any { it.startsWith("composer-") })
        assertTrue(ids.none { it.startsWith("daily-") })
        // Without listening, nothing is "made for" them yet.
        assertTrue(home.flatMap { it.mixes }.none { it.personal })
    }

    @Test
    fun `without the analyzer, mixes use tags and listening`() {
        val lib = Pretend.library().let { l ->
            LibrarySnapshot(l.songs, arrayOfNulls(l.songs.size), emptyMap(), emptyMap(), l.tempo, l.energy, "tags-only")
        }
        val maker = maker(lib)
        val daily = maker.dailyMixes()
        assertTrue(daily.isNotEmpty())
        assertTrue(daily.first().songs.count { style(it.id) == 0 } > daily.first().songs.size / 2)
        assertTrue(maker.home().none { it.id == "moods" })
    }
}

/** A pretend Navidrome library and play history for the API test. */
private class FakeMusic : MusicSource {
    var lib = Pretend.library()
    val histories = mutableMapOf<String, History>()
    override suspend fun snapshot() = lib
    override suspend fun history(navidromeUserId: String, snapshot: LibrarySnapshot) = histories[navidromeUserId] ?: History.EMPTY
    override suspend fun popularity(snapshot: LibrarySnapshot) = mapOf(1 to 3, 2 to 2)
    override suspend fun navidromeUserId(username: String) = "nd-$username"
}

class MixApiTest {
    @Test
    fun `home, a mix, radio, saving and plays`() = testApplication {
        val music = FakeMusic()
        val now = System.currentTimeMillis()
        music.histories["nd-alice"] = History(
            playCount = (0 until 30).associateWith { 4 }, lastPlayed = (0 until 30).associateWith { now }, plays = (0 until 30).map { it to now },
            starred = emptySet(), starredAlbums = emptySet(), starredArtists = emptySet(), rating = emptyMap(),
        )
        val db = File.createTempFile("jukebox-mixes", ".db").apply { delete(); deleteOnExit() }.path
        application { jukeboxServer(Config(0, db, "http://unused", "", ""), ApiFakeNavidrome(), music = music) }
        val client = createClient { install(ContentNegotiation) { json(eventJson) } }
        val token = client.post("/auth/login") {
            contentType(ContentType.Application.Json); setBody(LoginRequest("alice", "s", "ok-alice"))
        }.body<SessionResponse>().sessionToken

        val home = client.get("/mixes") { bearerAuth(token) }.body<HomeMixes>()
        val summaries = home.sections.flatMap { it.mixes }
        assertTrue(summaries.any { it.id == "daily-1" })
        assertTrue(summaries.all { it.songs.isEmpty() && it.author == AUTHOR })
        assertTrue(summaries.filterNot { it.endless }.all { it.songCount > 0 && it.updatedAt > 0 })

        val daily = client.get("/mixes/daily-1") { bearerAuth(token) }.body<MixDto>()
        assertTrue(daily.songs.isNotEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("/mixes/daily-99") { bearerAuth(token) }.status)

        val station = client.post("/mixes/radio") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(RadioRequest("radio-song-song3", count = 10))
        }.body<MixDto>()
        assertEquals(10, station.songs.size)

        // A station opened from Home comes with songs, not just its name.
        val homeStation = summaries.first { it.endless }
        assertTrue(client.get("/mixes/${homeStation.id}") { bearerAuth(token) }.body<MixDto>().songs.isNotEmpty())

        val recommended = client.post("/mixes/recommend") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(RecommendRequest(listOf("song1", "song2"), count = 5))
        }.body<List<MixSong>>()
        assertEquals(5, recommended.size)
        assertTrue(recommended.none { it.id == "song1" || it.id == "song2" })

        // Save a mix to Your Library, then remove it.
        assertEquals(HttpStatusCode.NoContent, client.put("/mixes/daily-1/follow") { bearerAuth(token) }.status)
        val followed = client.get("/mixes/followed") { bearerAuth(token) }.body<List<MixDto>>()
        assertEquals(listOf("daily-1"), followed.map { it.id })
        client.delete("/mixes/daily-1/follow") { bearerAuth(token) }
        assertTrue(client.get("/mixes/followed") { bearerAuth(token) }.body<List<MixDto>>().isEmpty())

        // Skipping a song again and again keeps it out of the mixes.
        val skipped = daily.songs.first().id
        val plays = PlaysRequest(List(3) { PlayEvent(skipped, now, playedMs = 5_000, durationMs = 240_000, skipped = true) })
        assertEquals(HttpStatusCode.NoContent, client.post("/plays") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(plays) }.status)

        // Every list of songs handed out is written down with each song's place in it. Opening the same
        // mix again with the same songs isn't a new suggestion; a station batch continues its numbering.
        client.get("/mixes/daily-1") { bearerAuth(token) }
        client.post("/mixes/radio") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(RadioRequest("radio-song-song3", exclude = station.songs.map { it.id }, count = 5))
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            fun lists(mixId: String) = c.query("SELECT kind, first_position FROM suggestion_lists WHERE mix_id = ? ORDER BY id", mixId) {
                it.getString(1) to it.getInt(2)
            }
            assertEquals(listOf("mix" to 0), lists("daily-1"))
            assertEquals(listOf("radio" to 0, "radio" to 10), lists("radio-song-song3"))
            assertEquals(listOf("recommend" to 0), lists("recommend"))
            assertEquals(listOf("mix" to 0), lists(homeStation.id))
            val dailySongs = c.query(
                "SELECT s.song_id FROM suggestions s JOIN suggestion_lists l ON l.id = s.list_id WHERE l.mix_id = 'daily-1' ORDER BY s.position",
            ) { it.getString(1) }
            assertEquals(daily.songs.map { it.id }, dailySongs)
            val second = c.query(
                "SELECT s.position FROM suggestions s JOIN suggestion_lists l ON l.id = s.list_id WHERE l.first_position = 10 ORDER BY s.position",
            ) { it.getInt(1) }
            assertEquals((10 until 15).toList(), second)
        }
    }
}

private class ApiFakeNavidrome : Navidrome(Config(0, "", "http://unused", "", "")) {
    override suspend fun users() = null
    override suspend fun checkLogin(username: String, salt: String, token: String) = token == "ok-$username"
}
