package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable data class AdminAccessDto(val isAdmin: Boolean)

@Serializable data class TopItemDto(val name: String, val detail: String? = null, val plays: Int, val coverArt: String? = null)

@Serializable
data class RangeStatsDto(
    val hours: Double,
    val plays: Int,
    val topSongs: List<TopItemDto>,
    val topMovies: List<TopItemDto>,
    val topComposers: List<TopItemDto>,
)

@Serializable
data class UserStatsDto(
    val username: String,
    val displayName: String,
    /** When they last finished a counted play, epoch ms. */
    val lastPlayedAt: Long? = null,
    /** Keys: "today", "7d", "30d", "all". */
    val ranges: Map<String, RangeStatsDto>,
)

@Serializable data class DayHoursDto(val date: String, val hours: Double)

@Serializable
data class StatsDto(
    val generatedAt: Long,
    val users: List<UserStatsDto>,
    /** Everyone's listening per day for the last 30 days, oldest first. */
    val daily: List<DayHoursDto>,
    /** Everyone's listening by hour of the day (0–23) over the last 30 days. */
    val hourOfDay: List<Double>,
)

/**
 * Listening statistics for admins, read from Navidrome's own database (read-only).
 *
 * Navidrome keeps one row per counted play (a song listened to at least halfway, or 4 minutes)
 * in `scrobbles`, and per-song totals in `annotation`. A counted play adds the song's full length,
 * so the hours are a close estimate. Only people who are admins in Navidrome can see this.
 */
class Stats(private val navidromeDb: String?, private val db: Db, private val hiddenUser: String, private val defaultZone: String) {
    private data class NdUser(val id: String, val userName: String, val name: String, val isAdmin: Boolean)
    private data class Song(val title: String, val artist: String, val album: String, val albumId: String, val composer: String, val seconds: Double)
    private data class Play(val userId: String, val at: Long, val song: Song)
    private data class Total(val userId: String, val count: Int, val song: Song)
    private data class Snapshot(val takenAt: Long, val users: List<NdUser>, val plays: List<Play>, val totals: List<Total>)

    private val lock = Mutex()
    @Volatile private var cached: Snapshot? = null

    /** Admin means admin in Navidrome, checked against Navidrome's own user table. */
    suspend fun isAdmin(user: UserDto): Boolean {
        val users = snapshot()?.users ?: return false
        val navidromeId = db.tx { queryOne("SELECT navidrome_id FROM users WHERE id = ?", user.id) { it.getString(1) } }
        return users.any { it.isAdmin && (it.id == navidromeId || (navidromeId == null && it.userName.equals(user.username, true))) }
    }

    /** Everyone here who is a Navidrome admin (they hear about new music requests). */
    suspend fun adminIds(): List<Long> {
        val admins = snapshot()?.users?.filter { it.isAdmin } ?: return emptyList()
        val users = db.tx { query("SELECT id, navidrome_id, username FROM users WHERE deleted_at IS NULL") { Triple(it.getLong(1), it.getString(2), it.getString(3)) } }
        return users.filter { (_, navidromeId, username) ->
            admins.any { it.id == navidromeId || (navidromeId == null && it.userName.equals(username, true)) }
        }.map { it.first }
    }

    suspend fun report(user: UserDto, timeZone: String?): StatsDto {
        if (!isAdmin(user)) throw ApiError(HttpStatusCode.Forbidden, "Only admins can see listening stats")
        val snapshot = snapshot() ?: throw ApiError(HttpStatusCode.ServiceUnavailable, "The server can't read Navidrome's database")
        val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of(defaultZone)
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val since = mapOf(
            "today" to today.atStartOfDay(zone).toEpochSecond(),
            "7d" to now.minusSeconds(7 * DAY).epochSecond,
            "30d" to now.minusSeconds(30 * DAY).epochSecond,
        )

        val users = snapshot.users.filter { !it.userName.equals(hiddenUser, true) }.map { u ->
            val plays = snapshot.plays.filter { it.userId == u.id }
            val ranges = since.mapValues { (_, from) -> summarize(plays.filter { it.at >= from }.map { it.song to 1 }) } +
                ("all" to summarize(snapshot.totals.filter { it.userId == u.id }.map { it.song to it.count }))
            UserStatsDto(u.userName, u.name.ifBlank { u.userName }, plays.maxOfOrNull { it.at }?.times(1000), ranges)
        }.sortedByDescending { it.ranges["all"]?.hours ?: 0.0 }

        val last30 = snapshot.plays.filter { it.at >= since.getValue("30d") }
        val byDay = last30.groupBy { Instant.ofEpochSecond(it.at).atZone(zone).toLocalDate() }
        val daily = (29 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            DayHoursDto(day.toString(), hours(byDay[day].orEmpty().sumOf { it.song.seconds }))
        }
        val byHour = last30.groupBy { Instant.ofEpochSecond(it.at).atZone(zone).hour }
        val hourOfDay = (0..23).map { h -> hours(byHour[h].orEmpty().sumOf { it.song.seconds }) }
        return StatsDto(snapshot.takenAt, users, daily, hourOfDay)
    }

