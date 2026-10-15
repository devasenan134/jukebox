package io.github.devasenan134.jukebox.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.abs

private val log = LoggerFactory.getLogger("requests")

/** A song or a movie's album from the music catalog (iTunes), which may not be in the library. */
@Serializable
data class CatalogItem(
    /** "itunes-song-123" or "itunes-movie-456". */
    val id: String,
    /** "song" or "movie". */
    val kind: String,
    val title: String,
    /** The movie a song is from (for a movie, its own name). */
    val movie: String,
    val year: Int? = null,
    /** A song's singers; a movie's composer. */
    val artist: String? = null,
    val artworkUrl: String? = null,
    /** How many songs a movie's album has. */
    val trackCount: Int = 0,
    val durationMs: Long? = null,
    /** A song found on a compilation rather than its movie's own album. */
    @Transient val compilation: Boolean = false,
)

@Serializable
data class MusicRequestDto(
    val id: Long,
    val item: CatalogItem,
    /** "open", "done" (it's in the library) or "declined" (it couldn't be found). */
    val status: String,
    val requestedAt: Long,
    val closedAt: Long? = null,
    /** Why it was declined, if the admin said. */
    val note: String? = null,
    /** Where it is in the library once done: the movie, and for a song the song itself. */
    val albumId: String? = null,
    val songId: String? = null,
    /** Whether the caller asked for it. */
    val mine: Boolean = false,
    /** Everyone who asked for it, by name. */
    val askedBy: List<String> = emptyList(),
    /**
     * While it's being filled (status still "open"): "review" (downloaded, waiting for an admin's check),
     * "approved" (being moved into the library) or "rejected" (being removed; then null again).
     */
    val stage: String? = null,
    /** What was downloaded, for admins to check. */
    val review: RequestReview? = null,
    /** Why the last check was rejected (admins only). */
    val reviewNote: String? = null,
)

/** A fulfilled request's download, written by the fulfil script, waiting in REQUESTS_DIR/<request id>/. */
@Serializable
data class RequestReview(
    val album: String,
    val year: Int? = null,
    val composer: String? = null,
    val lyricists: String? = null,
    val coverUrl: String? = null,
    /** Where it came from (the matched page), and how sure the matcher was. */
    val source: String? = null,
    val score: Double? = null,
    /** "existing": joins [destinationAlbumId] in the library; "new": a new album folder. */
    val destination: String = "new",
    val destinationAlbumId: String? = null,
    val destinationFolder: String? = null,
    /** The new folder the script suggested, to go back to after picking an album. */
    val suggestedFolder: String? = null,
    /** An admin picked the destination: the script files it there, without looking again. */
    val destinationChosen: Boolean = false,
    /** Where tracks marked [ReviewTrack.score] go (the album's "(Original Background Score)" folder). */
    val scoreFolder: String? = null,
    val tracks: List<ReviewTrack> = emptyList(),
    /** Songs the album already has in the library, left out of the download. */
    val skipped: List<String> = emptyList(),
    /** A song whose album is filled by another request in the same batch: it comes with that one. */
    val coveredBy: Long? = null,
    /** Anything the script wants the admin to know ("320 kbps link failed, 128 kbps used"). */
    val notes: List<String> = emptyList(),
)

@Serializable
data class ReviewTrack(
    val n: Int,
    /** The file's name in the request's folder. */
    val file: String,
    val title: String,
    val singers: String? = null,
    val durationMs: Long? = null,
    /** kbps. */
    val bitrate: Int? = null,
    /** "synced", "plain" or "none". */
    val lyrics: String = "none",
    /** Background score (theme music, BGM): filed under the album's background score. */
    val score: Boolean = false,
)

@Serializable data class RejectReview(val note: String? = null)

@Serializable data class ReviewsReady(val ids: List<Long>)

/** A catalog result, with its request if someone already asked for it. */
@Serializable
data class CatalogHit(val item: CatalogItem, val request: MusicRequestDto? = null)

/** Catalog results that aren't in the library. */
@Serializable
data class CatalogResults(val movies: List<CatalogHit> = emptyList(), val songs: List<CatalogHit> = emptyList())

@Serializable data class NewMusicRequest(val id: String)

@Serializable data class DeclineMusicRequest(val note: String? = null)

/** "Added", optionally saying where: for music filed by a script that knows the album it just filled. */
@Serializable data class CompleteMusicRequest(val albumId: String? = null, val songId: String? = null)

/**
 * Where a request's album is in the library (for the fulfil script, docs/request-fulfilment.md): the album,
 * the song if asked for, and the folder its files are in, as `library` (the name in LIBRARIES) and a path
 * inside it, with one of its files to copy album tags from.
 */
