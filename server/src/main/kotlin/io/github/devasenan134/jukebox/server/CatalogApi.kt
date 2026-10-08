package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.Transcoder
import io.github.devasenan134.jukebox.server.subsonic.SubsonicLibrary
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.time.Instant

private val log = LoggerFactory.getLogger("catalog")

// ---------- DTOs for Jukebox API v2 ----------

@Serializable
data class PersonRefDto(
    val id: String,
    val name: String,
    val role: String? = null,
)

@Serializable
data class AlbumSummaryDto(
    val id: String,
    val title: String,
    val year: Int? = null,
    val kind: String = "film",
    val coverArt: String? = null,
    val composers: List<PersonRefDto> = emptyList(),
    val cast: List<String> = emptyList(),
    val songCount: Int = 0,
    val durationMs: Long = 0,
    val starred: String? = null,
)

@Serializable
data class AlbumsResponse(
    val total: Int,
    val albums: List<AlbumSummaryDto>,
)

@Serializable
data class RecordingDto(
    val id: String,
    val title: String,
    val version: String = "original",
    val durationMs: Long = 0,
    val singers: List<PersonRefDto> = emptyList(),
    val composers: List<PersonRefDto> = emptyList(),
    val lyricists: List<PersonRefDto> = emptyList(),
    val coverArt: String? = null,
    val hasSyncedLyrics: Boolean = false,
    val playCount: Int = 0,
    val starred: String? = null,
)

@Serializable
data class TrackDto(
    val id: String,
    val disc: Int = 1,
    val number: Int = 1,
    val title: String,
    val recording: RecordingDto,
)

@Serializable
data class ReleaseDto(
    val id: String,
    val title: String,
    val kind: String, // "soundtrack", "score", "single", "rerelease", "other"
    val year: Int? = null,
    val coverArt: String? = null,
    val tracks: List<TrackDto> = emptyList(),
)

@Serializable
data class AlbumDetailDto(
    val id: String,
    val title: String,
    val year: Int? = null,
    val kind: String = "film",
    val coverArt: String? = null,
    val composers: List<PersonRefDto> = emptyList(),
    val directors: List<String> = emptyList(),
    val cast: List<PersonRefDto> = emptyList(),
    val starred: String? = null,
    val releases: List<ReleaseDto> = emptyList(),
)

@Serializable
data class PersonSummaryDto(
    val id: String,
    val name: String,
    val roles: List<String> = emptyList(),
    val coverArt: String? = null,
    val songCount: Int = 0,
    val movieCount: Int = 0,
)

@Serializable
data class PeopleResponse(
    val total: Int,
    val people: List<PersonSummaryDto>,
)

@Serializable
data class PersonDetailDto(
    val id: String,
    val name: String,
    val roles: List<String> = emptyList(),
    val coverArt: String? = null,
    val songCount: Int = 0,
    val movieCount: Int = 0,
    val albums: List<AlbumSummaryDto> = emptyList(),
    val songs: List<RecordingDto> = emptyList(),
    val movies: List<AlbumSummaryDto> = emptyList(),
)

@Serializable
data class LyricLineDto(
    val startMs: Long,
    val text: String,
)

@Serializable
data class LyricsDto(
    val script: String,
    val synced: Boolean,
    val lines: List<LyricLineDto>,
)

@Serializable
data class SongVersionDto(
    val id: String,
    val title: String,
    val version: String,
)

@Serializable
data class SongDetailDto(
    val id: String,
    val title: String,
    val version: String,
    val durationMs: Long,
    val albumId: String,
    val albumTitle: String,
    val coverArt: String? = null,
    val composers: List<PersonRefDto> = emptyList(),
    val singers: List<PersonRefDto> = emptyList(),
    val lyricists: List<PersonRefDto> = emptyList(),
    val versions: List<SongVersionDto> = emptyList(),
    val lyrics: List<LyricsDto> = emptyList(),
    val playCount: Int = 0,
    val starred: String? = null,
)

@Serializable
data class LyricsMatchDto(
    val recordingId: String,
    val songTitle: String,
    val albumId: String,
    val albumTitle: String,
    val coverArt: String? = null,
    val matchedLine: String,
    val startMs: Long = 0,
)

@Serializable
data class LyricsSearchResponse(
    val query: String,
    val total: Int,
    val matches: List<LyricsMatchDto>,
)

@Serializable
data class UnifiedSearchResponse(
    val query: String,
    val people: List<PersonHit> = emptyList(),
    val movies: List<MovieHit> = emptyList(),
    val songs: List<SongHit> = emptyList(),
    val lyrics: List<LyricsMatchDto> = emptyList(),
)

// ---------- Service implementation ----------

