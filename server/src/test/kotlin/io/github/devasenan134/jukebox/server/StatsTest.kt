package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatsTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    /** A tiny stand-in for Navidrome's database: two people, three songs, some plays. */
    private fun fakeNavidrome(): String {
        val path = File.createTempFile("navidrome", ".db").apply { deleteOnExit() }.path
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE user (id TEXT, user_name TEXT, name TEXT, is_admin BOOL)")
                st.execute("CREATE TABLE media_file (id TEXT, title TEXT, artist TEXT, album TEXT, album_id TEXT, album_artist TEXT, duration REAL)")
                st.execute("CREATE TABLE scrobbles (id INTEGER PRIMARY KEY, media_file_id TEXT, user_id TEXT, submission_time INTEGER)")
                st.execute("CREATE TABLE annotation (user_id TEXT, item_id TEXT, item_type TEXT, play_count INTEGER)")
                st.execute("INSERT INTO user VALUES ('nd-devs', 'devs', 'Devs', 1), ('nd-sathya', 'sathya', 'Sathya', 0), ('nd-bot', 'jukebox-bot', '', 1)")
                st.execute(
                    """INSERT INTO media_file VALUES
                       ('s1', 'Mounamey', 'SPB', 'Anbe Sivam', 'a1', 'Vidyasagar', 1800),
                       ('s2', 'Kaara Aattakkaara', 'ARR', 'O Kadhal Kanmani', 'a2', 'A.R. Rahman', 360),
                       ('s3', 'Hare Rama', 'Yuvan', 'Arrambam', 'a3', 'Yuvanshankar Raja', 720)""",
                )
            }
            val now = Instant.now().epochSecond
            val todayStart = LocalDate.now(zone).atStartOfDay(zone).toEpochSecond()
            val plays = listOf(
                Triple("s1", "nd-devs", maxOf(todayStart + 60, now - 60)), // today: 30 min
                Triple("s1", "nd-devs", now - 3 * 86_400), // this week: 30 min
                Triple("s2", "nd-devs", now - 3 * 86_400), // this week: 6 min
                Triple("s3", "nd-devs", now - 20 * 86_400), // this month: 12 min
                Triple("s3", "nd-sathya", now - 2 * 86_400), // Sathya, this week: 12 min
            )
            c.prepareStatement("INSERT INTO scrobbles (media_file_id, user_id, submission_time) VALUES (?, ?, ?)").use { st ->
                plays.forEach { (song, user, at) -> st.setString(1, song); st.setString(2, user); st.setLong(3, at); st.executeUpdate() }
            }
            // All-time counts include plays from before Navidrome kept a time for each one.
            c.createStatement().use {
                it.execute("INSERT INTO annotation VALUES ('nd-devs', 's1', 'media_file', 10), ('nd-devs', 's2', 'media_file', 1), ('nd-devs', 's3', 'media_file', 1), ('nd-sathya', 's3', 'media_file', 1)")
            }
        }
        return path
    }

    private fun ourDb(): Db {
        val db = Db(File.createTempFile("jukebox", ".db").apply { delete(); deleteOnExit() }.path)
        runBlocking {
            db.tx {
                update("INSERT INTO users (id, username, display_name, created_at, navidrome_id) VALUES (1, 'devs', 'Devs', 0, 'nd-devs')")
                update("INSERT INTO users (id, username, display_name, created_at, navidrome_id) VALUES (2, 'sathya', 'Sathya', 0, 'nd-sathya')")
            }
        }
        return db
    }

    @Test
    fun `hours, ranges and top lists for admins only`() = runBlocking {
        val stats = Stats(fakeNavidrome(), ourDb(), hiddenUser = "jukebox-bot", defaultZone = "Asia/Kolkata")
        val devs = UserDto(1, "devs", "Devs")
        val sathya = UserDto(2, "sathya", "Sathya")

        assertTrue(stats.isAdmin(devs))
        assertFalse(stats.isAdmin(sathya))
        val error = assertFailsWith<ApiError> { stats.report(sathya, null) }
        assertEquals(HttpStatusCode.Forbidden, error.status)

        val report = stats.report(devs, "Asia/Kolkata")
        // The server's own admin account isn't a listener, so it's left out.
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
}