@Serializable
data class RequestPlace(
    val albumId: String,
    val album: String,
    val year: Int? = null,
    val songId: String? = null,
    val library: String? = null,
    val folder: String? = null,
    val sampleFile: String? = null,
    /** The album's songs, so a download can leave out the ones it already has. */
    val songs: List<String> = emptyList(),
    /** It's a background score's own entry, not the film's songs. */
    val score: Boolean = false,
)

/** An admin picks where a download goes: an album in the library, or (null) a new album folder. */
@Serializable data class ReviewDestination(val albumId: String? = null)

/** Where songs and movies that aren't in the library are found. Tests use a pretend one. */
interface Catalog {
    /** Songs and movies for [query], best first. Empty if the catalog can't be reached. */
    suspend fun search(query: String): List<CatalogItem>

    /** One item by its [CatalogItem.id]. */
    suspend fun lookup(id: String): CatalogItem?

    /** Whether it's making no more calls for now (so an empty answer may only mean "not now"). */
    suspend fun busy(): Boolean = false
}

/**
 * The iTunes Search API (free, no key; the Indian store knows Tamil film music well). It allows
 * about 20 calls a minute, so answers are kept for a few hours and calls beyond that are skipped.
 */
class ITunesCatalog(private val http: HttpClient = HttpClient(CIO)) : Catalog {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val searches = object : LinkedHashMap<String, Pair<Long, List<CatalogItem>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<CatalogItem>>>) = size > 300
    }
    private val items = object : LinkedHashMap<String, CatalogItem>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CatalogItem>) = size > 5_000
    }
    private val calls = ArrayDeque<Long>()

    override suspend fun search(query: String): List<CatalogItem> {
        val key = Fuzzy.words(query).joinToString(" ")
        mutex.withLock { searches[key]?.takeIf { now() - it.first < KEEP_MS }?.let { return it.second } }
        val found = coroutineScope {
            val songs = async { call("search", "term" to query, "entity" to "song", "limit" to "40") }
            val albums = async { call("search", "term" to query, "entity" to "album", "limit" to "15") }
            val results = listOf(albums.await(), songs.await())
            if (results.any { it == null }) null else results.flatMap { it!! }
        } ?: return emptyList()
        mutex.withLock {
            searches[key] = now() to found
            found.forEach { items[it.id] = it }
        }
        return found
    }

    override suspend fun lookup(id: String): CatalogItem? {
        mutex.withLock { items[id] }?.let { return it }
        val number = id.substringAfterLast('-').toLongOrNull() ?: return null
        return call("lookup", "id" to number.toString())?.firstOrNull { it.id == id }
    }

    override suspend fun busy(): Boolean = mutex.withLock {
        val t = now()
        while (calls.isNotEmpty() && calls.first() < t - 60_000) calls.removeFirst()
        calls.size >= MAX_CALLS_PER_MINUTE
    }

    /** One call to the API, or null if it failed or the minute's calls are used up. */
    private suspend fun call(path: String, vararg params: Pair<String, String>): List<CatalogItem>? {
        val allowed = mutex.withLock {
            val t = now()
            while (calls.isNotEmpty() && calls.first() < t - 60_000) calls.removeFirst()
            (calls.size < MAX_CALLS_PER_MINUTE).also { if (it) calls.addLast(t) }
        }
        if (!allowed) {
            log.info("Catalog: skipping a call, too many this minute")
            return null
        }
        return withTimeoutOrNull(8_000) {
            runCatching {
                val response = http.get("https://itunes.apple.com/$path") {
                    parameter("country", "IN")
                    parameter("media", "music")
                    params.forEach { (k, v) -> parameter(k, v) }
                }
                if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
                json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]!!.jsonArray.mapNotNull { parse(it.jsonObject) }
            }.onFailure { log.warn("Catalog call failed: ${it.message}") }.getOrNull()
        }
    }

    companion object {
        private const val KEEP_MS = 6 * 60 * 60 * 1000L
        private const val MAX_CALLS_PER_MINUTE = 16
        private val FROM = Regex("""\s*[(\[]From\s+["“”']([^"“”']+)["“”']\s*[)\]]""", RegexOption.IGNORE_CASE)
        private val SOUNDTRACK = Regex("""\s*[(\[](?:Original\s+)?(?:Motion\s+Picture\s+|Movie\s+)?Sound\s*track[)\]]""", RegexOption.IGNORE_CASE)
        private val EP = Regex("""\s+-\s+(?:EP|Single)$""", RegexOption.IGNORE_CASE)
        private val FILM = Regex("""Sound\s*track|Motion Picture|Background Score""", RegexOption.IGNORE_CASE)
        /** Words of compilations ("Kollywood's Top 10 Hits"), whose songs come from many movies. */
        private val COMPILATION = setOf(
            "hits", "top", "best", "songs", "collection", "beats", "jukebox", "vol", "volume", "essentials", "playlist",
            "mashup", "lofi", "birthday", "special", "melodies", "favourites", "favorites", "greatest", "classics",
            "nonstop", "medley", "vibes", "mix", "hour",
        )

        /** "Kanave Kanave (From "David")" → "Kanave Kanave". */
        fun cleanTitle(title: String) = title.replace(FROM, "").replace(SOUNDTRACK, "").replace(EP, "").trim()

        /** "David (Original Motion Picture Soundtrack) - EP" → "David". */
        fun movieName(album: String) = album.replace(SOUNDTRACK, "").replace(EP, "").trim()

        /** Whether an album is one movie's music, rather than a compilation or a single. */
        fun isMovieAlbum(album: String, albumArtist: String? = null): Boolean {
            if (FROM.containsMatchIn(album)) return false
            if (FILM.containsMatchIn(album)) return true
            if (albumArtist.equals("Various Artists", ignoreCase = true)) return false
            val words = album.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            return words.size in 1..4 && words.none { it in COMPILATION || it.all(Char::isDigit) }
        }

        fun parse(r: JsonObject): CatalogItem? {
            fun text(key: String) = r[key]?.jsonPrimitive?.contentOrNull
            fun number(key: String) = r[key]?.jsonPrimitive?.longOrNull
            val year = text("releaseDate")?.take(4)?.toIntOrNull()
            val art = text("artworkUrl100")?.replace("100x100bb", "600x600bb")
            val album = text("collectionName").orEmpty()
            return when (text("wrapperType")) {
                "track" -> {
                    if (text("kind") != "song") return null
                    val title = text("trackName") ?: return null
                    // A song on a compilation says which movie it's from; otherwise the album is the movie.
                    val onMovieAlbum = isMovieAlbum(album, text("collectionArtistName"))
                    val movie = FROM.find(title)?.groupValues?.get(1)?.trim()
                        ?: movieName(album).takeIf { onMovieAlbum }
                        ?: return null
                    CatalogItem(
                        "itunes-song-${number("trackId") ?: return null}", "song", cleanTitle(title), movie, year,
                        text("artistName"), art, durationMs = number("trackTimeMillis"), compilation = !onMovieAlbum,
                    )
                }
                "collection" -> {
                    if (!isMovieAlbum(album, text("artistName"))) return null
                    CatalogItem(
                        "itunes-movie-${number("collectionId") ?: return null}", "movie", movieName(album), movieName(album), year,
                        text("artistName"), art, trackCount = number("trackCount")?.toInt() ?: 0,
                    )
                }
                else -> null
            }
        }
    }
}

