package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.Listening
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.basicAuth
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import kotlin.math.abs

private val log = LoggerFactory.getLogger("imports")

/** One song of a playlist from somewhere else, as that service describes it. */
@Serializable
data class ImportedTrack(
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
    /** The recording's ISRC, when the source says (Spotify's API, Exportify files): an exact match. */
    val isrc: String? = null,
    /** Its id in the music catalog (Apple Music songs are iTunes songs), so it can be requested straight away. */
    val catalogId: String? = null,
)

/** A song of the playlist and what it is in the library: [match] if found ([sure] when nothing is in doubt), [choices] to pick from. */
@Serializable
data class ImportRow(val track: ImportedTrack, val match: MixSong? = null, val sure: Boolean = false, val choices: List<MixSong> = emptyList())

@Serializable
data class ImportPreview(
    val name: String,
    /** "spotify", "apple", "youtube" or "file". */
    val source: String,
    val rows: List<ImportRow>,
    /** The source only showed part of the playlist (see [note]). */
    val truncated: Boolean = false,
    val note: String? = null,
)

/** A link to a playlist, or the text of an exported file (CSV, M3U, Apple Music XML) or pasted lines. */
@Serializable
data class ImportPreviewRequest(val url: String? = null, val text: String? = null, val name: String? = null)

@Serializable
data class ImportCreateRequest(val name: String, val songIds: List<String>)

@Serializable
data class ImportCreated(val playlistId: String, val songCount: Int)

/** Asking for a song of an imported playlist that isn't in the library. */
@Serializable
data class ImportRequestMusic(val title: String, val artist: String? = null, val album: String? = null, val catalogId: String? = null)

/** A playlist as read from its source, before matching. */
class ReadPlaylist(val name: String, val source: String, val tracks: List<ImportedTrack>, val truncated: Boolean = false, val note: String? = null)

/**
 * Importing playlists from Spotify, Apple Music, YouTube (and YouTube Music), or an exported file: the songs are
 * read, matched to the library, shown to check, and saved as a playlist of yours. Songs the library doesn't have
 * can be requested.
 */
