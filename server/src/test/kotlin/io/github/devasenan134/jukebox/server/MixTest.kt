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
import kotlinx.coroutines.runBlocking
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
    fun `your playlists teach your taste even before you play anything`() {
        val lib = Pretend.library()
        // A playlist of kuthu songs (100..199), and nothing played or liked.
        val kuthu = PlaylistTaste((100 until 120).map { it to now - 90 * MixMaker.DAY }, own = true)
        val history = History(emptyMap(), emptyMap(), emptyList(), emptySet(), emptySet(), emptySet(), emptyMap(), playlisted = playlistTaste(listOf(kuthu), now))
        val maker = maker(lib, history)
        assertTrue(maker.hasTaste)
        val discover = assertNotNull(maker.discover())
        assertTrue(discover.songs.none { it.id.removePrefix("song").toInt() in 100 until 120 })
        assertTrue(discover.songs.count { style(it.id) == 1 } >= discover.songs.size / 2)
    }

    @Test
    fun `playlists you made count more than ones you liked, and careful ones more than dumps`() {
        val old = now - 90 * MixMaker.DAY
        val taste = playlistTaste(
            listOf(
                PlaylistTaste(listOf(1 to old, 2 to now), own = true),
                PlaylistTaste((10 until 510).map { it to old }, own = true),
                PlaylistTaste(listOf(3 to old), own = false),
                PlaylistTaste(listOf(1 to old), own = false),
            ),
            now,
        )
        assertTrue(taste.getValue(2) > taste.getValue(1) - 0.4) // recently added counts a bit more
        assertTrue(taste.getValue(1) > taste.getValue(3)) // made > liked
        assertTrue(taste.getValue(1) > taste.getValue(10)) // small playlist > 500-song dump
        assertTrue(taste.getValue(10) > 0.3) // but a big playlist you made still says you like its songs
        assertTrue(taste.values.all { it <= 2.0 })
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
        // Moods and decades lean towards your taste, and the composers and singers rotate for each person: only charts are alike.
        val showcases = setOf("charts")
        for (id in showcases) {
            val a = fan.firstOrNull { it.id == id } ?: continue
            val b = stranger.first { it.id == id }
            assertEquals(a.mixes.map { it.id to it.songs.map { s -> s.id } }, b.mixes.map { it.id to it.songs.map { s -> s.id } }, id)
            assertTrue(a.mixes.none { it.personal }, id)
        }
        assertTrue(fan.first { it.id == "made-for-you" }.mixes.all { it.personal })
        assertTrue(fan.first { it.id == "top-mixes" }.mixes.all { it.personal })
        assertTrue(fan.first { it.id == "your-stations" }.mixes.all { it.personal && it.endless })
        // Yours first: Made for you, Your top mixes, Your stations; and no mix twice on Home.
        assertEquals(listOf("made-for-you", "top-mixes", "your-stations"), fan.take(3).map { it.id })
        val ids = fan.flatMap { it.mixes }.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "no mix twice: $ids")
        // Composers and singers are on Home only as stations; their This Is mixes still open by id.
        assertTrue(fan.none { it.id == "composers" || it.id == "singers" })
        assertTrue(fan.first { it.id == "artist-stations" }.mixes.all { it.endless })
        assertNotNull(maker(lib).byId("composer-composer1")).let { assertTrue(it.title.startsWith("This Is ")) }
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
        assertTrue(ids.any { it.startsWith("radio-composer-") })
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

    // ---------- keeping mixes fresh ----------

    @Test
    fun `songs you were shown and passed over make room for others`() {
        val lib = Pretend.library()
        val yesterday = today.minusDays(1)
        val before = maker(lib).dailyMixes().first().songs.map { it.id }
        val fresh = before.filter { lib.index.getValue(it) !in 0 until 40 }.take(20)
        // Those songs were shown on three days, and never played.
        val shown = (1..3L).map { ShownList("daily-1", today.minusDays(it), fresh.map { id -> lib.index.getValue(id) }) }
        val after = MixMaker(
            lib, melodyFan(lib), emptyMap(), emptyMap(), emptyMap(), 42, today, now, shown = shown,
        ).dailyMixes().first().songs.map { it.id }
        val kept = after.count { it in fresh }
        assertTrue(kept < fresh.size / 2, "most passed-over songs gave way: $kept of ${fresh.size} still there")
        // Songs you went on to play aren't held against you.
        val playedAll = melodyFan(lib).let { h ->
            val ids = fresh.map { lib.index.getValue(it) }
            History(h.playCount, h.lastPlayed + ids.associateWith { now }, h.plays, h.starred, h.starredAlbums, h.starredArtists, h.rating)
        }
        val forgiven = MixMaker(lib, playedAll, emptyMap(), emptyMap(), emptyMap(), 42, today, now, shown = shown)
        val plain = MixMaker(lib, playedAll, emptyMap(), emptyMap(), emptyMap(), 42, today, now)
        assertEquals(plain.dailyMixes().first().songs.map { it.id }, forgiven.dailyMixes().first().songs.map { it.id })
        assertTrue(yesterday.isBefore(today))
    }

    @Test
    fun `discover has new songs every week and doesn't change during it`() {
        val lib = Pretend.library()
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val thisWeek = assertNotNull(maker(lib).discover()).songs.map { it.id }
        // Opening it on Monday doesn't change it on Wednesday.
        val openedMonday = listOf(ShownList("discover", monday, thisWeek.map { lib.index.getValue(it) }))
        val wednesday = monday.plusDays(2)
        val later = MixMaker(lib, melodyFan(lib), emptyMap(), emptyMap(), mapOf(120 to 4), 42, wednesday, now, shown = openedMonday)
        assertEquals(thisWeek, assertNotNull(later.discover()).songs.map { it.id })
        // Next week's has none of them.
        val nextWeek = MixMaker(lib, melodyFan(lib), emptyMap(), emptyMap(), mapOf(120 to 4), 42, monday.plusWeeks(1), now, shown = openedMonday)
        val next = assertNotNull(nextWeek.discover()).songs.map { it.id }
        assertTrue(next.none { it in thisWeek }, "repeated: ${next.filter { it in thisWeek }}")
    }

    @Test
    fun `your top mixes change from day to day and every one of them can be opened`() {
        val lib = Pretend.library()
        val days = (0 until 5L).map { d ->
            MixMaker(lib, melodyFan(lib), emptyMap(), emptyMap(), emptyMap(), 42, today.plusDays(d), now).topMixes()
        }
        assertTrue(days.all { it.size >= 4 }, "sizes ${days.map { it.size }}")
        assertTrue(days.map { day -> day.map { it.id } }.toSet().size > 1, "the same top mixes every day")
        val kinds = days.flatten().map { it.kind }.toSet()
        assertTrue("because" in kinds && "artistmix" in kinds, "kinds: $kinds")
        val maker = maker(lib)
        for (mix in days.first()) {
            val opened = assertNotNull(maker.byId(mix.id), mix.id)
            assertEquals(mix.songs.map { it.id }, opened.songs.map { it.id }, mix.id)
            assertTrue(opened.songs.size >= 10, "${mix.id} has ${opened.songs.size} songs")
        }
        // "Because you played" starts with that song and stays in its style.
        val because = assertNotNull(maker.byId("because-song3"))
        assertEquals("song3", because.songs.first().id)
        assertTrue(because.songs.count { style(it.id) == 0 } > because.songs.size * 0.8)
        // A composer's melodies are theirs, and melodies.
        val composer = lib.songs[3].composer!!
        val melodies = assertNotNull(maker.byId("cmood-${composer.id}-melody"))
        assertTrue(melodies.songs.all { s -> lib.songs[lib.index.getValue(s.id)].composer?.id == composer.id })
        assertEquals("${composer.name} Melodies", melodies.title)
    }

    @Test
    fun `the daylist is named for the time of day`() {
        val lib = Pretend.library()
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        fun at(hour: Int): MixDto {
            val time = today.atTime(hour, 30).atZone(zone)
            return assertNotNull(
                MixMaker(lib, melodyFan(lib), emptyMap(), emptyMap(), emptyMap(), 42, today, time.toInstant().toEpochMilli(), zone = zone).byId("daylist"),
            )
        }
        val morning = at(8)
        val night = at(23)
        assertTrue(morning.title.startsWith("Friday morning "), morning.title)
        assertTrue(night.title.startsWith("Friday night "), night.title)
        assertTrue(morning.songs.size >= 20)
        assertTrue(morning.personal)
    }

    @Test
    fun `seasonal mixes come in their season, songs named for it first`() {
        val rain = (0 until 5).map { i ->
            Pretend.song(1000 + i, 0).copy(id = "rain$i", title = "Mazhaiyil Nanaigiren $i", albumId = "rain$i", album = "Rain $i") to Pretend.sound(0)
        }
        val lib = Pretend.library(rain)
        val season = MixMaker.SEASONS.first { it.key == "rain" }
        val inSeason = MixMaker(lib, melodyFan(lib), emptyMap(), emptyMap(), emptyMap(), 42, LocalDate.of(2026, 10, 28), now)
        val mix = assertNotNull(inSeason.season(season))
        assertTrue(mix.songs.take(8).count { it.id.startsWith("rain") } >= 4, mix.songs.take(8).map { it.title }.toString())
        assertTrue(inSeason.topMixes().first().id == "season-rain")
        val outOfSeason = MixMaker(lib, melodyFan(lib), emptyMap(), emptyMap(), emptyMap(), 42, LocalDate.of(2027, 3, 1), now)
        assertEquals(null, outOfSeason.season(season))
        assertTrue(outOfSeason.topMixes().none { it.kind == "season" })
    }

    // ---------- stations that move ----------

    @Test
    fun `your stations follow what you played this week`() {
        val lib = Pretend.library()
        // A melody fan who played two kuthu songs (style 1) a lot this week.
        val base = melodyFan(lib)
        val week = (1..6).flatMap { listOf(150 to now - it * 3_600_000L, 170 to now - it * 3_600_000L - 60_000) }
        val history = History(base.playCount, base.lastPlayed, week + base.plays, base.starred, base.starredAlbums, base.starredArtists, base.rating)
        val maker = MixMaker(lib, history, emptyMap(), emptyMap(), emptyMap(), 42, today, now)
        val ids = maker.yourStations().map { it.id }
        assertTrue(ids.any { it == "radio-song-song150" || it == "radio-song-song170" } ||
            maker.topMixes().any { it.id == "because-song150" || it.id == "because-song170" }, "this week's songs lead: $ids")
        assertTrue(MixMaker.DISCOVERY in ids, ids.toString())
        assertTrue(ids.any { it.startsWith("radio-composer-") }, ids.toString())
        assertTrue(maker.yourStations().all { it.endless })
    }

    @Test
    fun `mood, decade and discovery stations`() {
        val lib = Pretend.library()
        val maker = maker(lib)
        val sad = assertNotNull(maker.radio("radio-mood-sad", emptySet(), 20, Random(1)))
        assertEquals("Sad Songs Radio", sad.title)
        assertTrue(sad.songs.all { style(it.id) == 0 } && sad.songs.size == 20)
        val nineties = assertNotNull(maker.radio("radio-decade-1990", emptySet(), 20, Random(1)))
        assertTrue(nineties.songs.all { it.year in 1990..1999 }, nineties.songs.map { it.year }.toString())
        val discovery = assertNotNull(maker.radio(MixMaker.DISCOVERY, emptySet(), 20, Random(1)))
        val played = (0 until 40).map { "song$it" }.toSet()
        assertTrue(discovery.songs.none { it.id in played })
        assertEquals(null, maker.radio("radio-mood-nope", emptySet(), 20, Random(1)))
        assertEquals(null, maker.radio("radio-decade-1850", emptySet(), 20, Random(1)))
        // Next batches skip what was played.
        val next = assertNotNull(maker.radio("radio-mood-sad", sad.songs.map { it.id }.toSet(), 20, Random(2)))
        assertTrue(next.songs.none { s -> sad.songs.any { it.id == s.id } })
    }

    @Test
    fun `a station turns away from what you skip`() {
        val lib = Pretend.library()
        val maker = maker(lib)
        // Discovery for a melody fan is mostly melody; skip melody songs and it turns to other styles.
        fun melody(skipped: List<String>) = (1..5).sumOf { r ->
            maker.radio(MixMaker.DISCOVERY, emptySet(), 20, Random(r.toLong()), skipped)!!.songs.count { style(it.id) == 0 }
        }
        val skipped = (60 until 66).map { "song$it" }
        assertTrue(melody(skipped) < melody(emptyList()), "${melody(skipped)} vs ${melody(emptyList())}")
        assertTrue(maker.radio(MixMaker.DISCOVERY, emptySet(), 20, Random(1), skipped)!!.songs.none { it.id in skipped })
    }

    @Test
    fun `composers and singers on Home rotate from day to day`() {
        val lib = Pretend.library()
        val days = (0 until 6L).map { d ->
            MixMaker(lib, melodyFan(lib), emptyMap(), emptyMap(), emptyMap(), 42, today.plusDays(d), now).topPeople(composer = true, count = 4).map { it.id }
        }
        assertTrue(days.toSet().size > 1, "the same composers every day: $days")
        // The ones you play come up most: melody composers (composer0..composer4).
        val yours = days.flatten().count { it.removePrefix("composer").toInt() < 5 }
        assertTrue(yours > days.flatten().size / 2, "$yours of ${days.flatten().size}")
    }

    @Test
    fun `weekly mixes are new on their first two days`() {
        val lib = Pretend.library()
        val monday = LocalDate.of(2027, 1, 18)
        fun discover(day: LocalDate) = MixMaker(lib, melodyFan(lib), emptyMap(), emptyMap(), mapOf(120 to 4), 42, day, now).discover()!!.isNew
        assertTrue(discover(monday) && discover(monday.plusDays(1)))
        assertTrue(!discover(monday.plusDays(2)) && !discover(monday.plusDays(6)))
    }

    @Test
    fun `every mix and station on Home has a cover design`() {
        val lib = Pretend.library()
        val home = maker(lib).home()
        val mixes = home.flatMap { it.mixes }
        val missing = mixes.filter { it.art == null }.map { it.id }
        assertTrue(missing.isEmpty(), "no cover design: $missing")
        val daily = mixes.first { it.id == "daily-1" }.art!!
        assertEquals("sleeve", daily.style)
        assertEquals("01", daily.headline)
        assertTrue(daily.picture!!.startsWith("ar-"), "a Daily Mix shows its main artist")
        // Stations are tuned somewhere on the dial, each to its own place, always the same one.
        val stations = mixes.filter { it.endless }
        assertTrue(stations.all { it.art!!.style == "tuner" })
        val frequencies = stations.map { it.art!!.stamp!!.removeSuffix(" FM").toFloat() }
        assertTrue(frequencies.all { it in 88f..107.9f }, frequencies.toString())
        assertEquals(stations.map { it.art }, maker(lib).home().flatMap { it.mixes }.filter { it.endless }.map { it.art })
    }

    @Test
    fun `songs and score tracks are generated separately in mixes, stations, and recommendations`() {
        val scoreTracks = (0 until 20).map { i ->
            val isRomantic = i % 2 == 0
            val title = if (isRomantic) "Romantic Love Theme $i" else "Mass Elevation BGM $i"
            LibrarySong(
                id = "score$i",
                title = title,
                album = "Movie ${i / 2} (Original Background Score)",
                albumId = "obs${i / 2}",
                artist = "",
                singers = emptyList(),
                composer = Person("composer0", "Composer 0"),
                year = 2020,
                duration = 90,
                genre = "Tamil",
                score = true,
            ) to Pretend.sound(0)
        }
        val lib = Pretend.library(extra = scoreTracks)
        val maker = maker(lib)

        // 1. Daily mixes have ONLY songs, NEVER score tracks
        val daily = maker.dailyMixes()
        assertTrue(daily.isNotEmpty())
        for (m in daily) {
            assertTrue(m.songs.none { it.id.startsWith("score") }, "daily mix must contain only songs, no bgms")
        }

        // 2. Score mixes exist, have unique names, and contain ONLY score tracks
        val scoreMixes = maker.scoreMixes()
        assertTrue(scoreMixes.isNotEmpty(), "score mixes should be generated when library has score tracks")
        val romanticRose = scoreMixes.firstOrNull { it.id == "score-romantic-rose" }
        assertNotNull(romanticRose, "Smell the Romantic rose mix must exist")
        assertEquals("Smell the Romantic rose", romanticRose.title)
        assertTrue(romanticRose.songs.all { it.id.startsWith("score") }, "Romantic rose mix must contain ONLY score/bgm tracks")

        val massElevation = scoreMixes.firstOrNull { it.id == "score-mass-elevation" }
        assertNotNull(massElevation, "Feel the Mass elevation mix must exist")
        assertEquals("Feel the Mass elevation", massElevation.title)
        assertTrue(massElevation.songs.all { it.id.startsWith("score") }, "Mass elevation mix must contain ONLY score/bgm tracks")

        // 3. Stations: song station has only songs, score station has only scores
        val songStation = assertNotNull(maker.radio("radio-song-song5", emptySet(), 20, Random(1)))
        assertTrue(songStation.songs.none { it.id.startsWith("score") }, "song station must only play songs")

        val scoreStation = assertNotNull(maker.radio("radio-song-score0", emptySet(), 10, Random(1)))
        assertTrue(scoreStation.songs.all { it.id.startsWith("score") }, "score station must only play scores/bgms")

        // 4. Playlist recommendations: song playlist recommends songs, score playlist recommends scores
        val songRecs = maker.recommend(listOf("song1", "song2"), 5, 0)
        assertTrue(songRecs.all { !it.id.startsWith("score") }, "recommendations for songs must only be songs")

        val scoreRecs = maker.recommend(listOf("score0", "score1"), 5, 0)
        assertTrue(scoreRecs.all { it.id.startsWith("score") }, "recommendations for score playlist must only be scores")

        // 5. When user listens to BGMs, score mixes come up in Your top mixes
        val bgmFanHistory = History(
            playCount = mapOf(lib.index.getValue("score0") to 10, lib.index.getValue("score1") to 8),
            lastPlayed = mapOf(lib.index.getValue("score0") to now, lib.index.getValue("score1") to now),
            plays = listOf(lib.index.getValue("score0") to now, lib.index.getValue("score1") to now),
            starred = emptySet(), starredAlbums = emptySet(), starredArtists = emptySet(), rating = emptyMap(),
        )
        val bgmMaker = maker(lib, history = bgmFanHistory)
        val home = bgmMaker.home()
        val top = home.first { it.id == "top-mixes" }
        assertTrue(top.mixes.any { it.id.startsWith("score-") }, "BGM fan gets score mixes in Your top mixes")
    }
}