class CatalogService(
    private val db: Db,
    private val castFile: File? = null,
    private val transcoder: Transcoder? = null,
    private val roots: (() -> Map<Long, String>)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CastLine(val album: String, val year: Int = 0, val starring: List<String> = emptyList())

    private fun readCast(): Map<Pair<String, Int>, List<String>> {
        val file = castFile?.takeIf { it.isFile } ?: return emptyMap()
        return file.readLines().mapNotNull { line ->
            runCatching { json.decodeFromString<CastLine>(line) }.getOrNull()?.takeIf { it.starring.isNotEmpty() }
        }.associate { (it.album.lowercase() to it.year) to it.starring }
    }

    private fun Connection.resolveRecordingId(id: String): String {
        var current = id
        repeat(10) {
            current = queryOne("SELECT merged_into FROM recordings WHERE id = ?", current) { it.getString(1) } ?: return current
        }
        return current
    }

    /** Resolves audio file and content-type for a song (supports 'auto', 'mobile', 'original'). */
    suspend fun audio(id: String, quality: String? = "auto"): Pair<File, String>? {
        val rid = db.read { resolveRecordingId(id) }
        val q = quality?.lowercase() ?: "auto"
        if (q == "mobile" || q == "auto") {
            val mobile = transcoder?.find(rid) ?: if (q == "mobile") transcoder?.transcodeOne(rid) else null
            if (mobile != null) return mobile to "audio/ogg; codecs=opus"
        }
        val fileInfo = db.read {
            queryOne(
                """SELECT library_id, path, format FROM files WHERE recording_id = ? AND missing_since IS NULL
                   ORDER BY coalesce(bitrate, 0) DESC LIMIT 1""", rid
            ) { Triple(it.getLong(1), it.getString(2), it.getString(3)) }
        } ?: return null

        val rootPath = roots?.invoke()?.get(fileInfo.first) ?: return null
        val file = File(rootPath, fileInfo.second).takeIf { it.isFile } ?: return null
        return file to SubsonicLibrary.contentType(fileInfo.third)
    }

    suspend fun albums(
        user: UserDto,
        sort: String? = "name",
        kind: String? = null,
        fromYear: Int? = null,
        toYear: Int? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): AlbumsResponse = db.read {
        val castMap = readCast()
        val composers = HashMap<String, MutableList<PersonRefDto>>()
        query(
            """SELECT c.album_id, p.id, p.name FROM album_credits c JOIN people p ON p.id = c.person_id
               WHERE c.role = 'composer' ORDER BY c.album_id, c.position"""
        ) {
            composers.getOrPut(it.getString(1)) { mutableListOf() } += PersonRefDto(it.getString(2), it.getString(3), "composer")
        }

        // Distinct recordings on disk per movie/song album (excluding scores)
        val songsCounts = query(
            """SELECT a.id, count(DISTINCT r.id), sum(DISTINCT r.duration_ms)
               FROM albums a
               JOIN releases rl ON rl.album_id = a.id
               JOIN tracks t ON t.release_id = rl.id
               JOIN recordings r ON r.id = t.recording_id
               JOIN files f ON f.track_id = t.id AND f.missing_since IS NULL
               WHERE rl.kind != 'score'
               GROUP BY a.id"""
        ) { it.getString(1) to (it.getInt(2) to it.getLong(3)) }.toMap()

        // Distinct recordings on disk per background score release
        val scoreCounts = query(
            """SELECT rl.id, count(DISTINCT r.id), sum(DISTINCT r.duration_ms)
               FROM releases rl
               JOIN tracks t ON t.release_id = rl.id
               JOIN recordings r ON r.id = t.recording_id
               JOIN files f ON f.track_id = t.id AND f.missing_since IS NULL
               WHERE rl.kind = 'score'
               GROUP BY rl.id"""
        ) { it.getString(1) to (it.getInt(2) to it.getLong(3)) }.toMap()

        val userLikes = query("SELECT item_id, liked_at FROM likes WHERE user_id = ? AND item_type = 'album'", user.id) {
            it.getString(1) to it.getLong(2)
        }.toMap()

        val playsByAlbum = if (sort == "recent" || sort == "frequent") {
            query(
                """SELECT CASE WHEN rl.kind = 'score' THEN rl.id ELSE a.id END, count(p.count), max(p.last_played)
                   FROM play_counts p
                   JOIN recordings r ON r.id = p.recording_id
                   JOIN tracks t ON t.recording_id = r.id
                   JOIN releases rl ON rl.id = t.release_id
                   JOIN albums a ON a.id = rl.album_id
                   WHERE p.user_id = ?
                   GROUP BY 1""",
                user.id,
            ) { it.getString(1) to (it.getInt(2) to it.getLong(3)) }.toMap()
        } else emptyMap()

        val filmAlbums = if (kind == null || kind != "score") {
            query(
                """SELECT a.id, a.title, a.year, a.kind, a.cover_id, a.created_at, a.sort_title
                   FROM albums a
                   WHERE (? IS NULL OR a.kind = ?)
                     AND (? IS NULL OR a.year >= ?)
                     AND (? IS NULL OR a.year <= ?)""",
                kind, kind, fromYear, fromYear, toYear, toYear,
            ) { rs ->
                val id = rs.getString(1)
                val title = rs.getString(2)
                val year = (rs.getObject(3) as Number?)?.toInt()
                val k = rs.getString(4)
                val coverId = rs.getString(5)
                val createdAt = rs.getLong(6)
                val sortTitle = rs.getString(7)
                val (songCount, duration) = songsCounts[id] ?: (0 to 0L)
                val starred = userLikes[id]?.let { Instant.ofEpochMilli(it).toString() }
                val cast = castMap[title.lowercase() to (year ?: 0)].orEmpty().take(4)

                AlbumSummaryDto(
                    id = id,
                    title = title,
                    year = year,
                    kind = k,
                    coverArt = coverId?.let { "al-$id" },
                    composers = composers[id].orEmpty(),
                    cast = cast,
                    songCount = songCount,
                    durationMs = duration,
                    starred = starred,
                ) to (sortTitle to createdAt)
            }.filter { it.first.songCount > 0 }
        } else emptyList()

        val scoreAlbums = if (kind == null || kind == "score" || kind == "film") {
            query(
                """SELECT rl.id, a.title, rl.title, coalesce(rl.year, a.year), coalesce(rl.cover_id, a.cover_id), a.created_at, a.sort_title, a.id
                   FROM releases rl
                   JOIN albums a ON a.id = rl.album_id
                   WHERE rl.kind = 'score'
                     AND (? IS NULL OR coalesce(rl.year, a.year) >= ?)
                     AND (? IS NULL OR coalesce(rl.year, a.year) <= ?)""",
                fromYear, fromYear, toYear, toYear,
            ) { rs ->
                val relId = rs.getString(1)
                val albumTitle = rs.getString(2)
                val relTitle = rs.getString(3)
                val scoreTitle = "$albumTitle (Original Background Score)"
                val year = (rs.getObject(4) as Number?)?.toInt()
                val coverId = rs.getString(5)
                val createdAt = rs.getLong(6)
                val sortTitle = rs.getString(7) + " ~"
                val parentAlbumId = rs.getString(8)
                val (songCount, duration) = scoreCounts[relId] ?: (0 to 0L)
                val starred = (userLikes[relId] ?: userLikes[parentAlbumId])?.let { Instant.ofEpochMilli(it).toString() }
                val cast = castMap[albumTitle.lowercase() to (year ?: 0)].orEmpty().take(4)

                AlbumSummaryDto(
                    id = relId,
                    title = scoreTitle,
                    year = year,
                    kind = "score",
                    coverArt = coverId?.let { "al-$relId" },
                    composers = composers[parentAlbumId].orEmpty(),
                    cast = cast,
                    songCount = songCount,
                    durationMs = duration,
                    starred = starred,
                ) to (sortTitle to createdAt)
            }.filter { it.first.songCount > 0 }
        } else emptyList()

        val allAlbums = filmAlbums + scoreAlbums

        val sorted = when (sort) {
            "newest" -> allAlbums.sortedByDescending { it.second.second }
            "random" -> allAlbums.shuffled()
            "byYear" -> allAlbums.sortedWith(compareByDescending<Pair<AlbumSummaryDto, Pair<String, Long>>> { it.first.year }.thenBy { it.second.first })
            "recent" -> allAlbums.sortedByDescending { playsByAlbum[it.first.id]?.second ?: 0L }
            "frequent" -> allAlbums.sortedByDescending { playsByAlbum[it.first.id]?.first ?: 0 }
            "starred" -> allAlbums.filter { it.first.starred != null }.sortedByDescending { it.first.starred }
            else -> allAlbums.sortedBy { it.second.first }
        }.map { it.first }

        val paged = sorted.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500))
        AlbumsResponse(total = sorted.size, albums = paged)
    }

    suspend fun album(user: UserDto, id: String): AlbumDetailDto? = db.read {
        val castMap = readCast()
        val album = queryOne(
            "SELECT id, title, year, kind, cover_id FROM albums WHERE id = ?", id
        ) {
            Triple(it.getString(1), it.getString(2), (it.getObject(3) as Number?)?.toInt()) to (it.getString(4) to it.getString(5))
        }

        if (album != null) {
            val (albumId, title, year) = album.first
            val (kind, coverId) = album.second

            val credits = query(
                """SELECT c.role, p.id, p.name FROM album_credits c JOIN people p ON p.id = c.person_id
                   WHERE c.album_id = ? ORDER BY c.role, c.position""", id
            ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }

            val composers = credits.filter { it.first == "composer" }.map { PersonRefDto(it.second, it.third, "composer") }
            val directors = credits.filter { it.first == "director" }.map { it.third }
            val creditActors = credits.filter { it.first == "actor" }.map { PersonRefDto(it.second, it.third, "actor") }
            val castNames = castMap[title.lowercase() to (year ?: 0)].orEmpty()
            val cast = if (creditActors.isNotEmpty()) creditActors else castNames.map {
                PersonRefDto("actor-${Fuzzy.key(it)}", it, "actor")
            }

            val starred = queryOne(
                "SELECT liked_at FROM likes WHERE user_id = ? AND item_type = 'album' AND item_id = ?",
                user.id, id,
            ) { Instant.ofEpochMilli(it.getLong(1)).toString() }

            val userSongLikes = query("SELECT item_id, liked_at FROM likes WHERE user_id = ? AND item_type = 'recording'", user.id) {
                it.getString(1) to it.getLong(2)
            }.toMap()

            val userSongPlays = query("SELECT recording_id, count FROM play_counts WHERE user_id = ?", user.id) {
                it.getString(1) to it.getInt(2)
            }.toMap()

            // Fetch releases (excluding score releases so movie and score are kept separate)
            val releases = query(
                """SELECT rl.id, rl.title, rl.kind, rl.year, rl.cover_id
                   FROM releases rl WHERE rl.album_id = ? AND rl.kind != 'score'
                   ORDER BY CASE rl.kind WHEN 'soundtrack' THEN 0 WHEN 'album' THEN 0 WHEN 'single' THEN 1 ELSE 2 END, rl.year""",
                id,
            ) { rs ->
                val relId = rs.getString(1)
                val relTitle = rs.getString(2)
                val relKind = rs.getString(3)
                val relYear = (rs.getObject(4) as Number?)?.toInt()
                val relCover = rs.getString(5)

                val tracks = query(
                    """SELECT t.id, t.disc, t.number, t.title, r.id, r.title, r.version, r.duration_ms, coalesce(f.cover_id, rl.cover_id, a.cover_id),
                              EXISTS(SELECT 1 FROM lyrics l WHERE l.recording_id = r.id AND l.synced = 1)
                       FROM tracks t
                       JOIN recordings r ON r.id = t.recording_id
                       JOIN releases rl ON rl.id = t.release_id
                       JOIN albums a ON a.id = rl.album_id
                       JOIN files f ON f.track_id = t.id AND f.missing_since IS NULL
                       WHERE t.release_id = ?
                       ORDER BY t.disc, t.number, t.title""",
                    relId,
                ) { trs ->
                    val trkId = trs.getString(1)
                    val disc = trs.getInt(2)
                    val num = trs.getInt(3)
                    val trkTitle = trs.getString(4)
                    val recId = trs.getString(5)
                    val recTitle = trs.getString(6)
                    val version = trs.getString(7) ?: "original"
                    val durationMs = trs.getLong(8)
                    val trkCover = trs.getString(9)
                    val hasSynced = trs.getInt(10) == 1

                    val songCredits = query(
                        """SELECT c.role, p.id, p.name FROM recording_credits c JOIN people p ON p.id = c.person_id
                           WHERE c.recording_id = ? ORDER BY c.role, c.position""",
                        recId,
                    ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }

                    val singers = songCredits.filter { it.first == "singer" }.map { PersonRefDto(it.second, it.third, "singer") }
                    val songComposers = songCredits.filter { it.first == "composer" }.map { PersonRefDto(it.second, it.third, "composer") }
                        .ifEmpty { composers }
                    val lyricists = songCredits.filter { it.first == "lyricist" }.map { PersonRefDto(it.second, it.third, "lyricist") }

                    val songStarred = userSongLikes[recId]?.let { Instant.ofEpochMilli(it).toString() }
                    val playCount = userSongPlays[recId] ?: 0

                    TrackDto(
                        id = trkId,
                        disc = disc,
                        number = num,
                        title = trkTitle,
                        recording = RecordingDto(
                            id = recId,
                            title = recTitle,
                            version = version,
                            durationMs = durationMs,
                            singers = singers,
                            composers = songComposers,
                            lyricists = lyricists,
                            coverArt = trkCover?.let { "al-$id" },
                            hasSyncedLyrics = hasSynced,
                            playCount = playCount,
                            starred = songStarred,
                        ),
                    )
                }.distinctBy { it.recording.id }

                ReleaseDto(
                    id = relId,
                    title = relTitle,
                    kind = relKind,
                    year = relYear,
                    coverArt = relCover?.let { "rl-$relId" } ?: coverId?.let { "al-$id" },
                    tracks = tracks,
                )
            }.filter { it.tracks.isNotEmpty() }

            return@read AlbumDetailDto(
                id = id,
                title = title,
                year = year,
                kind = kind,
                coverArt = coverId?.let { "al-$id" },
                composers = composers,
                directors = directors,
                cast = cast,
                starred = starred,
                releases = releases,
            )
        }

        // If not found in albums table, check if id is a score release
        val scoreRelease = queryOne(
            """SELECT rl.id, a.title, rl.title, coalesce(rl.year, a.year), coalesce(rl.cover_id, a.cover_id), a.id
               FROM releases rl
               JOIN albums a ON a.id = rl.album_id
               WHERE rl.id = ? AND rl.kind = 'score'""", id
        ) {
            val relId = it.getString(1)
            val parentTitle = it.getString(2)
            val relTitle = it.getString(3)
            val year = (it.getObject(4) as Number?)?.toInt()
            val coverId = it.getString(5)
            val parentAlbumId = it.getString(6)
            val scoreTitle = "$parentTitle (Original Background Score)"
            Triple(relId, scoreTitle, year) to (coverId to (parentAlbumId to parentTitle))
        } ?: return@read null

        val (scoreId, scoreTitle, scoreYear) = scoreRelease.first
        val (scoreCoverId, parentInfo) = scoreRelease.second
        val (parentAlbumId, parentTitle) = parentInfo

        val credits = query(
            """SELECT c.role, p.id, p.name FROM album_credits c JOIN people p ON p.id = c.person_id
               WHERE c.album_id = ? ORDER BY c.role, c.position""", parentAlbumId
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }

        val composers = credits.filter { it.first == "composer" }.map { PersonRefDto(it.second, it.third, "composer") }
        val directors = credits.filter { it.first == "director" }.map { it.third }
        val creditActors = credits.filter { it.first == "actor" }.map { PersonRefDto(it.second, it.third, "actor") }
        val castNames = castMap[parentTitle.lowercase() to (scoreYear ?: 0)].orEmpty()
        val cast = if (creditActors.isNotEmpty()) creditActors else castNames.map {
            PersonRefDto("actor-${Fuzzy.key(it)}", it, "actor")
        }

        val starred = queryOne(
            "SELECT liked_at FROM likes WHERE user_id = ? AND item_type = 'album' AND (item_id = ? OR item_id = ?)",
            user.id, scoreId, parentAlbumId,
        ) { Instant.ofEpochMilli(it.getLong(1)).toString() }

        val userSongLikes = query("SELECT item_id, liked_at FROM likes WHERE user_id = ? AND item_type = 'recording'", user.id) {
            it.getString(1) to it.getLong(2)
        }.toMap()

        val userSongPlays = query("SELECT recording_id, count FROM play_counts WHERE user_id = ?", user.id) {
            it.getString(1) to it.getInt(2)
        }.toMap()

        val tracks = query(
            """SELECT t.id, t.disc, t.number, t.title, r.id, r.title, r.version, r.duration_ms, coalesce(f.cover_id, rl.cover_id, a.cover_id),
                      EXISTS(SELECT 1 FROM lyrics l WHERE l.recording_id = r.id AND l.synced = 1)
               FROM tracks t
               JOIN recordings r ON r.id = t.recording_id
               JOIN releases rl ON rl.id = t.release_id
               JOIN albums a ON a.id = rl.album_id
               JOIN files f ON f.track_id = t.id AND f.missing_since IS NULL
               WHERE t.release_id = ?
               ORDER BY t.disc, t.number, t.title""",
            scoreId,
        ) { trs ->
            val trkId = trs.getString(1)
            val disc = trs.getInt(2)
            val num = trs.getInt(3)
            val trkTitle = trs.getString(4)
            val recId = trs.getString(5)
            val recTitle = trs.getString(6)
            val version = trs.getString(7) ?: "original"
            val durationMs = trs.getLong(8)
            val trkCover = trs.getString(9)
            val hasSynced = trs.getInt(10) == 1

            val songCredits = query(
                """SELECT c.role, p.id, p.name FROM recording_credits c JOIN people p ON p.id = c.person_id
                   WHERE c.recording_id = ? ORDER BY c.role, c.position""",
                recId,
            ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }

            val singers = songCredits.filter { it.first == "singer" }.map { PersonRefDto(it.second, it.third, "singer") }
            val songComposers = songCredits.filter { it.first == "composer" }.map { PersonRefDto(it.second, it.third, "composer") }
                .ifEmpty { composers }
            val lyricists = songCredits.filter { it.first == "lyricist" }.map { PersonRefDto(it.second, it.third, "lyricist") }

            val songStarred = userSongLikes[recId]?.let { Instant.ofEpochMilli(it).toString() }
            val playCount = userSongPlays[recId] ?: 0

            TrackDto(
                id = trkId,
                disc = disc,
                number = num,
                title = trkTitle,
                recording = RecordingDto(
                    id = recId,
                    title = recTitle,
                    version = version,
                    durationMs = durationMs,
                    singers = singers,
                    composers = songComposers,
                    lyricists = lyricists,
                    coverArt = trkCover?.let { "al-$scoreId" },
                    hasSyncedLyrics = hasSynced,
                    playCount = playCount,
                    starred = songStarred,
                ),
            )
        }.distinctBy { it.recording.id }

        AlbumDetailDto(
            id = scoreId,
            title = scoreTitle,
            year = scoreYear,
            kind = "score",
            coverArt = scoreCoverId?.let { "al-$scoreId" },
            composers = composers,
            directors = directors,
            cast = cast,
            starred = starred,
            releases = listOf(
                ReleaseDto(
                    id = scoreId,
                    title = scoreTitle,
                    kind = "score",
                    year = scoreYear,
                    coverArt = scoreCoverId?.let { "al-$scoreId" },
                    tracks = tracks,
                )
            ),
        )
    }

    suspend fun people(
        role: String? = "all",
        query: String? = null,
        offset: Int = 0,
        limit: Int = 100,
    ): PeopleResponse = db.read {
        val people = query(
            """SELECT p.id, p.name,
                      (SELECT count(DISTINCT c.album_id) FROM album_credits c WHERE c.person_id = p.id AND c.role = 'composer'),
                      EXISTS (SELECT 1 FROM recording_credits c WHERE c.person_id = p.id AND c.role = 'singer'),
                      EXISTS (SELECT 1 FROM recording_credits c WHERE c.person_id = p.id AND c.role = 'composer'),
                      EXISTS (SELECT 1 FROM recording_credits c WHERE c.person_id = p.id AND c.role = 'lyricist'),
                      (SELECT count(DISTINCT c.recording_id) FROM recording_credits c WHERE c.person_id = p.id)
               FROM people p
               WHERE p.merged_into IS NULL
               ORDER BY p.name COLLATE NOCASE"""
        ) { rs ->
            val id = rs.getString(1)
            val name = rs.getString(2)
            val composerAlbums = rs.getInt(3)
            val isSinger = rs.getInt(4) == 1
            val isComposer = rs.getInt(5) == 1 || composerAlbums > 0
            val isLyricist = rs.getInt(6) == 1
            val songs = rs.getInt(7)

            val roles = buildList {
                if (isComposer) add("composer")
                if (isSinger) add("singer")
                if (isLyricist) add("lyricist")
            }
            PersonSummaryDto(
                id = id,
                name = name,
                roles = roles,
                coverArt = "ar-$id",
                songCount = songs,
                movieCount = composerAlbums,
            )
        }.filter { p ->
            val matchesRole = when (role?.lowercase()) {
                "composer" -> p.roles.contains("composer")
                "singer" -> p.roles.contains("singer")
                "lyricist" -> p.roles.contains("lyricist")
                else -> p.roles.isNotEmpty()
            }
            val matchesQuery = query.isNullOrBlank() || p.name.contains(query, ignoreCase = true)
            matchesRole && matchesQuery
        }

        val paged = people.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500))
        PeopleResponse(total = people.size, people = paged)
    }

    suspend fun person(user: UserDto, id: String): PersonDetailDto? = db.read {
        val person = queryOne(
            "SELECT id, name FROM people WHERE id = ? AND merged_into IS NULL", id
        ) { it.getString(1) to it.getString(2) } ?: return@read null

        val pId = person.first
        val name = person.second

        val roles = query(
            """SELECT DISTINCT role FROM (
                 SELECT role FROM album_credits WHERE person_id = ?
                 UNION
                 SELECT role FROM recording_credits WHERE person_id = ?
               )""", pId, pId
        ) { it.getString(1) }

        // Albums where this person is composer
        val albumIds = query(
            """SELECT DISTINCT album_id FROM album_credits WHERE person_id = ? AND role = 'composer'
               UNION
               SELECT DISTINCT rl.album_id FROM recording_credits c
               JOIN tracks t ON t.recording_id = c.recording_id
               JOIN releases rl ON rl.id = t.release_id
               WHERE c.person_id = ? AND c.role = 'composer'""", pId, pId
        ) { it.getString(1) }

        val albums = albumIds.mapNotNull { albId ->
            queryOne("SELECT id, title, year, kind, cover_id FROM albums WHERE id = ?", albId) {
                AlbumSummaryDto(
                    id = it.getString(1),
                    title = it.getString(2),
                    year = (it.getObject(3) as Number?)?.toInt(),
                    kind = it.getString(4),
                    coverArt = it.getString(5)?.let { c -> "al-$albId" },
                    composers = listOf(PersonRefDto(pId, name, "composer")),
                )
            }
        }

        // Score releases composed by this person
        val scoreAlbums = query(
            """SELECT DISTINCT rl.id, a.title, coalesce(rl.year, a.year), coalesce(rl.cover_id, a.cover_id)
               FROM releases rl
               JOIN albums a ON a.id = rl.album_id
               JOIN tracks t ON t.release_id = rl.id
               JOIN recording_credits c ON c.recording_id = t.recording_id
               WHERE rl.kind = 'score' AND c.person_id = ? AND c.role = 'composer'""", pId
        ) { rs ->
            val relId = rs.getString(1)
            val parentTitle = rs.getString(2)
            AlbumSummaryDto(
                id = relId,
                title = "$parentTitle (Original Background Score)",
                year = (rs.getObject(3) as Number?)?.toInt(),
                kind = "score",
                coverArt = rs.getString(4)?.let { "al-$relId" },
                composers = listOf(PersonRefDto(pId, name, "composer")),
            )
        }

        val allAlbums = (albums + scoreAlbums).sortedWith(compareByDescending<AlbumSummaryDto> { it.year }.thenBy { it.title })

        // Songs where this person is credited (singer, lyricist, composer)
        val songs = query(
            """SELECT DISTINCT r.id, r.title, r.version, r.duration_ms, a.id, a.title, coalesce(f.cover_id, rl.cover_id, a.cover_id)
               FROM recording_credits c
               JOIN recordings r ON r.id = c.recording_id
               JOIN tracks t ON t.recording_id = r.id
               JOIN releases rl ON rl.id = t.release_id
               JOIN albums a ON a.id = rl.album_id
               JOIN files f ON f.track_id = t.id AND f.missing_since IS NULL
               WHERE c.person_id = ?
               ORDER BY a.year DESC, r.title""", pId
        ) { rs ->
            val recId = rs.getString(1)
            val title = rs.getString(2)
            val ver = rs.getString(3)
            val dur = rs.getLong(4)
            val albId = rs.getString(5)
            val cov = rs.getString(7)

            RecordingDto(
                id = recId,
                title = title,
                version = ver,
                durationMs = dur,
                coverArt = cov?.let { "al-$albId" },
            )
        }.distinctBy { it.id }

        PersonDetailDto(
            id = pId,
            name = name,
            roles = roles,
            coverArt = "ar-$pId",
            songCount = songs.size,
            movieCount = allAlbums.size,
            albums = allAlbums,
            songs = songs,
            movies = allAlbums.filter { it.kind == "film" },
        )
    }

    suspend fun song(user: UserDto, id: String): SongDetailDto? = db.read {
        val rid = resolveRecordingId(id)
        val base = queryOne(
            """SELECT r.id, r.title, r.version, r.duration_ms, a.id, a.title, coalesce(f.cover_id, rl.cover_id, a.cover_id), r.song_id, rl.kind, rl.id
               FROM recordings r
               JOIN tracks t ON t.recording_id = r.id
               JOIN releases rl ON rl.id = t.release_id
               JOIN albums a ON a.id = rl.album_id
               LEFT JOIN files f ON f.track_id = t.id AND f.missing_since IS NULL
               WHERE r.id = ? LIMIT 1""", rid
        ) { rs ->
            val isScore = rs.getString(9) == "score"
            val effectiveAlbumId = if (isScore) rs.getString(10) else rs.getString(5)
            val effectiveAlbumTitle = if (isScore) "${rs.getString(6)} (Original Background Score)" else rs.getString(6)
            SongDetailDto(
                id = rs.getString(1),
                title = rs.getString(2),
                version = rs.getString(3),
                durationMs = rs.getLong(4),
                albumId = effectiveAlbumId,
                albumTitle = effectiveAlbumTitle,
                coverArt = rs.getString(7)?.let { "al-$effectiveAlbumId" },
            ) to rs.getString(8)
        } ?: return@read null

        val songDto = base.first
        val songGroupId = base.second

        val credits = query(
            """SELECT c.role, p.id, p.name FROM recording_credits c JOIN people p ON p.id = c.person_id
               WHERE c.recording_id = ? ORDER BY c.role, c.position""", rid
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }

        val composers = credits.filter { it.first == "composer" }.map { PersonRefDto(it.second, it.third, "composer") }
        val singers = credits.filter { it.first == "singer" }.map { PersonRefDto(it.second, it.third, "singer") }
        val lyricists = credits.filter { it.first == "lyricist" }.map { PersonRefDto(it.second, it.third, "lyricist") }

        val versions = if (songGroupId != null) {
            query(
                "SELECT id, title, version FROM recordings WHERE song_id = ? AND id != ? AND merged_into IS NULL",
                songGroupId, rid
            ) { SongVersionDto(it.getString(1), it.getString(2), it.getString(3)) }
        } else emptyList()

        val lyrics = query(
            "SELECT script, synced, text FROM lyrics WHERE recording_id = ? ORDER BY synced DESC, script = 'ta' DESC", rid
        ) { rs ->
            val script = rs.getString(1)
            val synced = rs.getInt(2) == 1
            val text = rs.getString(3)
            val lines = if (synced) {
                SubsonicLibrary.lrcLines(text).map { LyricLineDto(it.first, it.second) }
            } else {
                text.lines().map { LyricLineDto(0L, it) }
            }
            LyricsDto(script = script, synced = synced, lines = lines)
        }

        val playCount = queryOne("SELECT count FROM play_counts WHERE user_id = ? AND recording_id = ?", user.id, rid) { it.getInt(1) } ?: 0
        val starred = queryOne("SELECT liked_at FROM likes WHERE user_id = ? AND item_type = 'recording' AND item_id = ?", user.id, rid) {
            Instant.ofEpochMilli(it.getLong(1)).toString()
        }

        songDto.copy(
            composers = composers,
            singers = singers,
            lyricists = lyricists,
            versions = versions,
            lyrics = lyrics,
            playCount = playCount,
            starred = starred,
        )
    }

    suspend fun searchLyrics(query: String, limit: Int = 30): LyricsSearchResponse = db.read {
        val q = query.trim()
        if (q.length < 2) return@read LyricsSearchResponse(query, 0, emptyList())
        val pattern = "%$q%"
        val matches = query(
            """SELECT l.recording_id, l.synced, l.text, r.title, a.id, a.title, coalesce(f.cover_id, rl.cover_id, a.cover_id), rl.kind, rl.id
               FROM lyrics l
               JOIN recordings r ON r.id = l.recording_id
               JOIN tracks t ON t.recording_id = r.id
               JOIN releases rl ON rl.id = t.release_id
               JOIN albums a ON a.id = rl.album_id
               LEFT JOIN files f ON f.track_id = t.id AND f.missing_since IS NULL
               WHERE l.text LIKE ?
               LIMIT ?""",
            pattern, limit * 3,
        ) { rs ->
            val recId = rs.getString(1)
            val synced = rs.getInt(2) == 1
            val text = rs.getString(3)
            val songTitle = rs.getString(4)
            val isScore = rs.getString(8) == "score"
            val albumId = if (isScore) rs.getString(9) else rs.getString(5)
            val albumTitle = if (isScore) "${rs.getString(6)} (Original Background Score)" else rs.getString(6)
            val coverId = rs.getString(7)

            val (matchedLine, startMs) = if (synced) {
                val lines = SubsonicLibrary.lrcLines(text)
                val m = lines.firstOrNull { it.second.contains(q, ignoreCase = true) }
                    ?: (lines.firstOrNull() ?: (0L to ""))
                m.second to m.first
            } else {
                val line = text.lines().firstOrNull { it.contains(q, ignoreCase = true) }.orEmpty().trim()
                line to 0L
            }

            LyricsMatchDto(
                recordingId = recId,
                songTitle = songTitle,
                albumId = albumId,
                albumTitle = albumTitle,
                coverArt = coverId?.let { "al-$albumId" },
                matchedLine = matchedLine,
                startMs = startMs,
            )
        }.distinctBy { it.recordingId }.take(limit)

        LyricsSearchResponse(query = query, total = matches.size, matches = matches)
    }

    suspend fun searchUnified(
        user: UserDto,
        query: String,
        searchService: LibrarySearch?,
        lyricsLimit: Int = 10,
    ): UnifiedSearchResponse {
        val base = searchService?.search(query) ?: SearchResults()
        val lyrics = searchLyrics(query, lyricsLimit).matches
        return UnifiedSearchResponse(
            query = query,
            people = base.people,
            movies = base.movies,
            songs = base.songs,
            lyrics = lyrics,
        )
    }

    fun routes(route: Route, searchService: LibrarySearch?) = route.route("/api/v2") {
        route("/albums") {
            get {
                val q = call.request.queryParameters
                call.respond(
                    albums(
                        user = call.me(),
                        sort = q["sort"] ?: "name",
                        kind = q["kind"],
                        fromYear = q["fromYear"]?.toIntOrNull(),
                        toYear = q["toYear"]?.toIntOrNull(),
                        offset = q["offset"]?.toIntOrNull() ?: 0,
                        limit = q["limit"]?.toIntOrNull() ?: 50,
                    )
                )
            }
            get("/{id}") {
                val id = call.parameters["id"].orEmpty()
                val album = album(call.me(), id) ?: throw ApiError(HttpStatusCode.NotFound, "Album not found")
                call.respond(album)
            }
        }

        route("/people") {
            get {
                val q = call.request.queryParameters
                call.respond(
                    people(
                        role = q["role"] ?: "all",
                        query = q["q"],
                        offset = q["offset"]?.toIntOrNull() ?: 0,
                        limit = q["limit"]?.toIntOrNull() ?: 100,
                    )
                )
            }
            get("/{id}") {
                val id = call.parameters["id"].orEmpty()
                val person = person(call.me(), id) ?: throw ApiError(HttpStatusCode.NotFound, "Person not found")
                call.respond(person)
            }
        }

        route("/songs") {
            get("/{id}") {
                val id = call.parameters["id"].orEmpty()
                val song = song(call.me(), id) ?: throw ApiError(HttpStatusCode.NotFound, "Song not found")
                call.respond(song)
            }
            get("/{id}/stream") {
                val id = call.parameters["id"].orEmpty()
                val quality = call.request.queryParameters["quality"] ?: "auto"
                val (file, type) = audio(id, quality) ?: throw ApiError(HttpStatusCode.NotFound, "Audio file not found")
                call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=86400")
                call.respond(LocalFileContent(file, ContentType.parse(type)))
            }
        }

        route("/search") {
            get {
                val q = call.request.queryParameters["q"].orEmpty()
                call.respond(searchUnified(call.me(), q, searchService))
            }
            get("/lyrics") {
                val q = call.request.queryParameters["q"].orEmpty()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
                call.respond(searchLyrics(q, limit))
            }
        }
    }
}

private fun ApplicationCall.me(): UserDto = principal<UserDto>() ?: throw ApiError(HttpStatusCode.Unauthorized, "Not logged in")