class PlaylistImports(
    private val db: Db,
    private val music: MusicSource,
    private val listening: Listening,
    private val requests: MusicRequests?,
    private val reader: PlaylistReader,
) {
    private val mutex = Mutex()
    @Volatile private var matcher: ImportMatcher? = null

    suspend fun preview(request: ImportPreviewRequest): ImportPreview {
        val url = request.url?.trim().orEmpty()
        val read = when {
            url.isNotEmpty() -> reader.read(url)
            !request.text.isNullOrBlank() -> PlaylistFiles.parse(request.text, request.name?.trim()?.ifEmpty { null } ?: "Imported playlist")
            else -> throw ApiError(HttpStatusCode.BadRequest, "Paste a playlist link or choose a file")
        }
        if (read.tracks.isEmpty()) throw ApiError(HttpStatusCode.UnprocessableEntity, "No songs found there. Is the playlist public?")
        val m = matcher() ?: throw ApiError(HttpStatusCode.ServiceUnavailable, "The library isn't ready yet")
        val byIsrc = isrcMatches(read.tracks.mapNotNull { it.isrc })
        val rows = read.tracks.take(MAX_TRACKS).map { m.match(it, it.isrc?.let { code -> byIsrc[code.uppercase()] }) }
        log.info("Import from ${read.source}: ${rows.size} songs, ${rows.count { it.match != null }} found")
        return ImportPreview(
            read.name, read.source, rows,
            truncated = read.truncated || read.tracks.size > MAX_TRACKS,
            note = read.note ?: if (read.tracks.size > MAX_TRACKS) "Only the first $MAX_TRACKS songs are imported." else null,
        )
    }

    suspend fun create(me: UserDto, request: ImportCreateRequest): ImportCreated {
        val ids = request.songIds.distinct().take(MAX_TRACKS)
        if (ids.isEmpty()) throw ApiError(HttpStatusCode.BadRequest, "No songs to save")
        val snapshot = music.snapshot() ?: throw ApiError(HttpStatusCode.ServiceUnavailable, "The library isn't ready yet")
        val known = ids.filter { it in snapshot.index }
        val playlist = listening.create(me.id, request.name.ifBlank { "Imported playlist" }, known)
        return ImportCreated(playlist.id, known.size)
    }

    /** Requests a song that isn't in the library: by its catalog id, or found in the catalog by its name. */
    suspend fun request(me: UserDto, wanted: ImportRequestMusic): MusicRequestDto {
        val requests = requests ?: throw ApiError(HttpStatusCode.NotFound, "Requests are off on this server")
        wanted.catalogId?.let { return requests.request(me, it) }
        // Search by the title alone (the catalog search keeps results whose title or artist has every word asked
        // for), then prefer the one by the same artist.
        val title = ImportMatcher.cleanTitle(wanted.title).first
        val words = Fuzzy.words(title)
        val sameTitle = requests.search(me, title).songs.filter { MusicRequests.same(words, Fuzzy.words(ImportMatcher.cleanTitle(it.item.title).first)) }
        val artists = wanted.artist?.let(ImportMatcher::splitArtists).orEmpty().map(Fuzzy::words).filter { it.isNotEmpty() }
        val best = sameTitle.firstOrNull { hit ->
            val theirs = ImportMatcher.splitArtists(hit.item.artist.orEmpty()).map(Fuzzy::words)
            artists.any { a -> theirs.any { MusicRequests.same(a, it) } }
        } ?: sameTitle.firstOrNull()
            ?: throw ApiError(HttpStatusCode.NotFound, "Couldn't find \"$title\" in the catalog. Request it from Search instead.")
        return requests.request(me, best.item.id)
    }

    private suspend fun matcher(): ImportMatcher? = mutex.withLock {
        val snapshot = music.snapshot() ?: return null
        matcher?.takeIf { it.version == snapshot.version } ?: ImportMatcher(snapshot).also { matcher = it }
    }

    /** ISRC to song id, for the codes the library has (each recording keeps its ISRC from the file's tags). */
    private suspend fun isrcMatches(codes: List<String>): Map<String, String> {
        if (codes.isEmpty()) return emptyMap()
        val upper = codes.map { it.uppercase() }.distinct().take(MAX_TRACKS)
        return db.read {
            upper.chunked(400).flatMap { chunk ->
                query(
                    "SELECT upper(isrc), coalesce(merged_into, id) FROM recordings WHERE upper(isrc) IN (${chunk.joinToString(",") { "?" }})",
                    *chunk.toTypedArray(),
                ) { it.getString(1) to it.getString(2) }
            }.toMap()
        }
    }

    companion object {
        const val MAX_TRACKS = 1000
    }
}

/**
 * Finds an imported song in the library: by ISRC when there is one, otherwise by its title (spelling forgiven),
 * with the artists, the album or movie ("From \"Leo\""), and the length to tell songs with the same name apart.
 */
class ImportMatcher(private val snapshot: LibrarySnapshot) {
    val version = snapshot.version
    private val songs = snapshot.songs
    private val titleWords = songs.map { Fuzzy.words(cleanTitle(it.title).first) }
    private val people = songs.map { s -> (s.singers + listOfNotNull(s.composer) + s.lyricists).map { Fuzzy.words(it.name) } }
    private val albumWords = songs.map { Fuzzy.words(it.album.substringBefore(" (Original")) }
    /** Songs by each word of their title, to look at only the songs that could match. */
    private val byWord: Map<String, List<Int>> = HashMap<String, MutableList<Int>>().also { map ->
        titleWords.forEachIndexed { i, words -> if (!songs[i].karaoke) words.toSet().forEach { map.getOrPut(it) { mutableListOf() } += i } }
    }