/**
 * Asking for music that isn't in the library. Search shows catalog songs and movies the library
 * doesn't have; anyone can request one, and admins are told. Once the music is
 * added, an admin marks it done and everyone who asked gets a notification, or marks it declined
 * if it can't be found.
 */
class MusicRequests(
    private val db: Db,
    private val music: MusicSource,
    private val catalog: Catalog,
    private val push: Push,
    private val isAdmin: suspend (UserDto) -> Boolean,
    private val adminIds: suspend () -> List<Long>,
    /** Where the fulfil script leaves downloads for checking (read-only here); null: reviews can't be played. */
    private val requestsDir: File? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    @Volatile private var shelf: Shelf? = null

    /** Catalog songs and movies for [query] that the library doesn't have. */
    suspend fun search(me: UserDto, query: String): CatalogResults {
        val q = query.trim()
        if (q.length < 2 || Fuzzy.words(q).isEmpty()) return CatalogResults()
        val library = shelf() ?: return CatalogResults()
        // The same song is often in the catalog several times (its movie's album and compilations): keep one,
        // the movie's own if there is one.
        // The catalog also matches words deep inside an album, so keep what the search is really about:
        // the song, its movie or its artist. Covers and "slowed" versions aren't what people look for.
        val words = Fuzzy.words(q)
        val found = catalog.search(q)
            .filter { item -> listOf(item.title, item.movie, item.artist.orEmpty()).any { Fuzzy.match(words, Fuzzy.words(it)) > 0 } }
            .filterNot { VERSION.containsMatchIn(it.title) }
            .groupBy(::matchKey).values
            .map { same -> same.firstOrNull { !it.compilation } ?: same.first() }
            .filter { library.find(it) == null }
        val requests = db.tx { requestsByKey(found.map(::matchKey), me.id) }
        // A request that was filled is in the library, even when the library calls it something else (a BGM album
        // filed as its film's background score): it isn't offered again.
        fun hits(kind: String, max: Int) = found.filter { it.kind == kind && requests[matchKey(it)]?.status != "done" }.take(max)
            .map { CatalogHit(it, requests[matchKey(it)]) }
        return CatalogResults(movies = hits("movie", 10), songs = hits("song", 20))
    }

    suspend fun request(me: UserDto, catalogId: String): MusicRequestDto {
        val item = catalog.lookup(catalogId.take(100)) ?: throw notFoundOrBusy()
        if (shelf()?.find(item) != null) throw ApiError(HttpStatusCode.Conflict, "It's already in the library")
        val key = matchKey(item)
        val (id, isNew) = db.tx {
            val existing = queryOne("SELECT id, status FROM music_requests WHERE match_key = ?", key) { it.getLong(1) to it.getString(2) }
            val itemJson = json.encodeToString(CatalogItem.serializer(), item)
            val id = when {
                existing == null -> insert(
                    "INSERT INTO music_requests (match_key, catalog_id, item_json, status, created_at) VALUES (?, ?, ?, 'open', ?)", key, item.id, itemJson, now(),
                )
                // Asked for again after it was declined (or removed from the library): open it again.
                existing.second != "open" -> existing.first.also {
                    update(
                        "UPDATE music_requests SET status = 'open', catalog_id = ?, item_json = ?, note = NULL, closed_at = NULL, album_id = NULL, song_id = NULL, created_at = ? WHERE id = ?",
                        item.id, itemJson, now(), it,
                    )
                }
                else -> existing.first
            }
            update("INSERT OR IGNORE INTO music_request_askers (request_id, user_id, asked_at) VALUES (?, ?, ?)", id, me.id, now())
            id to (existing?.second != "open")
        }
        if (isNew) {
            log.info("${me.username} requested ${label(item)}")
            push.musicRequested(adminIds().filter { it != me.id }, me, label(item))
        }
        return db.tx { dto(id, me.id) }!!
    }

    /** Adds [me] to request [id] (someone asked for the same music already); null if it was declined or is gone. */
    suspend fun join(me: UserDto, id: Long): MusicRequestDto? = db.tx {
        val status = queryOne("SELECT status FROM music_requests WHERE id = ?", id) { it.getString(1) }
        if (status == null || status == "declined") return@tx null
        update("INSERT OR IGNORE INTO music_request_askers (request_id, user_id, asked_at) VALUES (?, ?, ?)", id, me.id, now())
        dto(id, me.id)
    }

    /** Whether the catalog is out of calls for now. */
    suspend fun catalogBusy(): Boolean = catalog.busy()

    /** The catalog didn't have it, or (when it's making no more calls for now) couldn't be asked. */
    suspend fun notFoundOrBusy(what: String = "Couldn't find that in the catalog"): ApiError =
        if (catalog.busy()) ApiError(HttpStatusCode.ServiceUnavailable, CATALOG_BUSY) else ApiError(HttpStatusCode.NotFound, what)

    /** Takes back your request; one nobody wants any more goes away. */
    suspend fun cancel(me: UserDto, id: Long) = db.tx {
        update("DELETE FROM music_request_askers WHERE request_id = ? AND user_id = ?", id, me.id)
        update("DELETE FROM music_requests WHERE id = ? AND status = 'open' AND NOT EXISTS (SELECT 1 FROM music_request_askers WHERE request_id = ?)", id, id)
    }

    /** The fulfil script hands in a download for checking. */
    suspend fun submitReview(me: UserDto, id: Long, review: RequestReview): MusicRequestDto {
        requireAdmin(me)
        if (review.tracks.any { !SAFE_FILE.matches(it.file) }) throw ApiError(HttpStatusCode.BadRequest, "Bad file name")
        val changed = db.tx {
            update(
                "UPDATE music_requests SET stage = 'review', review_json = ?, review_note = NULL, staged_at = ? WHERE id = ? AND status = 'open'",
                json.encodeToString(RequestReview.serializer(), review), now(), id,
            )
        }
        if (changed == 0) throw ApiError(HttpStatusCode.Conflict, "That request isn't open")
        return db.tx { dto(id, me.id) }!!
    }

    /** After a batch is handed in: one notification to the admins, not one per request. */
    suspend fun reviewsReady(me: UserDto, ids: List<Long>) {
        requireAdmin(me)
        val waiting = db.tx { ids.distinct().mapNotNull { dto(it, me.id) }.filter { it.stage == "review" && it.review?.coveredBy == null } }
        if (waiting.isEmpty()) return
        push.musicToCheck(adminIds(), waiting.size, label(waiting.first().item))
    }

    /** An admin listened and it's right: the fulfil script moves it into the library. Songs that come with it go too. */
    suspend fun approve(me: UserDto, id: Long): MusicRequestDto = decide(me, id, "approved", null)

    /** It's wrong: the fulfil script deletes the download and the request waits again, with the [note]. */
    suspend fun reject(me: UserDto, id: Long, note: String?): MusicRequestDto = decide(me, id, "rejected", note?.trim()?.take(200)?.takeIf { it.isNotEmpty() })

    private suspend fun decide(me: UserDto, id: Long, stage: String, note: String?): MusicRequestDto {
        requireAdmin(me)
        db.tx {
            val changed = update("UPDATE music_requests SET stage = ?, review_note = ? WHERE id = ? AND status = 'open' AND stage = 'review'", stage, note, id)
            if (changed == 0) throw ApiError(HttpStatusCode.Conflict, "That request isn't waiting for a check")
            update(
                "UPDATE music_requests SET stage = ?, review_note = ? WHERE status = 'open' AND stage = 'review' AND json_extract(review_json, '$.coveredBy') = ?",
                stage, note, id,
            )
        }
        log.info("${me.username} $stage request $id")
        return db.tx { dto(id, me.id) }!!
    }

    /** The fulfil script removed a rejected download: the request is plainly waiting again. */
    suspend fun clearReview(me: UserDto, id: Long): MusicRequestDto {
        requireAdmin(me)
        db.tx { update("UPDATE music_requests SET stage = NULL, review_json = NULL, staged_at = NULL WHERE id = ?", id) }
        return db.tx { dto(id, me.id) } ?: throw ApiError(HttpStatusCode.NotFound, "No such request")
    }

    /** Track [n] of a download waiting for a check, to listen to. */
    suspend fun reviewFile(me: UserDto, id: Long, n: Int): File {
        requireAdmin(me)
        val dir = requestsDir ?: throw ApiError(HttpStatusCode.NotFound, "Downloads can't be played on this server")
        val review = db.tx { dto(id, me.id) }?.review ?: throw ApiError(HttpStatusCode.NotFound, "Nothing to check")
        val track = review.tracks.firstOrNull { it.n == n } ?: throw ApiError(HttpStatusCode.NotFound, "No such track")
        return File(File(dir, id.toString()), track.file).takeIf { it.isFile } ?: throw ApiError(HttpStatusCode.NotFound, "The file is gone")
    }

    /** Your requests: waiting ones first, then ones answered in the last 90 days. */
    suspend fun mine(me: UserDto): List<MusicRequestDto> = repaired(db.tx {
        query(
            """SELECT r.id FROM music_requests r JOIN music_request_askers a ON a.request_id = r.id
               WHERE a.user_id = ? AND (r.status = 'open' OR r.closed_at > ?)
               ORDER BY r.status = 'open' DESC, coalesce(r.closed_at, r.created_at) DESC""",
            me.id, now() - RECENT_MS,
        ) { it.getLong(1) }.mapNotNull { dto(it, me.id)?.copy(review = null, reviewNote = null) }
    })

    /** These requests as [me] sees them (whether they asked too), by id; ones that are gone are left out. */
    suspend fun byIds(me: UserDto, ids: List<Long>): Map<Long, MusicRequestDto> =
        if (ids.isEmpty()) emptyMap()
        else repaired(db.tx { ids.distinct().mapNotNull { dto(it, me.id)?.copy(review = null, reviewNote = null) } }).associateBy { it.id }

    /** For admins: everything waiting (oldest first), then what was answered in the last 30 days. */
    suspend fun all(me: UserDto): List<MusicRequestDto> {
        requireAdmin(me)
        return repaired(db.tx {
            query(
                """SELECT id FROM music_requests WHERE status = 'open' OR closed_at > ?
                   ORDER BY status = 'open' DESC, CASE WHEN status = 'open' THEN created_at ELSE -closed_at END""",
                now() - ADMIN_RECENT_MS,
            ) { it.getLong(1) }.mapNotNull { dto(it, me.id) }
        })
    }

    /**
     * Where the album a request is for is in the library, if it is. The catalog's year and artist can be a
     * re-release's ("Anbe Aaruyire", 2013, "A.R. Rahman & Vaali" for the 2005 film), so the caller gives the
     * real [year] and [composer], and one of them has to agree: a same-named film from another year by
     * someone else is another film. [title] is the song to look for (default: the requested song).
     */
    suspend fun place(
        me: UserDto, id: Long, year: Int?, composer: String?, title: String?, albumId: String? = null, movie: String? = null, score: Boolean = false,
    ): RequestPlace {
        requireAdmin(me)
        val request = db.tx { dto(id, me.id) } ?: throw ApiError(HttpStatusCode.NotFound, "No such request")
        val library = shelf() ?: throw ApiError(HttpStatusCode.ServiceUnavailable, "The library isn't loaded yet")
        // [movie]: the album's name when the request's isn't it ("Remo Special (Original Background Score…)" is Remo's score).
        val movie = (if (albumId != null) library.album(albumId) else library.findAlbum(movie?.takeIf { it.isNotBlank() } ?: request.item.movie, year, composer, score))
            ?: throw ApiError(HttpStatusCode.NotFound, "That album isn't in the library")
        val songTitle = title?.takeIf { it.isNotBlank() } ?: request.item.title.takeIf { request.item.kind == "song" }
        val songId = songTitle?.let { t -> Fuzzy.words(t).let { words -> movie.songs.firstOrNull { same(words, it.second) }?.first } }
        // The folder most of its files are in (a disc or two can sit in subfolders).
        val files = db.read {
            query(
                """SELECT l.name, f.path, r.kind = 'score' FROM files f JOIN tracks t ON t.id = f.track_id JOIN releases r ON r.id = t.release_id
                   JOIN libraries l ON l.id = f.library_id WHERE (r.album_id = ? OR r.id = ?) AND f.missing_since IS NULL ORDER BY f.path""",
                // A background score is listed by its release's id.
                movie.id, movie.id,
            ) { Triple(it.getString(1), it.getString(2), it.getBoolean(3)) }
        }
        // The songs' folder rather than the background score's (Remo's score has more files than its songs);
        // for a background score itself, its own folder.
        val songs = files.filterNot { it.third }.ifEmpty { files }
        val folder = songs.groupBy { (lib, path) -> lib to path.substringBeforeLast('/', "") }.maxByOrNull { it.value.size }
        return RequestPlace(
            albumId = movie.id,
            album = movie.title,
            year = movie.year.takeIf { it > 0 },
            songId = songId,
            library = folder?.key?.first,
            folder = folder?.key?.second,
            sampleFile = folder?.value?.first()?.second,
            songs = movie.songTitles,
            score = movie.isScore,
        )
    }

    /** An admin picks where a download waiting for a check goes: [albumId] in the library, or a new album (null). */
    suspend fun setDestination(me: UserDto, id: Long, albumId: String?): MusicRequestDto {
        requireAdmin(me)
        val request = db.tx { dto(id, me.id) } ?: throw ApiError(HttpStatusCode.NotFound, "No such request")
        val review = request.review?.takeIf { request.stage == "review" } ?: throw ApiError(HttpStatusCode.Conflict, "That request isn't waiting for a check")
        val suggested = review.suggestedFolder ?: review.destinationFolder.takeIf { review.destination == "new" }
        val changed = if (albumId == null) {
            review.copy(destination = "new", destinationAlbumId = null, destinationFolder = suggested, suggestedFolder = suggested, destinationChosen = true)
        } else {
            val place = place(me, id, null, null, null, albumId)
            if (place.folder == null) throw ApiError(HttpStatusCode.Conflict, "That album's files can't be found")
            review.copy(destination = "existing", destinationAlbumId = place.albumId, destinationFolder = place.folder, suggestedFolder = suggested, destinationChosen = true)
        }
        db.tx { update("UPDATE music_requests SET review_json = ? WHERE id = ?", json.encodeToString(RequestReview.serializer(), changed), id) }
        return db.tx { dto(id, me.id) }!!
    }

    /**
     * Requests closed before the library's ids changed (moving off Navidrome) point at albums that no longer exist,
     * so tapping them did nothing: find their album again and save it.
     */
    private suspend fun repaired(list: List<MusicRequestDto>): List<MusicRequestDto> {
        if (list.none { it.status == "done" }) return list
        val library = shelf() ?: return list
        return list.map { r ->
            if (r.status != "done" || (r.albumId != null && library.place(r.albumId, null) != null)) return@map r
            val place = library.find(r.item) ?: return@map r
            db.tx { update("UPDATE music_requests SET album_id = ?, song_id = ? WHERE id = ?", place.albumId, place.songId, r.id) }
            r.copy(albumId = place.albumId, songId = place.songId)
        }
    }

    /**
     * An admin added the music: it must be in the library now. Everyone who asked is told. With [where], the
     * caller says which album (and song) it is, for when the catalog's details don't find it on their own.
     */
    suspend fun complete(me: UserDto, id: Long, where: CompleteMusicRequest? = null): MusicRequestDto {
        requireAdmin(me)
        val request = db.tx { dto(id, me.id) } ?: throw ApiError(HttpStatusCode.NotFound, "No such request")
        val library = shelf()
        val place = (if (where?.albumId != null) library?.place(where.albumId, where.songId) else library?.find(request.item))
            ?: throw ApiError(HttpStatusCode.Conflict, "It isn't in the library yet. New files are picked up at the next scan; try again then")
        val askers = db.tx {
            update(
                "UPDATE music_requests SET status = 'done', album_id = ?, song_id = ?, note = NULL, closed_at = ?, stage = NULL, review_json = NULL WHERE id = ?",
                place.albumId, place.songId, if (request.status == "done") request.closedAt ?: now() else now(), id,
            )
            askers(id)
        }
        // Pointing a closed request at the right album isn't news to the people who asked.
        if (request.status != "done") push.musicReady(askers, label(request.item), place.albumId)
        return db.tx { dto(id, me.id) }!!
    }

    /** An admin closed it with the wrong album: it waits again, as if never answered (nobody is told). */
    suspend fun reopen(me: UserDto, id: Long): MusicRequestDto {
        requireAdmin(me)
        val changed = db.tx {
            update(
                "UPDATE music_requests SET status = 'open', album_id = NULL, song_id = NULL, note = NULL, closed_at = NULL, stage = NULL, review_json = NULL WHERE id = ? AND status != 'open'",
                id,
            )
        }
        if (changed == 0) throw ApiError(HttpStatusCode.Conflict, "That request is already open")
        log.info("${me.username} reopened request $id")
        return db.tx { dto(id, me.id) }!!
    }

    /** An admin couldn't find it. Everyone who asked is told, with the admin's [note] if any. */
    suspend fun decline(me: UserDto, id: Long, note: String?): MusicRequestDto {
        requireAdmin(me)
        val request = db.tx { dto(id, me.id) } ?: throw ApiError(HttpStatusCode.NotFound, "No such request")
        val why = note?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
        val askers = db.tx {
            update(
                "UPDATE music_requests SET status = 'declined', note = ?, album_id = NULL, song_id = NULL, closed_at = ?, stage = NULL, review_json = NULL WHERE id = ?",
                why, now(), id,
            )
            askers(id)
        }
        push.musicDeclined(askers, label(request.item), why)
        return db.tx { dto(id, me.id) }!!
    }

    private suspend fun requireAdmin(me: UserDto) {
        if (!isAdmin(me)) throw ApiError(HttpStatusCode.Forbidden, "Only admins can answer requests")
    }

    private fun java.sql.Connection.askers(id: Long) = query("SELECT user_id FROM music_request_askers WHERE request_id = ?", id) { it.getLong(1) }

    private fun java.sql.Connection.dto(id: Long, userId: Long): MusicRequestDto? {
        val askers = query(
            "SELECT u.id, u.display_name FROM music_request_askers a JOIN users u ON u.id = a.user_id WHERE a.request_id = ? ORDER BY a.asked_at", id,
        ) { it.getLong(1) to it.getString(2) }
        return queryOne("SELECT item_json, status, created_at, closed_at, note, album_id, song_id, stage, review_json, review_note FROM music_requests WHERE id = ?", id) {
            MusicRequestDto(
                id = id,
                item = json.decodeFromString(CatalogItem.serializer(), it.getString(1)),
                status = it.getString(2),
                requestedAt = it.getLong(3),
                closedAt = (it.getObject(4) as Number?)?.toLong(),
                note = it.getString(5),
                albumId = it.getString(6),
                songId = it.getString(7),
                mine = askers.any { (asker, _) -> asker == userId },
                askedBy = askers.map { (_, name) -> name },
                stage = it.getString(8),
                review = it.getString(9)?.let { r -> runCatching { json.decodeFromString(RequestReview.serializer(), r) }.getOrNull() },
                reviewNote = it.getString(10),
            )
        }
    }

    private fun java.sql.Connection.requestsByKey(keys: List<String>, userId: Long): Map<String, MusicRequestDto> {
        if (keys.isEmpty()) return emptyMap()
        val distinct = keys.distinct()
        return query("SELECT id, match_key FROM music_requests WHERE match_key IN (${distinct.joinToString { "?" }})", *distinct.toTypedArray()) {
            it.getLong(1) to it.getString(2)
        }.mapNotNull { (id, key) -> dto(id, userId)?.let { key to it } }.toMap()
    }

    private suspend fun shelf(): Shelf? = mutex.withLock {
        val snapshot = music.snapshot() ?: return null
        shelf?.takeIf { it.version == snapshot.version } ?: Shelf(snapshot).also { shelf = it }
    }

    /** Where a song or movie is in the library. [songId] is null for a movie. */
    data class Place(val albumId: String, val songId: String?)

    /** The library's movies and songs, ready to compare with catalog items. */
    private class Shelf(snapshot: LibrarySnapshot) {
        val version = snapshot.version

        class Movie(
            val id: String, val title: String, val words: List<String>, val year: Int, val composer: List<String>,
            val songs: List<Pair<String, List<String>>>, val songTitles: List<String>,
            /** A background score (its own entry in the library), not the film's songs. */
            val isScore: Boolean = false,
        ) {
            /** The film's name as words, without words about the album's kind ("(Original Background Score)"). */
            val core = movieWords(title)
        }

        val movies = snapshot.byAlbum.map { (id, list) ->
            val songs = list.map { snapshot.songs[it] }.filter { !it.karaoke }
            val first = snapshot.songs[list.first()]
            Movie(
                id, first.album, Fuzzy.words(first.album), list.maxOf { snapshot.songs[it].year }, Fuzzy.words(first.composer?.name.orEmpty()),
                songs.map { it.id to Fuzzy.words(it.title) }, songs.map { it.title }.distinct(),
                isScore = list.all { snapshot.songs[it].score },
            )
        }
        private val byId = movies.associateBy { it.id }

        /** The album called [title] from [year] (±1) or by [composer]; the closest year first. */
        fun findAlbum(title: String, year: Int?, composer: String?, score: Boolean = false): Movie? {
            val words = movieWords(title)
            val by = Fuzzy.words(composer.orEmpty())
            // The film's songs aren't its background score: a songs album is only found as songs. For a score, its
            // own entry if the library has one, else the songs album it goes beside.
            return movies.filter { same(words, it.core) && (score || !it.isScore) }
                .sortedBy { if (score && it.isScore) 0 else 1 }
                .filter { m -> (year != null && m.year > 0 && abs(m.year - year) <= 1) || same(by, m.composer) || composedBy(m.composer, by) }
                .minByOrNull { m -> if (year != null && m.year > 0) abs(m.year - year) else 99 }
        }

        fun album(albumId: String): Movie? = byId[albumId]

        /** [albumId] (and [songId] on it), if they're in the library. */
        fun place(albumId: String, songId: String?): Place? {
            val movie = byId[albumId] ?: return null
            if (songId != null && movie.songs.none { it.first == songId }) return null
            return Place(albumId, songId)
        }

        fun find(item: CatalogItem): Place? {
            val words = movieWords(item.movie)
            // Asked for the songs: the score alone doesn't have them. Asked for the score: only a score is it.
            val wantScore = isScoreTitle(item.movie) || (item.kind == "song" && isScoreTitle(item.title))
            val candidates = movies.filter { same(words, it.core) && it.isScore == wantScore }
            if (item.kind == "movie") {
                // A remake has the same name: the year tells them apart, or, when the catalog's year is a re-release's
                // ("Pichaikkaran", 2021 for the 2016 film), the library's composer being among the catalog's artists.
                val artist = Fuzzy.words(item.artist.orEmpty())
                val movie = candidates.firstOrNull { m ->
                    item.year == null || m.year <= 0 || abs(m.year - item.year) <= 1 || composedBy(m.composer, artist)
                } ?: return null
                return Place(movie.id, null)
            }
            val title = Fuzzy.words(item.title)
            for (m in candidates) {
                m.songs.firstOrNull { same(title, it.second) }?.let { return Place(m.id, it.first) }
            }
            return null
        }
    }

    companion object {
        const val CATALOG_BUSY = "The music catalog is busy. Try again in a minute"
        /** A file name in a request's folder: no folders, nothing hidden. */
        private val SAFE_FILE = Regex("""^[^/\\\u0000.][^/\\\u0000]{0,200}$""")
        private val VERSION = Regex("""\b(cover|karaoke|slowed|reverb|lo-?fi|8d|sped up)\b""", RegexOption.IGNORE_CASE)
        private const val RECENT_MS = 90L * 24 * 60 * 60 * 1000
        private const val ADMIN_RECENT_MS = 30L * 24 * 60 * 60 * 1000

        /** The same names, however spelled: each has to match the other. */
        fun same(a: List<String>, b: List<String>) = a.isNotEmpty() && b.isNotEmpty() && Fuzzy.match(a, b) >= 0.85 && Fuzzy.match(b, a) >= 0.85

        /** All of the library's [composer] is in the catalog's [artist] ("A. R. Rahman" in "A.R. Rahman & Vaali"). */
        fun composedBy(composer: List<String>, artist: List<String>) = composer.isNotEmpty() && artist.isNotEmpty() && Fuzzy.match(composer, artist) >= 0.85

        /** Words that name what kind of album it is, not which film: "Remo Special (Original Background Score + Additional Song)" is Remo. */
        private val KIND_WORDS = Regex("""\b(original|background|score|bgms?|ost|special|additional|songs?|soundtrack|theme|music|motion|picture)\b""", RegexOption.IGNORE_CASE)

        private val SCORE_TITLE = Regex("""\b(background\s+(score|music)|bgms?|ost|original\s+score)\b""", RegexOption.IGNORE_CASE)

        /** "Remo Special (Original Background Score + …)", "Kaithi BGM": a background score, not the film's songs. */
        fun isScoreTitle(title: String) = SCORE_TITLE.containsMatchIn(title)

        /** A movie's name as words, without bracketed parts or words about the album's kind. */
        fun movieWords(title: String): List<String> =
            Fuzzy.words(title.replace(KIND_WORDS, " ")).ifEmpty { Fuzzy.words(title) }

        /** One key per song or movie, whatever catalog entry it came from. */
        fun matchKey(item: CatalogItem) = listOf(item.kind, Fuzzy.key(item.title), Fuzzy.key(item.movie)).joinToString("|")

        /** "Kanave Kanave (David)", or "David (2013)" for a whole movie. */
        fun label(item: CatalogItem) = if (item.kind == "movie") item.movie + (item.year?.let { " ($it)" } ?: "") else "${item.title} (${item.movie})"
    }
}