/** A pretend library and play history for the API test. */
private class FakeMusic : MusicSource {
    var lib = Pretend.library()
    val histories = mutableMapOf<Long, History>()
    override suspend fun snapshot() = lib
    override suspend fun history(userId: Long, snapshot: LibrarySnapshot) = histories[userId] ?: History.EMPTY
    override suspend fun popularity(snapshot: LibrarySnapshot) = mapOf(1 to 3, 2 to 2)
}

class MixApiTest {
    @Test
    fun `a mix that comes to Home after a week away is new for a day`(): Unit = runBlocking {
        val path = File.createTempFile("jukebox-new", ".db").apply { delete(); deleteOnExit() }.path
        val music = FakeMusic()
        val userId = addAccount(path, "alice")
        val start = 1_800_000_000_000L
        fun plays(at: Long, songs: IntRange) = History(
            playCount = songs.associateWith { 4 }, lastPlayed = songs.associateWith { at }, plays = songs.map { it to at },
            starred = emptySet(), starredAlbums = emptySet(), starredArtists = emptySet(), rating = emptyMap(),
        )
        music.histories[userId] = plays(start, 0 until 30)
        var clock = start
        val service = MixService(Db(path), music, java.time.ZoneOffset.UTC) { clock }
        val user = UserDto(userId, "alice", "alice")
        // Someone's first Home has nothing new.
        val first = service.home(user).sections.flatMap { it.mixes }
        assertTrue(first.none { it.isNew }, first.filter { it.isNew }.map { it.id }.toString())
        // Their listening changes: a mix that wasn't there before (Because you played a kuthu song) is new; Daily Mix 1 isn't.
        clock += 3_600_000L
        music.histories[userId] = plays(clock, 0 until 30).let { h ->
            History(h.playCount + (150 to 6), h.lastPlayed + (150 to clock), List(6) { 150 to clock - it } + h.plays, emptySet(), emptySet(), emptySet(), emptyMap())
        }
        service.home(user) // the first call answers at once and works out the new one in the background
        kotlinx.coroutines.delay(1_500)
        val later = service.home(user).sections.flatMap { it.mixes }
        val appeared = later.filter { m -> first.none { it.id == m.id } }
        assertTrue(appeared.isNotEmpty() && appeared.all { it.isNew }, "appeared: ${appeared.map { it.id to it.isNew }}")
        assertTrue(later.first { it.id == "daily-1" }.isNew.not())
    }