    fun match(track: ImportedTrack, isrcSongId: String? = null): ImportRow {
        isrcSongId?.let { id -> snapshot.index[id]?.let { return ImportRow(track, song(it), sure = true) } }
        val (title, movie) = cleanTitle(track.title)
        // A YouTube title is often "Song | Movie | Singer" or "Singer - Song": try each part as the title too.
        val titles = (listOf(title) + title.split(" | ", " - ", " – ", " — ").map { it.trim() }.filter { it.length >= 2 }).distinct()
        val artists = track.artists.flatMap { splitArtists(it) }.map(Fuzzy::words).filter { it.isNotEmpty() }
        val albumHints = listOfNotNull(movie, track.album?.let { cleanTitle(it).first }).map(Fuzzy::words).filter { it.isNotEmpty() }
        // Words of the whole YouTube title, to recognise the movie or a singer mentioned in it.
        val allWords = Fuzzy.words(track.title).toSet()

        val scored = HashMap<Int, Double>()
        for (t in titles) {
            val q = Fuzzy.words(t)
            if (q.isEmpty()) continue
            val candidates = q.flatMap { byWord[it].orEmpty() }.toSet() + nearWords(q)
            for (i in candidates) {
                val byTitle = minOf(Fuzzy.match(q, titleWords[i]), Fuzzy.match(titleWords[i], q))
                if (byTitle < 0.8) continue
                val byArtist = when {
                    artists.isNotEmpty() -> if (artists.any { a -> people[i].any { p -> MusicRequests.same(a, p) } }) 1.0 else 0.0
                    else -> if (people[i].any { p -> p.isNotEmpty() && allWords.containsAll(p) }) 0.6 else 0.0
                }
                val byAlbum = when {
                    albumHints.any { MusicRequests.same(it, albumWords[i]) } -> 1.0
                    albumWords[i].isNotEmpty() && allWords.containsAll(albumWords[i]) -> 0.8
                    else -> 0.0
                }
                val gap = track.durationMs?.let { abs(it - songs[i].duration * 1000L) }
                val byLength = when {
                    gap == null || songs[i].duration <= 0 -> 0.0
                    gap <= 4_000 -> 1.0
                    gap <= 15_000 -> 0.3
                    else -> -1.0
                }
                val score = byTitle + 0.5 * byArtist + 0.4 * byAlbum + 0.3 * byLength
                if (score > (scored[i] ?: -9.0)) scored[i] = score
            }
        }
        val ranked = scored.entries.sortedByDescending { it.value }.take(5)
        val best = ranked.firstOrNull() ?: return ImportRow(track)
        val second = ranked.getOrNull(1)?.value ?: 0.0
        // Found: the title matches and something else agrees (an artist, the movie, the length), or nothing else
        // is close. Sure: it also clearly beats the next best.
        val found = best.value >= 1.2 || (best.value >= 0.95 && second < best.value - 0.25)
        val sure = found && best.value >= 1.3 && best.value - second >= 0.3
        return ImportRow(track, if (found) song(best.key) else null, sure, ranked.map { song(it.key) })
    }

    /** Songs whose title has a word spelled close to one typed (the index only finds exact sounds). */
    private fun nearWords(q: List<String>): Set<Int> {
        if (q.size > 6) return emptySet()
        val out = HashSet<Int>()
        for (word in q.filter { it.length >= 4 }) {
            for ((w, list) in byWord) if (w != word && w.length >= 4 && abs(w.length - word.length) <= 1 && w[0] == word[0] && Fuzzy.match(listOf(word), listOf(w)) >= 0.8) out += list
            if (out.size > 2000) break
        }
        return out
    }

    private fun song(i: Int): MixSong = songs[i].let { s ->
        MixSong(
            id = s.id, title = s.title, artist = s.artist.takeIf { it.isNotBlank() }, album = s.album, albumId = s.albumId,
            coverArt = s.coverArt, duration = s.duration, year = s.year.takeIf { it > 0 }, artists = s.singers.map { PersonDto(it.id, it.name) },
        )
    }