    private fun summarize(plays: List<Pair<Song, Int>>): RangeStatsDto {
        fun top(key: (Song) -> String, name: (Song) -> String, detail: (Song) -> String?, cover: (Song) -> String?) =
            plays.filter { name(it.first).isNotBlank() }
                .groupBy { key(it.first) }
                .map { (_, group) -> group.first().first to group.sumOf { it.second } }
                .sortedByDescending { it.second }
                .take(TOP)
                .map { (song, count) -> TopItemDto(name(song), detail(song)?.takeIf { it.isNotBlank() }, count, cover(song)) }
        return RangeStatsDto(
            hours = hours(plays.sumOf { (song, count) -> song.seconds * count }),
            plays = plays.sumOf { it.second },
            topSongs = top({ it.title + "|" + it.albumId }, { it.title }, { it.artist }, { cover(it) }),
            topMovies = top({ it.albumId }, { it.album }, { it.composer }, { cover(it) }),
            topComposers = top({ it.composer.lowercase() }, { it.composer }, { null }, { null }),
        )
    }

    private fun cover(song: Song) = song.albumId.takeIf { it.isNotBlank() }?.let { "al-$it" }

    private fun hours(seconds: Double) = Math.round(seconds / 36.0) / 100.0 // to two decimals

    /** Navidrome's plays, reread at most once a minute. */
    private suspend fun snapshot(): Snapshot? = lock.withLock {
        val current = cached
        if (current != null && now() - current.takenAt < REFRESH_MS) return current
        withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }?.also { cached = it } ?: current
    }

    private fun load(): Snapshot? {
        val path = navidromeDb?.takeIf { File(it).isFile } ?: return null
        return DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro").use { c ->
            c.createStatement().use { it.execute("PRAGMA busy_timeout = 10000") }
            Snapshot(
                takenAt = now(),
                users = c.query("SELECT id, user_name, name, is_admin FROM user") {
                    NdUser(it.getString(1), it.getString(2), it.getString(3).orEmpty(), it.getBoolean(4))
                },
                // Navidrome has kept every play with its time since 0.58; older versions only have totals.
                plays = runCatching {
                    c.query("SELECT s.user_id, s.submission_time, $SONG_COLUMNS FROM scrobbles s JOIN media_file m ON m.id = s.media_file_id") {
                        Play(it.getString("user_id"), it.getLong("submission_time"), song(it))
                    }
                }.getOrDefault(emptyList()),
                totals = c.query(
                    """SELECT a.user_id, a.play_count, $SONG_COLUMNS FROM annotation a JOIN media_file m ON m.id = a.item_id
                       WHERE a.item_type = 'media_file' AND a.play_count > 0""",
                ) { Total(it.getString("user_id"), it.getInt("play_count"), song(it)) },
            )
        }
    }

    private fun song(rs: ResultSet) = Song(
        title = rs.getString("title").orEmpty(),
        artist = rs.getString("artist").orEmpty(),
        album = rs.getString("album").orEmpty(),
        albumId = rs.getString("album_id").orEmpty(),
        composer = rs.getString("album_artist").orEmpty(),
        seconds = rs.getDouble("duration"),
    )

    private companion object {
        const val DAY = 86_400L
        const val TOP = 5
        const val REFRESH_MS = 60_000L
        const val SONG_COLUMNS = "m.title, m.artist, m.album, m.album_id, m.album_artist, m.duration"
    }
}
