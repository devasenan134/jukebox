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
)

/** A catalog result, with its request if someone already asked for it. */
@Serializable
data class CatalogHit(val item: CatalogItem, val request: MusicRequestDto? = null)

/** Catalog results that aren't in the library. */
@Serializable
data class CatalogResults(val movies: List<CatalogHit> = emptyList(), val songs: List<CatalogHit> = emptyList())

@Serializable data class NewMusicRequest(val id: String)

@Serializable data class DeclineMusicRequest(val note: String? = null)

/** Where songs and movies that aren't in the library are found. Tests use a pretend one. */
interface Catalog {
    /** Songs and movies for [query], best first. Empty if the catalog can't be reached. */
    suspend fun search(query: String): List<CatalogItem>

    /** One item by its [CatalogItem.id]. */
    suspend fun lookup(id: String): CatalogItem?
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
        fun hits(kind: String, max: Int) = found.filter { it.kind == kind }.take(max).map { CatalogHit(it, requests[matchKey(it)]?.takeIf { r -> r.status != "done" }) }
        return CatalogResults(movies = hits("movie", 10), songs = hits("song", 20))
    }

    suspend fun request(me: UserDto, catalogId: String): MusicRequestDto {
        val item = catalog.lookup(catalogId.take(100)) ?: throw ApiError(HttpStatusCode.NotFound, "Couldn't find that in the catalog")
        if (shelf()?.find(item) != null) throw ApiError(HttpStatusCode.Conflict, "It's already in the library")
        val key = matchKey(item)
        val (id, isNew) = db.tx {
            val open = queryOne(
                "SELECT count(*) FROM music_request_askers a JOIN music_requests r ON r.id = a.request_id WHERE a.user_id = ? AND r.status = 'open'", me.id,
            ) { it.getInt(1) } ?: 0
            val existing = queryOne("SELECT id, status FROM music_requests WHERE match_key = ?", key) { it.getLong(1) to it.getString(2) }
            val asked = existing != null && queryOne("SELECT 1 FROM music_request_askers WHERE request_id = ? AND user_id = ?", existing.first, me.id) { true } != null
            if (!(asked && existing?.second == "open") && open >= MAX_OPEN) throw ApiError(HttpStatusCode.TooManyRequests, "You can have up to $MAX_OPEN requests waiting")
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

    /** Takes back your request; one nobody wants any more goes away. */
    suspend fun cancel(me: UserDto, id: Long) = db.tx {
        update("DELETE FROM music_request_askers WHERE request_id = ? AND user_id = ?", id, me.id)
        update("DELETE FROM music_requests WHERE id = ? AND status = 'open' AND NOT EXISTS (SELECT 1 FROM music_request_askers WHERE request_id = ?)", id, id)
    }

    /** Your requests: waiting ones first, then ones answered in the last 90 days. */
    suspend fun mine(me: UserDto): List<MusicRequestDto> = db.tx {
        query(
            """SELECT r.id FROM music_requests r JOIN music_request_askers a ON a.request_id = r.id
               WHERE a.user_id = ? AND (r.status = 'open' OR r.closed_at > ?)
               ORDER BY r.status = 'open' DESC, coalesce(r.closed_at, r.created_at) DESC""",
            me.id, now() - RECENT_MS,
        ) { it.getLong(1) }.mapNotNull { dto(it, me.id) }
    }

    /** For admins: everything waiting (oldest first), then what was answered in the last 30 days. */
    suspend fun all(me: UserDto): List<MusicRequestDto> {
        requireAdmin(me)
        return db.tx {
            query(
                """SELECT id FROM music_requests WHERE status = 'open' OR closed_at > ?
                   ORDER BY status = 'open' DESC, CASE WHEN status = 'open' THEN created_at ELSE -closed_at END""",
                now() - ADMIN_RECENT_MS,
            ) { it.getLong(1) }.mapNotNull { dto(it, me.id) }
        }
    }

    /** An admin added the music: it must be in the library now. Everyone who asked is told. */
    suspend fun complete(me: UserDto, id: Long): MusicRequestDto {
        requireAdmin(me)
        val request = db.tx { dto(id, me.id) } ?: throw ApiError(HttpStatusCode.NotFound, "No such request")
        val place = shelf()?.find(request.item)
            ?: throw ApiError(HttpStatusCode.Conflict, "It isn't in the library yet. New files are picked up at the next scan; try again then")
        val askers = db.tx {
            update(
                "UPDATE music_requests SET status = 'done', album_id = ?, song_id = ?, note = NULL, closed_at = ? WHERE id = ?",
                place.albumId, place.songId, now(), id,
            )
            askers(id)
        }
        push.musicReady(askers, label(request.item), place.albumId)
        return db.tx { dto(id, me.id) }!!
    }

    /** An admin couldn't find it. Everyone who asked is told, with the admin's [note] if any. */
    suspend fun decline(me: UserDto, id: Long, note: String?): MusicRequestDto {
        requireAdmin(me)
        val request = db.tx { dto(id, me.id) } ?: throw ApiError(HttpStatusCode.NotFound, "No such request")
        val why = note?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
        val askers = db.tx {
            update("UPDATE music_requests SET status = 'declined', note = ?, album_id = NULL, song_id = NULL, closed_at = ? WHERE id = ?", why, now(), id)
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
        return queryOne("SELECT item_json, status, created_at, closed_at, note, album_id, song_id FROM music_requests WHERE id = ?", id) {
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

        class Movie(val id: String, val words: List<String>, val year: Int, val composer: List<String>, val songs: List<Pair<String, List<String>>>)

        val movies = snapshot.byAlbum.map { (id, list) ->
            val songs = list.map { snapshot.songs[it] }.filter { !it.karaoke }
            val first = snapshot.songs[list.first()]
            Movie(id, Fuzzy.words(first.album), list.maxOf { snapshot.songs[it].year }, Fuzzy.words(first.composer?.name.orEmpty()), songs.map { it.id to Fuzzy.words(it.title) })
        }

        fun find(item: CatalogItem): Place? {
            val words = Fuzzy.words(item.movie)
            val candidates = movies.filter { same(words, it.words) }
            if (item.kind == "movie") {
                // A remake has the same name: the year (or, if the catalog's year is a re-release, the composer) tells them apart.
                val artist = Fuzzy.words(item.artist.orEmpty())
                val movie = candidates.firstOrNull { m ->
                    item.year == null || m.year <= 0 || abs(m.year - item.year) <= 1 || same(artist, m.composer)
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
        const val MAX_OPEN = 20
        private val VERSION = Regex("""\b(cover|karaoke|slowed|reverb|lo-?fi|8d|sped up)\b""", RegexOption.IGNORE_CASE)
        private const val RECENT_MS = 90L * 24 * 60 * 60 * 1000
        private const val ADMIN_RECENT_MS = 30L * 24 * 60 * 60 * 1000

        /** The same names, however spelled: each has to match the other. */
        fun same(a: List<String>, b: List<String>) = a.isNotEmpty() && b.isNotEmpty() && Fuzzy.match(a, b) >= 0.85 && Fuzzy.match(b, a) >= 0.85

        /** One key per song or movie, whatever catalog entry it came from. */
        fun matchKey(item: CatalogItem) = listOf(item.kind, Fuzzy.key(item.title), Fuzzy.key(item.movie)).joinToString("|")

        /** "Kanave Kanave (David)", or "David (2013)" for a whole movie. */
        fun label(item: CatalogItem) = if (item.kind == "movie") item.movie + (item.year?.let { " ($it)" } ?: "") else "${item.title} (${item.movie})"
    }
}
