package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatsTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    /** Three songs to have played. */
    private class StatsMusic : MusicSource {
        private fun song(id: String, title: String, singer: String, album: String, albumId: String, composer: String, seconds: Int) =
            LibrarySong(id, title, album, albumId, singer, listOf(Person("p-$singer", singer)), Person("p-$composer", composer), 2000, seconds, "Tamil", 0, false)
        private val songs = listOf(
            song("s1", "Mounamey", "SPB", "Anbe Sivam", "a1", "Vidyasagar", 1800),
            song("s2", "Kaara Aattakkaara", "ARR", "O Kadhal Kanmani", "a2", "A.R. Rahman", 360),
            song("s3", "Hare Rama", "Yuvan", "Arrambam", "a3", "Yuvanshankar Raja", 720),
        )
        override suspend fun snapshot() = LibrarySnapshot(songs, arrayOfNulls(3), emptyMap(), emptyMap(), FloatArray(3), FloatArray(3), "v1")
        override suspend fun history(userId: Long, snapshot: LibrarySnapshot) = History.EMPTY
        override suspend fun popularity(snapshot: LibrarySnapshot) = emptyMap<Int, Int>()
    }

    /** Two people and their plays: each counted play is a "played" event, with totals in play_counts. */
    private fun db(): Db {
        val db = Db(File.createTempFile("jukebox", ".db").apply { delete(); deleteOnExit() }.path)
        val now = System.currentTimeMillis()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val day = 86_400_000L
        runBlocking {
            db.tx {
                update("INSERT INTO users (id, username, display_name, created_at, is_admin) VALUES (1, 'devs', 'Devs', 0, 1)")
                update("INSERT INTO users (id, username, display_name, created_at) VALUES (2, 'sathya', 'Sathya', 0)")
                listOf(
                    Triple("s1", 1, maxOf(todayStart + 60_000, now - 60_000)), // today: 30 min
                    Triple("s1", 1, now - 3 * day), // this week: 30 min
                    Triple("s2", 1, now - 3 * day), // this week: 6 min
                    Triple("s3", 1, now - 20 * day), // this month: 12 min
                    Triple("s3", 2, now - 2 * day), // Sathya, this week: 12 min
                ).forEach { (song, user, at) ->
                    update("INSERT INTO events (user_id, type, at, payload) VALUES (?, 'played', ?, ?)", user, at, """{"recording":"$song"}""")
                }
                // All-time counts include plays imported from before each play had a time.
                listOf(Triple(1, "s1", 10), Triple(1, "s2", 1), Triple(1, "s3", 1), Triple(2, "s3", 1)).forEach { (user, song, count) ->
                    update("INSERT INTO play_counts VALUES (?, ?, ?, ?)", user, song, count, now)
                }
            }
        }
        return db
    }

    @Test
    fun `hours, ranges and top lists for admins only`() = runBlocking {
        val stats = Stats(db(), StatsMusic(), defaultZone = "Asia/Kolkata")
        val devs = UserDto(1, "devs", "Devs")
        val sathya = UserDto(2, "sathya", "Sathya")

        assertTrue(stats.isAdmin(devs))
        assertFalse(stats.isAdmin(sathya))
        val error = assertFailsWith<ApiError> { stats.report(sathya, null) }
        assertEquals(HttpStatusCode.Forbidden, error.status)

        val report = stats.report(devs, "Asia/Kolkata")
        assertEquals(listOf("devs", "sathya"), report.users.map { it.username })

        val me = report.users.first().ranges
        assertEquals(0.5 to 1, me.getValue("today").hours to me.getValue("today").plays)
        assertEquals(1.1 to 3, me.getValue("7d").hours to me.getValue("7d").plays) // 30 + 30 + 6 min
        assertEquals(1.3 to 4, me.getValue("30d").hours to me.getValue("30d").plays) // + 12 min
        assertEquals(5.3 to 12, me.getValue("all").hours to me.getValue("all").plays) // 10×30 + 6 + 12 min
        assertEquals("Mounamey" to 10, me.getValue("all").topSongs.first().let { it.name to it.plays })
        assertEquals(listOf("Anbe Sivam", "O Kadhal Kanmani", "Arrambam").toSet(), me.getValue("all").topMovies.map { it.name }.toSet())
        assertEquals("al-a1", me.getValue("all").topMovies.first().coverArt)
        assertEquals("Vidyasagar", me.getValue("all").topComposers.first().name)

        // Everyone together: 30 days of daily hours ending today, and 24 hourly buckets.
        assertEquals(30, report.daily.size)
        assertEquals(LocalDate.now(zone).toString(), report.daily.last().date)
        assertEquals(24, report.hourOfDay.size)
        assertEquals(1.5, report.hourOfDay.sum(), 0.02) // 1.3 h (devs) + 0.2 h (sathya)
    }

    @Test
    fun `how each sort of mix did`(): Unit = runBlocking {
        val db = db()
        val now = System.currentTimeMillis()
        db.tx {
            for ((mix, kind, first) in listOf(Triple("daily-1", "mix", 0), Triple("daily-2", "mix", 0), Triple("radio-song-s1", "radio", 0), Triple("radio-song-s1", "radio", 20))) {
                update("INSERT INTO suggestion_lists (user_id, mix_id, kind, songs_hash, first_position, served_at) VALUES (1, ?, ?, 0, ?, ?)", mix, kind, first, now)
            }
            for ((source, skipped) in listOf("mix:daily-1" to 0, "mix:daily-2" to 1, "mix:daily-2" to 0, "mix:radio-song-s1" to 1, "playlist:x" to 0)) {
                update("INSERT INTO plays (user_id, song_id, at, played_ms, duration_ms, skipped, source) VALUES (1, 's1', ?, 1000, 2000, ?, ?)", now, skipped, source)
            }
        }
        val mixes = Stats(db, StatsMusic(), defaultZone = "Asia/Kolkata").report(UserDto(1, "devs", "Devs"), null).mixes
        assertEquals(MixStatsDto("Daily Mixes", opened = 2, played = 3, skipped = 1), mixes.first())
        // A station counts as opened once, when it starts; its later batches don't.
        assertEquals(MixStatsDto("Stations", opened = 1, played = 1, skipped = 1), mixes.first { it.name == "Stations" })
        assertEquals(2, mixes.size)
    }
}
