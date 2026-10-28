package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import io.github.devasenan134.jukebox.server.library.event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import kotlin.random.Random

@Serializable data class HomeMixes(val sections: List<MixSection>, val analyzedSongs: Int, val totalSongs: Int)
/** The next songs of station [id]: none of [exclude] (what it played or has queued), and less like [skipped] (skipped while it played). */
@Serializable data class RadioRequest(val id: String, val exclude: List<String> = emptyList(), val count: Int = 25, val skipped: List<String> = emptyList())
@Serializable data class RecommendRequest(val songIds: List<String>, val count: Int = 10, val page: Int = 0)

/** One song the app played: how long, and whether it was skipped. */
@Serializable
data class PlayEvent(
    val songId: String,
    val at: Long,
    val playedMs: Long,
    val durationMs: Long = 0,
    val skipped: Boolean = false,
    /** Where it was played from, e.g. "mix:daily-1" (only for statistics). */
    val source: String? = null,
)

private val log = LoggerFactory.getLogger("jukebox.mixes")

@Serializable data class PlaysRequest(val events: List<PlayEvent>)

/**
 * Mixes, playlists and stations by Jukebox, for each person.
 *
 * Nothing is stored as a finished list: every mix is worked out from the library and your listening
 * whenever one of them changed. That's what keeps them up to date: a new song that fits a mix appears
 * in it as soon as Jukebox has scanned it (and the analyzer has listened to it). What each person was
 * shown is kept (suggestion_lists), so songs they keep passing over make room for others.
 */
