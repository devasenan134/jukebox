package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.MusicLibrary
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("requests")

/**
 * JioSaavn's catalog (its web API, no key), where requests are searched since 2026-11-23: the library's songs came
 * from JioSaavn and go by its names, and fulfil.py names downloads after it, so a request, the download and the
 * library's song are called the same. Only songs and albums in [languages] (the libraries' languages, the first
 * library's first; empty: any) are offered, as JioSaavn has a film's Telugu and Hindi albums under the same names
 * ("Hukum" from Jailer is three songs). [legacy] looks up the ids from before (iTunes: requests made then, Apple
 * Music playlists).
 */
class SaavnCatalog(
    private val languages: List<String>,
    private val legacy: Catalog? = null,
    private val http: HttpClient = HttpClient(CIO),
    private val api: String = "https://www.jiosaavn.com/api.php",
) : Catalog {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val searches = object : LinkedHashMap<String, Pair<Long, List<CatalogItem>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<CatalogItem>>>) = size > 300
    }
    private val items = object : LinkedHashMap<String, CatalogItem>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CatalogItem>) = size > 5_000
    }

    override suspend fun search(query: String): List<CatalogItem> {
        val key = Fuzzy.words(query).joinToString(" ")
        mutex.withLock { searches[key]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.let { return it.second } }
        val found = coroutineScope {
            val albums = async { call("search.getAlbumResults", "q" to query, "n" to "20", "p" to "1") }
            val songs = async { call("search.getResults", "q" to query, "n" to "40", "p" to "1") }
            val replies = listOf(albums.await(), songs.await())
            if (replies.any { it == null }) null
            else replies.flatMap { reply -> (reply!!.jsonObject()?.get("results") as? JsonArray).orEmpty().mapNotNull { parse(it, languages.toSet())?.let(::marked) } }
                .sortedBy { languages.indexOf(it.language).let { i -> if (i < 0) languages.size else i } }
        } ?: return emptyList()
        mutex.withLock {
            searches[key] = System.currentTimeMillis() to found
            found.forEach { items[it.id] = it }
        }
        return found
    }

    override suspend fun lookup(id: String): CatalogItem? {
        if (!id.startsWith(SONG) && !id.startsWith(MOVIE)) return legacy?.lookup(id)
        mutex.withLock { items[id] }?.let { return it }
        val item = if (id.startsWith(SONG)) {
            val reply = call("song.getDetails", "pids" to id.removePrefix(SONG))?.jsonObject()
            (reply?.get("songs") as? JsonArray)?.firstOrNull()?.let { parse(it, emptySet()) }
        } else {
            call("content.getAlbumDetails", "albumid" to id.removePrefix(MOVIE))?.let { parse(it, emptySet()) }
        }
        return item?.takeIf { it.id == id }?.let(::marked)?.also { mutex.withLock { items[id] = it } }
    }

    override suspend fun busy(): Boolean = false

    /**
     * A film in another language than the first library's says so in its name ("Jailer (Telugu)"): the apps show a
     * request's title and film, and the Telugu "Hukum" looks like the Tamil one otherwise.
     */
    private fun marked(item: CatalogItem): CatalogItem {
        val language = item.language ?: return item
        if (language == languages.firstOrNull()) return item
        val movie = "${item.movie} (${language.replaceFirstChar(Char::uppercase)})"
        return item.copy(movie = movie, title = if (item.kind == "movie") movie else item.title)
    }

    private suspend fun call(method: String, vararg params: Pair<String, String>): JsonElement? = withTimeoutOrNull(15_000) {
        runCatching {
            val response = http.get(api) {
                header("User-Agent", "Mozilla/5.0")
                parameter("__call", method)
                params.forEach { (k, v) -> parameter(k, v) }
                parameter("_format", "json")
                parameter("_marker", "0")
                parameter("api_version", "4")
                parameter("ctx", "web6dot0")
            }
            if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
            json.parseToJsonElement(response.bodyAsText())
        }.onFailure { log.warn("JioSaavn call $method failed: ${it.message}") }.getOrNull()
    }

    companion object {
        /** The languages of the libraries in a LIBRARIES setting ("tamil=/music/tamil:film:tamil;…"), in its order. */
        fun languagesOf(libraries: String?): List<String> =
            MusicLibrary.parse(libraries).map { (it.language ?: it.name).lowercase() }.distinct()

        const val SONG = "saavn-song-"
        const val MOVIE = "saavn-movie-"
        private const val KEEP_MS = 6 * 60 * 60 * 1000L
        /** "(From "Jailer 2")", and "(From"Baasha")" as JioSaavn sometimes writes it. */
        private val FROM = Regex("""\s*[(\[]From\s*["“”']([^"“”']+)["“”']\s*[)\]]""", RegexOption.IGNORE_CASE)

        private fun JsonElement.jsonObject() = this as? JsonObject
        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

        /** JioSaavn writes its names with HTML entities ("Hukum Reloaded - Tamil (From &quot;Jailer 2&quot;)"). */
        fun unescape(text: String) = text.replace("&quot;", "\"").replace("&#039;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

        /**
         * Whether an album is one film's music. JioSaavn often calls it just the film ("Jailer 2", "96"), where iTunes
         * says "(Original Motion Picture Soundtrack)", so a number in the name is fine; compilations' words aren't.
         */
        fun isFilmAlbum(album: String): Boolean {
            if (album.isBlank() || FROM.containsMatchIn(album)) return false
            if (ITunesCatalog.isMovieAlbum(album)) return true
            val words = ITunesCatalog.movieName(album).lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            return words.size in 1..5 && words.none { it in ITunesCatalog.COMPILATION }
        }

        /** The people of a song or album in [role] ("primary_artists", "artists"), as "A, B". */
        private fun people(more: JsonObject?, role: String): String? =
            ((more?.get("artistMap") as? JsonObject)?.get(role) as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.text("name") }
                .filterNot { it.equals("Various Artists", ignoreCase = true) }
                .distinct().joinToString(", ").ifEmpty { null }

        /** A song or an album of a search (or a lookup) as a catalog item; null for one in another language, or a compilation's. */
        fun parse(element: JsonElement, languages: Set<String>): CatalogItem? {
            val r = element.jsonObject() ?: return null
            val language = r.text("language")?.lowercase()
            if (languages.isNotEmpty() && language !in languages) return null
            val id = r.text("id") ?: return null
            val title = r.text("title") ?: return null
            val more = r["more_info"]?.jsonObject()
            val year = r.text("year")?.toIntOrNull()?.takeIf { it > 0 }
            val art = r.text("image")?.replace("150x150", "500x500")
            return when (r.text("type")) {
                "song" -> {
                    val album = more?.text("album").orEmpty()
                    // A single says which film it's from ("Hukum Reloaded - Tamil (From "Jailer 2")"); otherwise its album is the film.
                    val onMovieAlbum = isFilmAlbum(album)
                    val movie = (FROM.find(title) ?: FROM.find(album))?.groupValues?.get(1)?.trim()
                        ?: ITunesCatalog.movieName(album).takeIf { onMovieAlbum && it.isNotEmpty() }
                        ?: return null
                    CatalogItem(
                        SONG + id, "song", ImportMatcher.cleanTitle(title.replace(FROM, " ")).first, movie, year,
                        people(more, "primary_artists") ?: more?.text("music"), art,
                        durationMs = more?.text("duration")?.toLongOrNull()?.times(1000), compilation = !onMovieAlbum,
                        language = language,
                    )
                }
                "album" -> {
                    if (FROM.containsMatchIn(title) || !isFilmAlbum(title)) return null
                    val name = ITunesCatalog.movieName(title)
                    CatalogItem(
                        MOVIE + id, "movie", name, name, year, more?.text("music") ?: people(more, "primary_artists"), art,
                        trackCount = (more?.text("song_count") ?: r.text("list_count"))?.toIntOrNull() ?: 0, language = language,
                    )
                }
                else -> null
            }
        }
    }
}