    @Test
    fun `home, a mix, radio, saving and plays`() = testApplication {
        val music = FakeMusic()
        val now = System.currentTimeMillis()
        val db = File.createTempFile("jukebox-mixes", ".db").apply { delete(); deleteOnExit() }.path
        music.histories[addAccount(db, "alice")] = History(
            playCount = (0 until 30).associateWith { 4 }, lastPlayed = (0 until 30).associateWith { now }, plays = (0 until 30).map { it to now },
            starred = emptySet(), starredAlbums = emptySet(), starredArtists = emptySet(), rating = emptyMap(),
        )
        application { jukeboxServer(Config(0, db), music = music) }
        val client = createClient { install(ContentNegotiation) { json(eventJson) } }
        val token = client.post("/auth/login") {
            contentType(ContentType.Application.Json); setBody(loginRequest("alice"))
        }.body<SessionResponse>().sessionToken

        val home = client.get("/mixes") { bearerAuth(token) }.body<HomeMixes>()
        val summaries = home.sections.flatMap { it.mixes }
        assertTrue(summaries.any { it.id == "daily-1" })
        assertTrue(summaries.all { it.songs.isEmpty() && it.author == AUTHOR })
        assertTrue(summaries.filterNot { it.endless }.all { it.songCount > 0 && it.updatedAt > 0 })

        // The app says its time zone; one Java doesn't know is ignored.
        assertEquals(HttpStatusCode.OK, client.get("/mixes?tz=America/Toronto") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/mixes?tz=Mars/Olympus") { bearerAuth(token) }.status)

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

        // A station told what was skipped doesn't bring it back.
        val steered = client.post("/mixes/radio") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(RadioRequest("radio-song-song3", count = 10, skipped = listOf(station.songs[1].id)))
        }.body<MixDto>()
        assertTrue(steered.songs.none { it.id == station.songs[1].id })
    }
}