class MixService(
    private val db: Db,
    private val source: MusicSource,
    private val zone: ZoneId,
    private val clock: () -> Long = ::now,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private class Built(val key: String, val lib: LibrarySnapshot, val maker: MixMaker, val home: List<MixSection>, val checkedAt: Long)
    private val built = ConcurrentHashMap<Long, Built>()

    /** Which songs people put together (see [Together]), shared by everyone and worked out again every few minutes. */
    @Volatile private var together: Pair<String, Together>? = null
    @Volatile private var togetherAt = 0L

    /** Each person's time zone, as their app last said (until then, the server's). */
    private val zones = ConcurrentHashMap<Long, ZoneId>()

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rebuilding = ConcurrentHashMap<Long, Job>()

    /**
     * What [user] had last time, at once, while a fresh copy is worked out in the background (after a song
     * played, say). Only the very first time does anyone wait for it.
     */
    private suspend fun current(user: UserDto): Built {
        val cached = built[user.id] ?: return build(user)
        if (clock() - cached.checkedAt >= RECHECK_MS) {
            rebuilding.computeIfAbsent(user.id) {
                background.launch {
                    try { build(user) } catch (e: Exception) { log.warn("Couldn't work out mixes for {}", user.username, e) } finally { rebuilding.remove(user.id) }
                }
            }
        }
        return cached
    }

    /** Loads the library for mixes now, so the first person to open Home doesn't wait for it. */
    fun warm() {
        background.launch { runCatching { source.snapshot() } }
    }

    suspend fun home(user: UserDto, timeZone: String? = null): HomeMixes {
        timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }?.let { zones[user.id] = it }
        val b = current(user)
        return HomeMixes(b.home.map { s -> s.copy(mixes = s.mixes.map { it.summary() }) }, b.lib.analyzed, b.lib.songs.size)
    }

    suspend fun mix(user: UserDto, id: String): MixDto {
        val b = current(user)
        // Stations on Home are only a name and a cover; their songs are picked when they're opened.
        val mix = b.home.asSequence().flatMap { it.mixes }.firstOrNull { it.id == id && !it.endless } ?: b.maker.byId(id)
        mix?.let { suggested(user, id, "mix", it.songs, 0) }
        return mix ?: followedCopy(user.id, id) ?: throw ApiError(HttpStatusCode.NotFound, "This mix isn't available right now")
    }

    suspend fun radio(user: UserDto, request: RadioRequest): MixDto {
        val count = request.count.coerceIn(1, 50)
        val exclude = request.exclude.take(1000).toSet()
        val mix = build(user).maker.radio(request.id, exclude, count, Random(clock()), request.skipped.take(100))
            ?: throw ApiError(HttpStatusCode.NotFound, "This station isn't available")
        // This batch follows the songs the station already played.
        suggested(user, request.id, "radio", mix.songs, exclude.size)
        return mix
    }

    suspend fun recommend(user: UserDto, request: RecommendRequest): List<MixSong> {
        val count = request.count.coerceIn(1, 50)
        val page = request.page.coerceIn(0, 20)
        return build(user).maker.recommend(request.songIds.take(2000), count, page)
            .also { suggested(user, "recommend", "recommend", it, count * page) }
    }

    // ---------- what was suggested ----------

    /**
     * Writes down a list of songs handed to [user] (see suggestion_lists). Opening the same mix again
     * with the same songs within a day isn't a new suggestion. Never fails the request it belongs to.
     */
    private suspend fun suggested(user: UserDto, mixId: String, kind: String, songs: List<MixSong>, firstPosition: Int) {
        if (songs.isEmpty()) return
        val t = clock()
        val hash = songs.map { it.id }.hashCode()
        runCatching {
            db.tx {
                val last = queryOne(
                    "SELECT songs_hash, served_at FROM suggestion_lists WHERE user_id = ? AND mix_id = ? AND kind = ? ORDER BY served_at DESC LIMIT 1",
                    user.id, mixId, kind,
                ) { it.getInt(1) to it.getLong(2) }
                if (last != null && last.first == hash && t - last.second < MixMaker.DAY) return@tx
                val list = insert(
                    "INSERT INTO suggestion_lists (user_id, mix_id, kind, songs_hash, first_position, served_at) VALUES (?, ?, ?, ?, ?, ?)",
                    user.id, mixId.take(100), kind, hash, firstPosition, t,
                )
                songs.forEachIndexed { i, s ->
                    update("INSERT INTO suggestions (list_id, position, song_id) VALUES (?, ?, ?)", list, firstPosition + i, s.id)
                }
                event(user.id, "suggested", t) {
                    put("mix", mixId.take(100)); put("kind", kind); put("first", firstPosition)
                    putJsonArray("songs") { songs.forEach { add(it.id) } }
                }
                // A year of suggestions is plenty, like plays.
                update("DELETE FROM suggestion_lists WHERE user_id = ? AND served_at < ?", user.id, t - 365 * MixMaker.DAY)
            }
        }
    }

    // ---------- following mixes into Your Library ----------

    suspend fun follow(user: UserDto, id: String) {
        val mix = mix(user, id).summary()
        db.tx {
            val count = queryOne("SELECT count(*) FROM followed_mixes WHERE user_id = ?", user.id) { it.getInt(1) } ?: 0
            if (count >= 200) throw ApiError(HttpStatusCode.BadRequest, "You can save up to 200 mixes")
            update(
                """INSERT INTO followed_mixes (user_id, mix_id, mix_json, followed_at) VALUES (?, ?, ?, ?)
                   ON CONFLICT (user_id, mix_id) DO UPDATE SET mix_json = excluded.mix_json""",
                user.id, id, json.encodeToString(MixDto.serializer(), mix), clock(),
            )
        }
    }

    suspend fun unfollow(user: UserDto, id: String) = db.tx {
        update("DELETE FROM followed_mixes WHERE user_id = ? AND mix_id = ?", user.id, id)
    }

    /** Saved mixes, newest first, as they are now (a mix that no longer exists keeps its last look). */
    suspend fun followed(user: UserDto): List<MixDto> {
        val saved = db.tx {
            query("SELECT mix_id, mix_json FROM followed_mixes WHERE user_id = ? ORDER BY followed_at DESC", user.id) {
                it.getString(1) to it.getString(2)
            }
        }
        if (saved.isEmpty()) return emptyList()
        val b = build(user)
        val current = b.home.flatMap { it.mixes }.associateBy { it.id }
        return saved.map { (id, stored) ->
            (current[id] ?: runCatching { b.maker.byId(id) }.getOrNull())?.summary()
                ?: json.decodeFromString(MixDto.serializer(), stored).copy(songCount = 0)
        }
    }

    private suspend fun followedCopy(userId: Long, id: String): MixDto? = db.tx {
        queryOne("SELECT mix_json FROM followed_mixes WHERE user_id = ? AND mix_id = ?", userId, id) { it.getString(1) }
    }?.let { json.decodeFromString(MixDto.serializer(), it).copy(songCount = 0) }

    // ---------- plays and skips ----------

    suspend fun recordPlays(user: UserDto, events: List<PlayEvent>) {
        if (events.size > 500) throw ApiError(HttpStatusCode.BadRequest, "Too many plays at once")
        val t = clock()
        db.tx {
            for (e in events) {
                if (e.songId.isBlank() || e.songId.length > 100 || e.playedMs < 0) continue
                val at = e.at.coerceIn(t - 30 * MixMaker.DAY, t)
                update(
                    "INSERT INTO plays (user_id, song_id, at, played_ms, duration_ms, skipped, source) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    user.id, e.songId, at, e.playedMs, e.durationMs, if (e.skipped) 1 else 0, e.source?.take(100),
                )
                event(user.id, if (e.skipped) "skipped" else "listened", at) {
                    put("song", e.songId); put("playedMs", e.playedMs); put("durationMs", e.durationMs); e.source?.let { put("source", it.take(100)) }
                }
            }
            // A year of plays is plenty.
            update("DELETE FROM plays WHERE user_id = ? AND at < ?", user.id, t - 365 * MixMaker.DAY)
        }
    }

    // ---------- building ----------

    /** Everything for [user], worked out again only when the library, their listening, their friends' or the day changed. */
    private suspend fun build(user: UserDto): Built {
        val cached = built[user.id]
        if (cached != null && clock() - cached.checkedAt < RECHECK_MS) return cached
        val lib = source.snapshot() ?: throw ApiError(HttpStatusCode.ServiceUnavailable, "Mixes aren't available: the server can't read the library")
        val history = source.history(user.id, lib)
        val skips = skips(user.id, lib)
        val friends = friendsPlays(user.id, lib)
        val zone = zones[user.id] ?: zone
        val time = java.time.Instant.ofEpochMilli(clock()).atZone(zone)
        val today = time.toLocalDate()
        // The daylist changes with the part of the day.
        val dayPart = MixMaker.partOf(time.hour).name
        val (togetherVersion, together) = together(lib)
        val key = listOf(lib.version, history.version, skips.hashCode(), friends.hashCode(), today, dayPart, zone, togetherVersion).joinToString("|")
        if (cached != null && cached.key == key) return Built(key, cached.lib, cached.maker, cached.home, clock()).also { built[user.id] = it }

        val popularity = source.popularity(lib)
        val maker = MixMaker(
            lib, history, skips, popularity, friends, personSeed = user.id * 1_000_003L, today = today, now = clock(), together = together,
            shown = shown(user.id, lib, today, zone), zone = zone,
        )
        val home = remember(user.id, maker.home())
        return Built(key, lib, maker, home, clock()).also { built[user.id] = it }
    }

    /**
     * Which songs people put together: everyone's plays here in the last 180 days (not skipped, and not
     * started by a mix, so mixes don't teach themselves) and everyone's playlists.
     */
    private suspend fun together(lib: LibrarySnapshot): Pair<String, Together> {
        together?.let { if (clock() - togetherAt < TOGETHER_EVERY_MS && it.first.startsWith(lib.version)) return it }
        val plays = db.tx {
            query(
                "SELECT user_id, song_id, at FROM plays WHERE skipped = 0 AND at > ? AND (source IS NULL OR source NOT LIKE 'mix:%')",
                clock() - 180 * MixMaker.DAY,
            ) { rs -> lib.index[rs.getString(2)]?.let { Together.Play(rs.getLong(1), it, rs.getLong(3)) } }.filterNotNull()
        }
        val playlists = runCatching { source.playlists(lib) }.getOrDefault(emptyList())
        val version = "${lib.version}/${plays.size}/${playlists.sumOf { it.size }}"
        val current = together?.takeIf { it.first == version } ?: (version to Together.build(plays, playlists))
        together = current
        togetherAt = clock()
        return current
    }

    /**
     * The lists of songs [userId] was shown in the 10 weeks before [today] (mixes they opened and suggestions
     * for their playlists). Of a mix, only its first 25 songs count: few people scroll further.
     */
    private suspend fun shown(userId: Long, lib: LibrarySnapshot, today: LocalDate, zone: ZoneId): List<ShownList> {
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = db.read {
            query(
                """SELECT l.id, l.mix_id, l.served_at, s.song_id FROM suggestion_lists l JOIN suggestions s ON s.list_id = l.id
                   WHERE l.user_id = ? AND l.kind IN ('mix', 'recommend') AND l.served_at >= ? AND l.served_at < ?
                     AND (l.kind = 'recommend' OR s.position < 25)""",
                userId, start - 70 * MixMaker.DAY, start,
            ) { rs -> Triple(rs.getLong(1), rs.getString(2) to rs.getLong(3), rs.getString(4)) }
        }
        return rows.groupBy { it.first }.values.map { list ->
            val (mixId, at) = list.first().second
            ShownList(mixId, java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), list.mapNotNull { lib.index[it.third] })
        }
    }

    private class MixState(val hash: Int, val updatedAt: Long, val seenAt: Long, val appearedAt: Long)

    /**
     * Sets each mix's [MixDto.updatedAt] to when its songs last changed, and [MixDto.isNew] if it came (back) to
     * Home in the last day after a week or more away (kept in mix_state). Someone's very first Home has nothing new.
     */
    private suspend fun remember(userId: Long, sections: List<MixSection>): List<MixSection> {
        val t = clock()
        val states = db.tx {
            val stored = query("SELECT mix_id, songs_hash, updated_at, seen_at, appeared_at FROM mix_state WHERE user_id = ?", userId) {
                it.getString(1) to MixState(it.getInt(2), it.getLong(3), it.getLong(4), it.getLong(5))
            }.toMap()
            sections.flatMap { it.mixes }.distinctBy { it.id }.associate { mix ->
                val hash = mix.songs.map { it.id }.hashCode()
                val previous = stored[mix.id]
                val updated = if (previous != null && previous.hash == hash) previous.updatedAt else t
                val appeared = when {
                    previous == null -> if (stored.isEmpty()) 0L else t
                    previous.seenAt < t - AWAY_MS -> t
                    else -> previous.appearedAt
                }
                update(
                    "INSERT OR REPLACE INTO mix_state (user_id, mix_id, songs_hash, updated_at, seen_at, appeared_at) VALUES (?, ?, ?, ?, ?, ?)",
                    userId, mix.id, hash, updated, t, appeared,
                )
                mix.id to MixState(hash, updated, t, appeared)
            }
        }
        return sections.map { s ->
            s.copy(mixes = s.mixes.map { m ->
                val state = states.getValue(m.id)
                m.copy(updatedAt = if (m.endless) m.updatedAt else state.updatedAt, isNew = m.isNew || t - state.appearedAt < MixMaker.DAY)
            })
        }
    }

    private suspend fun skips(userId: Long, lib: LibrarySnapshot): Map<Int, SkipStats> = db.tx {
        query(
            "SELECT song_id, sum(skipped), sum(1 - skipped) FROM plays WHERE user_id = ? AND at > ? GROUP BY song_id",
            userId, clock() - 180 * MixMaker.DAY,
        ) { rs -> lib.index[rs.getString(1)]?.let { it to SkipStats(rs.getInt(2), rs.getInt(3)) } }.filterNotNull().toMap()
    }

    /** What this person's friends played in the last 30 days. */
    private suspend fun friendsPlays(userId: Long, lib: LibrarySnapshot): Map<Int, Int> {
        val friendIds = db.tx {
            query(
                "SELECT u.id FROM friendships f JOIN users u ON u.id = f.friend_id WHERE f.user_id = ? AND u.deleted_at IS NULL",
                userId,
            ) { it.getLong(1) }
        }
        val since = clock() - 30 * MixMaker.DAY
        val counts = mutableMapOf<Int, Int>()
        for (id in friendIds) {
            source.history(id, lib).plays.filter { it.second > since }.forEach { counts.merge(it.first, 1, Int::plus) }
        }
        return counts
    }

    private companion object {
        /** Checking whether anything changed takes a few small queries; don't do it more than this often. */
        const val RECHECK_MS = 30_000L
        /** How often to look for new plays and playlist changes for [Together]. */
        const val TOGETHER_EVERY_MS = 10 * 60_000L
        /** A mix away from someone's Home this long is "New" when it comes back. */
        const val AWAY_MS = 7 * MixMaker.DAY
    }
}
