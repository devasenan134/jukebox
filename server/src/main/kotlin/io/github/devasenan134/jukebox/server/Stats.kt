package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
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
 * Listening statistics for admins, from Jukebox's own listening: a "played" event for each counted play (a
 * song listened to at least halfway, or 4 minutes: the players' scrobble) and per-song totals in play_counts.
 * Plays imported from Navidrome are in both. A counted play adds the song's full length, so the hours are a
 * close estimate.
 */
class Stats(private val db: Db, private val music: MusicSource?, private val defaultZone: String) {
    private data class Account(val id: Long, val username: String, val name: String)
    private data class Play(val userId: Long, val at: Long, val song: LibrarySong)
    private data class Total(val userId: Long, val count: Int, val song: LibrarySong)
    private data class Snapshot(val takenAt: Long, val users: List<Account>, val plays: List<Play>, val totals: List<Total>)

    private val lock = Mutex()
    @Volatile private var cached: Snapshot? = null

    suspend fun isAdmin(user: UserDto): Boolean =
        db.tx { queryOne("SELECT is_admin FROM users WHERE id = ?", user.id) { it.getInt(1) == 1 } } == true

    /** Everyone who is an admin (they hear about new music requests). */
    suspend fun adminIds(): List<Long> = db.tx { query("SELECT id FROM users WHERE is_admin = 1 AND deleted_at IS NULL") { it.getLong(1) } }

    suspend fun report(user: UserDto, timeZone: String?): StatsDto {
        if (!isAdmin(user)) throw ApiError(HttpStatusCode.Forbidden, "Only admins can see listening stats")
        val snapshot = snapshot() ?: throw ApiError(HttpStatusCode.ServiceUnavailable, "Stats need the music library, which isn't ready")
        val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of(defaultZone)
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val since = mapOf(
            "today" to today.atStartOfDay(zone).toInstant().toEpochMilli(),
            "7d" to now.minusSeconds(7 * DAY).toEpochMilli(),
            "30d" to now.minusSeconds(30 * DAY).toEpochMilli(),
        )

        val users = snapshot.users.map { u ->
            val plays = snapshot.plays.filter { it.userId == u.id }
            val ranges = since.mapValues { (_, from) -> summarize(plays.filter { it.at >= from }.map { it.song to 1 }) } +
                ("all" to summarize(snapshot.totals.filter { it.userId == u.id }.map { it.song to it.count }))
            UserStatsDto(u.username, u.name.ifBlank { u.username }, plays.maxOfOrNull { it.at }, ranges)
        }.sortedByDescending { it.ranges["all"]?.hours ?: 0.0 }

        val last30 = snapshot.plays.filter { it.at >= since.getValue("30d") }
        val byDay = last30.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
        val daily = (29 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            DayHoursDto(day.toString(), hours(byDay[day].orEmpty().sumOf { it.song.duration.toDouble() }))
        }
        val byHour = last30.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).hour }
        val hourOfDay = (0..23).map { h -> hours(byHour[h].orEmpty().sumOf { it.song.duration.toDouble() }) }
        return StatsDto(snapshot.takenAt, users, daily, hourOfDay)
    }

    private fun summarize(plays: List<Pair<LibrarySong, Int>>): RangeStatsDto {
        fun top(key: (LibrarySong) -> String, name: (LibrarySong) -> String, detail: (LibrarySong) -> String?, cover: (LibrarySong) -> String?) =
            plays.filter { name(it.first).isNotBlank() }
                .groupBy { key(it.first) }
                .map { (_, group) -> group.first().first to group.sumOf { it.second } }
                .sortedByDescending { it.second }
                .take(TOP)
                .map { (song, count) -> TopItemDto(name(song), detail(song)?.takeIf { it.isNotBlank() }, count, cover(song)) }
        return RangeStatsDto(
            hours = hours(plays.sumOf { (song, count) -> song.duration.toDouble() * count }),
            plays = plays.sumOf { it.second },
            topSongs = top({ it.id }, { it.title }, { it.artist }, { it.coverArt }),
            topMovies = top({ it.albumId }, { it.album }, { it.composer?.name }, { it.coverArt }),
            topComposers = top({ it.composer?.id.orEmpty() }, { it.composer?.name.orEmpty() }, { null }, { null }),
        )
    }

    private fun hours(seconds: Double) = Math.round(seconds / 36.0) / 100.0 // to two decimals

    /** Everyone's plays, reread at most once a minute. */
    private suspend fun snapshot(): Snapshot? = lock.withLock {
        val current = cached
        if (current != null && now() - current.takenAt < REFRESH_MS) return current
        val lib = music?.snapshot() ?: return current
        // A song merged into another since it was played counts as the one it became.
        fun song(id: String, mergedInto: String?) = lib.index[mergedInto ?: id]?.let { lib.songs[it] }
        db.read {
            Snapshot(
                takenAt = now(),
                users = query("SELECT id, username, display_name FROM users WHERE deleted_at IS NULL") {
                    Account(it.getLong(1), it.getString(2), it.getString(3).orEmpty())
                },
                plays = query(
                    """SELECT e.user_id, e.at, json_extract(e.payload, '$.recording') AS rec, r.merged_into
                         FROM events e LEFT JOIN recordings r ON r.id = json_extract(e.payload, '$.recording')
                        WHERE e.type = 'played' AND e.user_id IS NOT NULL""",
                ) { rs -> song(rs.getString(3).orEmpty(), rs.getString(4))?.let { Play(rs.getLong(1), rs.getLong(2), it) } }.filterNotNull(),
                totals = query(
                    "SELECT p.user_id, p.count, p.recording_id, r.merged_into FROM play_counts p LEFT JOIN recordings r ON r.id = p.recording_id",
                ) { rs -> song(rs.getString(3), rs.getString(4))?.let { Total(rs.getLong(1), rs.getInt(2), it) } }.filterNotNull(),
            )
        }.also { cached = it }
    }

    private companion object {
        const val DAY = 86_400L
        const val TOP = 5
        const val REFRESH_MS = 60_000L
    }
}