    companion object {
        private val fromMovie = Regex("""[(\[]\s*from\s+(?:the\s+)?(?:movie\s+|film\s+)?["“']?([^"”')\]]+)["”']?\s*[)\]]|\s+-\s+from\s+["“']([^"”']+)["”']""", RegexOption.IGNORE_CASE)
        private val noise = Regex(
            """\s*[(\[](?:feat\.?|ft\.?|with|prod\.?|remaster(?:ed)?|\d{4}\s+remaster(?:ed)?|original motion picture soundtrack|lyric(?:al)? video|official.*?|full video|audio|video song|hd|4k)[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE,
        )
        private val dashNoise = Regex("""\s+-\s+(?:remaster(?:ed)?.*|\d{4} remaster.*|from .*|live.*|radio edit|single version)$""", RegexOption.IGNORE_CASE)

        /** A title without "(From \"Movie\")", "(feat. …)", "- Remastered" and the like, and the movie it named. */
        fun cleanTitle(raw: String): Pair<String, String?> {
            val movie = fromMovie.find(raw)?.let { m -> (m.groups[1] ?: m.groups[2])?.value?.trim() }
            val title = raw.replace(fromMovie, " ").replace(noise, " ").replace(dashNoise, "").replace(Regex("\\s+"), " ").trim()
            return (title.ifEmpty { raw.trim() }) to movie
        }

        /** "A, B & C feat. D" into its names. */
        fun splitArtists(text: String): List<String> =
            text.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|\band\b|\bx\b|•)\s*""", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** Reads a playlist from a link: Spotify, Apple Music, YouTube or YouTube Music. */
interface PlaylistReader {
    suspend fun read(url: String): ReadPlaylist
}

/**
 * The public pages of Spotify, Apple Music and YouTube carry their playlists' songs, so no account or key is
 * needed. With a Spotify app's id and secret (free, developer.spotify.com), Spotify playlists come from its API
 * instead: every song (not just the first 100) and their ISRCs.
 */
class WebPlaylistReader(
    private val http: HttpClient = HttpClient(CIO) { followRedirects = true },
    private val spotifyClientId: String? = null,
    private val spotifyClientSecret: String? = null,
) : PlaylistReader {
    private val json = Json { ignoreUnknownKeys = true }
    private var spotifyToken: Pair<String, Long>? = null
    private val tokenLock = Mutex()

    override suspend fun read(url: String): ReadPlaylist {
        val u = runCatching { java.net.URI(url.trim()) }.getOrNull()
        val host = u?.host?.lowercase()?.removePrefix("www.").orEmpty()
        return when {
            host == "open.spotify.com" || host == "spotify.link" -> spotify(url.trim())
            host == "music.apple.com" -> apple(url.trim())
            host.endsWith("youtube.com") || host == "youtu.be" -> youtube(url.trim())
            else -> throw ApiError(HttpStatusCode.BadRequest, "Paste a Spotify, Apple Music or YouTube playlist link, or import a file")
        }
    }

    private suspend fun page(url: String, vararg headers: Pair<String, String>): String = withTimeoutOrNull(20_000) {
        runCatching {
            val response = http.get(url) {
                header("User-Agent", BROWSER)
                header("Accept-Language", "en")
                headers.forEach { (k, v) -> header(k, v) }
            }
            if (!response.status.isSuccess()) null else response.bodyAsText()
        }.getOrNull()
    } ?: throw ApiError(HttpStatusCode.BadGateway, "Couldn't open that link. Is the playlist public?")

    private suspend fun spotify(url: String): ReadPlaylist {
        val match = Regex("""(playlist|album)/([A-Za-z0-9]+)""").find(url)
            ?: throw ApiError(HttpStatusCode.BadRequest, "That isn't a Spotify playlist or album link")
        val (kind, id) = match.destructured
        if (spotifyClientId != null && spotifyClientSecret != null) {
            runCatching { return spotifyApi(kind, id) }.onFailure { log.info("Spotify API failed for $kind $id, using the public page: ${it.message}") }
        }
        return PlaylistPages.spotifyEmbed(page("https://open.spotify.com/embed/$kind/$id"))
    }

    private suspend fun spotifyApi(kind: String, id: String): ReadPlaylist {
        val token = spotifyToken()
        suspend fun get(u: String): JsonObject {
            val r = http.get(u) { header("Authorization", "Bearer $token") }
            if (!r.status.isSuccess()) throw IllegalStateException("Spotify said ${r.status}")
            return json.parseToJsonElement(r.bodyAsText()).jsonObject
        }
        val first = get("https://api.spotify.com/v1/${kind}s/$id")
        val name = first.str("name") ?: "Spotify playlist"
        val tracks = mutableListOf<ImportedTrack>()
        var pageObj = first["tracks"]?.jsonObject
        val albumName = if (kind == "album") name else null
        while (pageObj != null && tracks.size < PlaylistImports.MAX_TRACKS) {
            pageObj["items"]?.jsonArray?.forEach { item ->
                val t = (if (kind == "album") item else item.jsonObject["track"])?.let { it as? JsonObject } ?: return@forEach
                tracks += ImportedTrack(
                    title = t.str("name") ?: return@forEach,
                    artists = t["artists"]?.jsonArray?.mapNotNull { it.jsonObject.str("name") }.orEmpty(),
                    album = albumName ?: t["album"]?.jsonObject?.str("name"),
                    durationMs = t["duration_ms"]?.jsonPrimitive?.longOrNull,
                    isrc = t["external_ids"]?.jsonObject?.str("isrc"),
                )
            }
            pageObj = pageObj.str("next")?.let { get(it) }
        }
        return ReadPlaylist(name, "spotify", tracks)
    }

    private suspend fun spotifyToken(): String = tokenLock.withLock {
        spotifyToken?.takeIf { it.second > System.currentTimeMillis() + 60_000 }?.let { return it.first }
        val r = http.submitForm("https://accounts.spotify.com/api/token", Parameters.build { append("grant_type", "client_credentials") }) {
            basicAuth(spotifyClientId!!, spotifyClientSecret!!)
        }
        val o = json.parseToJsonElement(r.bodyAsText()).jsonObject
        val token = o.str("access_token") ?: throw IllegalStateException("No Spotify token")
        val ttl = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        spotifyToken = token to System.currentTimeMillis() + ttl * 1000
        token
    }

    private suspend fun apple(url: String) = PlaylistPages.appleMusic(page(url))

    private suspend fun youtube(url: String): ReadPlaylist {
        val list = Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: throw ApiError(HttpStatusCode.BadRequest, "That YouTube link isn't a playlist (it needs list=…)")
        // The consent cookie skips the cookie banner page some countries get first.
        return PlaylistPages.youtube(page("https://www.youtube.com/playlist?list=$list&hl=en", "Cookie" to "SOCS=CAI; CONSENT=YES+1"))
    }

    companion object {
        private const val BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

/** Reading the songs out of the services' public playlist pages (each keeps them as JSON inside the page). */
object PlaylistPages {
    private val json = Json { ignoreUnknownKeys = true }

    private fun script(html: String, pattern: Regex): JsonElement? =
        pattern.find(html)?.groupValues?.get(1)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }

    /** Spotify's embed page: the name and up to 100 songs (title, artists, length). */
    fun spotifyEmbed(html: String): ReadPlaylist {
        val data = script(html, Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL))
        val entity = data?.jsonObject?.get("props")?.jsonObject?.get("pageProps")?.jsonObject?.get("state")?.jsonObject
            ?.get("data")?.jsonObject?.get("entity")?.jsonObject
            ?: throw ApiError(HttpStatusCode.UnprocessableEntity, "Couldn't read that Spotify playlist. Is it public?")
        val album = if (entity.str("type") == "album") entity.str("name") else null
        val tracks = entity["trackList"]?.jsonArray?.mapNotNull { t ->
            val o = t.jsonObject
            ImportedTrack(
                title = o.str("title") ?: return@mapNotNull null,
                artists = o.str("subtitle")?.split(Regex(""",\s*"""))?.map { it.trim() }.orEmpty().filter { it.isNotEmpty() },
                album = album,
                durationMs = o["duration"]?.jsonPrimitive?.longOrNull,
            )
        }.orEmpty()
        val full = tracks.size < 100
        return ReadPlaylist(
            entity.str("name") ?: "Spotify playlist", "spotify", tracks, truncated = !full,
            note = if (full) null else "Spotify's public page shows the first 100 songs. For all of them, import a CSV from exportify.app, or ask the server's admin to add a Spotify app id.",
        )
    }

    /** An Apple Music playlist or album page: the name and its songs (title, artists, album, length, iTunes id). */
    fun appleMusic(html: String): ReadPlaylist {
        val data = script(html, Regex("""<script type="application/json" id="serialized-server-data">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL))
            ?: throw ApiError(HttpStatusCode.UnprocessableEntity, "Couldn't read that Apple Music page. Is the playlist public?")
        val root = when (data) {
            is JsonArray -> data.firstOrNull()?.jsonObject?.get("data")?.jsonObject
            is JsonObject -> (data["data"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("data")?.jsonObject ?: data
            else -> null
        }
        val sections = root?.get("sections")?.jsonArray.orEmpty().map { it.jsonObject }
        val header = sections.firstOrNull { it.str("itemKind") == "containerDetailHeaderLockup" }?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
        val name = header?.str("title") ?: "Apple Music playlist"
        val isAlbum = header?.get("contentDescriptor")?.jsonObject?.str("kind") == "album"
        val tracks = sections.filter { it.str("itemKind") == "trackLockup" }.flatMap { it["items"]?.jsonArray.orEmpty() }.mapNotNull { t ->
            val o = t.jsonObject
            val id = o["contentDescriptor"]?.jsonObject?.get("identifiers")?.jsonObject?.str("storeAdamID")
            ImportedTrack(
                title = o.str("title") ?: return@mapNotNull null,
                artists = o["subtitleLinks"]?.jsonArray?.mapNotNull { it.jsonObject.str("title") }?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(o.str("artistName")),
                album = if (isAlbum) name else o["tertiaryLinks"]?.jsonArray?.firstOrNull()?.jsonObject?.str("title"),
                durationMs = o["duration"]?.jsonPrimitive?.longOrNull,
                catalogId = id?.takeIf { it.all(Char::isDigit) }?.let { "itunes-song-$it" },
            )
        }
        val full = tracks.size < 100
        return ReadPlaylist(
            name, "apple", tracks, truncated = !full,
            note = if (full) null else "Apple Music's page shows the first 100 songs. For all of them, export the playlist from the Music app (File › Library › Export Playlist) and import the file.",
        )
    }

    /** A YouTube playlist page (also YouTube Music playlists): up to 100 videos, their titles, channels and lengths. */
    fun youtube(html: String): ReadPlaylist {
        val data = script(html, Regex("""var ytInitialData\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL))
            ?: throw ApiError(HttpStatusCode.UnprocessableEntity, "Couldn't read that YouTube playlist. Is it public (or unlisted)?")
        val videos = mutableListOf<JsonObject>()
        // YouTube's newer pages describe each video as a "lockup" instead.
        val lockups = mutableListOf<JsonObject>()
        var more = false
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    e["playlistVideoRenderer"]?.jsonObject?.let { videos += it }
                    e["lockupViewModel"]?.jsonObject?.takeIf { it.str("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" }?.let { lockups += it }
                    if (e.containsKey("continuationItemRenderer")) more = true
                    e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> {}
            }
        }
        walk(data)
        fun text(o: JsonObject?): String? = o?.str("simpleText") ?: o?.get("runs")?.jsonArray?.joinToString("") { it.jsonObject.str("text").orEmpty() }
        val title = Regex("""<meta property="og:title" content="([^"]*)">""").find(html)?.groupValues?.get(1)?.let(::unescape)
        val tracks = videos.mapNotNull { v ->
            val name = text(v["title"]?.jsonObject) ?: return@mapNotNull null
            val channel = text(v["shortBylineText"]?.jsonObject)?.removeSuffix(" - Topic")
            ImportedTrack(name, listOfNotNull(channel?.takeIf { it.isNotBlank() }), null, v.str("lengthSeconds")?.toLongOrNull()?.times(1000))
        } + lockups.mapNotNull { l ->
            val meta = l["metadata"]?.jsonObject?.get("lockupMetadataViewModel")?.jsonObject ?: return@mapNotNull null
            val name = meta["title"]?.jsonObject?.str("content") ?: return@mapNotNull null
            val channel = meta["metadata"]?.jsonObject?.get("contentMetadataViewModel")?.jsonObject?.get("metadataRows")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("metadataParts")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonObject?.str("content")
                ?.removeSuffix(" - Topic")
            // The length is the badge on the thumbnail ("3:55").
            val badge = LENGTH_BADGE.find(l.toString())?.groupValues?.get(1)
            val ms = badge?.split(':')?.mapNotNull { it.toLongOrNull() }?.fold(0L) { a, b -> a * 60 + b }?.times(1000)
            ImportedTrack(name, listOfNotNull(channel?.takeIf { it.isNotBlank() }), null, ms)
        }
        // The page holds 100 videos at most; a full page means there may be more.
        val cut = more || tracks.size >= 100
        return ReadPlaylist(
            title ?: "YouTube playlist", "youtube", tracks, truncated = cut,
            note = if (cut) "YouTube's page shows the first 100 videos of a playlist." else null,
        )
    }

    private val LENGTH_BADGE = Regex("\"thumbnailBadgeViewModel\":\\{.*?\"text\":\"(\\d+(?::\\d+){1,2})\"", RegexOption.DOT_MATCHES_ALL)

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
}

/**
 * Exported playlists as text: CSV (Exportify, TuneMyMusic, Soundiiz…), M3U/M3U8, an Apple Music playlist export
 * (XML), or plain lines like "Title - Artist".
 */
object PlaylistFiles {
    fun parse(text: String, name: String): ReadPlaylist {
        val t = text.removePrefix("﻿")
        val tracks = when {
            t.contains("<plist") -> appleXml(t)
            t.trimStart().startsWith("#EXTM3U") || t.contains("#EXTINF") -> m3u(t)
            looksLikeCsv(t) -> csv(t)
            else -> lines(t)
        }
        val plistName = if (t.contains("<plist")) Regex("""<key>Name</key><string>([^<]*)</string>\s*<key>Description""").find(t)?.groupValues?.get(1) else null
        return ReadPlaylist(plistName?.let(::xmlText) ?: name, "file", tracks)
    }

    private fun looksLikeCsv(t: String): Boolean {
        val header = t.lineSequence().firstOrNull()?.lowercase() ?: return false
        return (header.contains(',') || header.contains('\t') || header.contains(';')) && (header.contains("track") || header.contains("title") || header.contains("song"))
    }

    fun csv(t: String): List<ImportedTrack> {
        val firstLine = t.lineSequence().first()
        val sep = listOf('\t', ';', ',').maxBy { c -> firstLine.count { it == c } }
        val rows = csvRows(t, sep)
        if (rows.size < 2) return emptyList()
        val head = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> head.indexOfFirst { it == n }.takeIf { it >= 0 } }
            ?: names.firstNotNullOfOrNull { n -> head.indexOfFirst { it.contains(n) }.takeIf { it >= 0 } }
        val title = col("track name", "title", "name", "song", "track") ?: return emptyList()
        val artist = col("artist name(s)", "artist name", "artists", "artist")
        val album = col("album name", "album")
        val duration = col("duration (ms)", "duration_ms", "duration", "length", "time")
        val isrc = col("isrc")
        return rows.drop(1).mapNotNull { r ->
            val name = r.getOrNull(title)?.trim().orEmpty().ifEmpty { return@mapNotNull null }
            ImportedTrack(
                name,
                artist?.let { r.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty(),
                album?.let { r.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() },
                duration?.let { r.getOrNull(it) }?.let(::durationMs),
                isrc?.let { r.getOrNull(it) }?.trim()?.takeIf { it.length == 12 },
            )
        }
    }

    /** "245000" (ms), "245" (s), "4:05" or "1:04:05". */
    private fun durationMs(v: String): Long? {
        val s = v.trim()
        if (s.contains(':')) return s.split(':').mapNotNull { it.trim().toLongOrNull() }.fold(0L) { a, b -> a * 60 + b } * 1000
        val n = s.toLongOrNull() ?: return null
        return if (n > 10_000) n else n * 1000
    }

    private fun csvRows(t: String, sep: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                quoted && c == '"' && t.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && t.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    fun m3u(t: String): List<ImportedTrack> {
        val out = mutableListOf<ImportedTrack>()
        var pending: Pair<String, Long?>? = null
        for (line in t.lines().map { it.trim() }) {
            when {
                line.startsWith("#EXTINF:") -> {
                    val body = line.removePrefix("#EXTINF:")
                    val seconds = body.substringBefore(',').substringBefore(' ').toLongOrNull()
                    pending = body.substringAfter(',', "").trim() to seconds?.takeIf { it > 0 }?.times(1000)
                }
                line.isEmpty() || line.startsWith("#") -> {}
                else -> {
                    val (text, ms) = pending ?: (line.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.') to null)
                    out += fromLine(text)?.copy(durationMs = ms) ?: continue
                    pending = null
                }
            }
        }
        return out
    }

    /** An Apple Music / iTunes playlist export: the tracks dictionary (Name, Artist, Album, Total Time) in playlist order. */
    fun appleXml(t: String): List<ImportedTrack> {
        val dicts = Regex("""<key>(\d+)</key>\s*<dict>(.*?)</dict>""", RegexOption.DOT_MATCHES_ALL).findAll(t).associate { m ->
            val fields = Regex("""<key>([^<]+)</key>\s*<(string|integer)>([^<]*)</\2>""").findAll(m.groupValues[2]).associate { it.groupValues[1] to xmlText(it.groupValues[3]) }
            m.groupValues[1] to fields
        }
        val order = Regex("""<key>Track ID</key>\s*<integer>(\d+)</integer>""").findAll(t.substringAfter("<key>Playlists</key>", "")).map { it.groupValues[1] }.toList()
            .ifEmpty { dicts.keys.toList() }
        return order.mapNotNull { id ->
            val f = dicts[id] ?: return@mapNotNull null
            ImportedTrack(f["Name"] ?: return@mapNotNull null, listOfNotNull(f["Artist"]), f["Album"], f["Total Time"]?.toLongOrNull())
        }
    }

    private fun xmlText(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    /** Plain lines: "Title - Artist" (or "Artist - Title", the matcher tries both), one song a line. */
    fun lines(t: String): List<ImportedTrack> = t.lines().mapNotNull { fromLine(it.trim().removePrefix("•").removePrefix("-").trim().replace(Regex("""^\d+[.)]\s*"""), "")) }

    private fun fromLine(line: String): ImportedTrack? {
        if (line.isBlank() || line.length > 300) return null
        val parts = line.split(" - ", " – ", " — ").map { it.trim() }.filter { it.isNotEmpty() }
        return when (parts.size) {
            0 -> null
            1 -> ImportedTrack(parts[0])
            // "Title - Artist" or "Artist - Title": keep the whole line as the title too, so either way matches.
            else -> ImportedTrack(line, listOf(parts.last(), parts.first()))
        }
    }
}
