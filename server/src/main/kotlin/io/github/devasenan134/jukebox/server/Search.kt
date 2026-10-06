package io.github.devasenan134.jukebox.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("search")

/** Someone in the results: a composer, singer, lyricist or actor (or several of these). */
@Serializable
data class PersonHit(
    val id: String,
    val name: String,
    /** "composer", "singer", "lyricist", "actor". */
    val roles: List<String>,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val movieCount: Int = 0,
)

@Serializable
data class MovieHit(
    val id: String,
    val name: String,
    val year: Int? = null,
    val composer: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    /** Why it matched when it isn't the name, e.g. "Starring Vijay". */
    val reason: String? = null,
)

@Serializable
data class SongHit(val song: MixSong, val reason: String? = null)

@Serializable
data class SearchResults(
    val people: List<PersonHit> = emptyList(),
    val movies: List<MovieHit> = emptyList(),
    val songs: List<SongHit> = emptyList(),
)

/** A person's page: the movies they're in (actors) or made music for, and their songs. */
@Serializable
data class PersonPage(val person: PersonHit, val movies: List<MovieHit>, val songs: List<MixSong>)

/**
 * Search that forgives spelling: "kanave", "kanavae" and "kanaavey" all find "Kanave". Looks at song
 * titles, movies, singers, composers, lyricists (from the songs' tags) and actors (from the movie
 * cast file, made by the library tools from Wikipedia).
 */
class LibrarySearch(private val music: MusicSource, private val castFile: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    @Volatile private var index: Index? = null

    suspend fun search(query: String): SearchResults {
        val q = Fuzzy.words(query)
        if (q.isEmpty()) return SearchResults()
        val ix = index() ?: return SearchResults()
        return ix.search(q, query)
    }

    suspend fun person(id: String): PersonPage {
        val ix = index() ?: throw ApiError(io.ktor.http.HttpStatusCode.NotFound, "The library isn't available")
        return ix.page(id) ?: throw ApiError(io.ktor.http.HttpStatusCode.NotFound, "Nobody with that id")
    }

    private suspend fun index(): Index? = mutex.withLock {
        val snapshot = music.snapshot() ?: return null
        val castVersion = castFile?.takeIf { it.isFile }?.let { "${it.length()}-${it.lastModified()}" } ?: "none"
        val version = snapshot.version + "/" + castVersion
        index?.takeIf { it.version == version } ?: Index(snapshot, readCast(), version).also {
            index = it
            log.info("Search index: ${snapshot.songs.size} songs, ${it.movies.size} movies, ${it.people.size} people")
        }
    }

    @Serializable private data class CastLine(val album: String, val year: Int = 0, val starring: List<String> = emptyList())

    /** (movie name in lower case, year) to its actors. */
    private fun readCast(): Map<Pair<String, Int>, List<String>> {
        val file = castFile?.takeIf { it.isFile } ?: return emptyMap()
        return file.readLines().mapNotNull { line ->
            runCatching { json.decodeFromString<CastLine>(line) }.getOrNull()?.takeIf { it.starring.isNotEmpty() }
        }.associate { (it.album.lowercase() to it.year) to it.starring }
    }

    /** Everything searchable, prepared once per library version. */
    private class Index(val snapshot: LibrarySnapshot, cast: Map<Pair<String, Int>, List<String>>, val version: String) {
        val songs = snapshot.songs

        class Movie(val id: String, val name: String, val year: Int, val composer: String?, val songs: List<Int>, val actors: List<Person>) {
            val words = Fuzzy.words(name)
        }

        class Someone(val id: String, val name: String) {
            val words = Fuzzy.words(name)
            val roles = linkedSetOf<String>()
            val songs = linkedSetOf<Int>()
            val movies = linkedSetOf<String>()
            val coverArt get() = if (id.startsWith(ACTOR)) null else "ar-$id"
        }

        val people = linkedMapOf<String, Someone>()
        val movies: Map<String, Movie>
        /** Spellings of one name ("Vaali", "Vaalee") are one person: the one with the most songs speaks for them all. */
        private val sameName: Map<String, List<Someone>>
        private val titleWords = songs.map { Fuzzy.words(it.title) }

        init {
            fun someone(p: Person, role: String) = people.getOrPut(p.id) { Someone(p.id, p.name) }.also { it.roles += role }
            songs.forEachIndexed { i, s ->
                s.composer?.let { someone(it, "composer").apply { songs += i; movies += s.albumId } }
                s.singers.forEach { someone(it, "singer").songs += i }
                s.lyricists.forEach { someone(it, "lyricist").apply { songs += i; movies += s.albumId } }
            }
            val byKey = people.values.groupBy { Fuzzy.key(it.name) }.mapValues { (_, same) -> same.maxBy { it.songs.size } }
            movies = snapshot.byAlbum.mapValues { (id, list) ->
                val first = songs[list.first()]
                val year = list.maxOf { songs[it].year }
                // An actor who also sings or writes here is one person (their person in the catalog).
                val actors = (cast[first.album.lowercase() to year] ?: emptyList()).let(::withoutRepeats).map { name ->
                    val key = Fuzzy.key(name)
                    byKey[key]?.let { Person(it.id, it.name) } ?: Person(ACTOR + key, name)
                }
                actors.forEach { a -> someone(a, "actor").apply { movies += id; songs += list } }
                Movie(id, first.album, year, first.composer?.name, list, actors)
            }
            sameName = people.values.groupBy { Fuzzy.key(it.name) }
        }

        private fun group(p: Someone) = sameName[Fuzzy.key(p.name)] ?: listOf(p)
        private fun main(p: Someone) = group(p).maxBy { it.songs.size }

        fun search(q: List<String>, raw: String): SearchResults {
            val foundPeople = people.values.mapNotNull { p -> Fuzzy.match(q, p.words).takeIf { it > 0 }?.let { main(p) to it } }
                .sortedWith(compareByDescending<Pair<Someone, Double>> { it.second }.thenByDescending { it.first.songs.size })
                .distinctBy { it.first.id }
                .take(12)

            val foundMovies = movies.values.mapNotNull { m ->
                val byName = Fuzzy.match(q, m.words)
                val actor = m.actors.map { it to Fuzzy.match(q, Fuzzy.words(it.name)) * 0.92 }.maxByOrNull { it.second }
                val byComposer = m.composer?.let { Fuzzy.match(q, Fuzzy.words(it)) * 0.7 } ?: 0.0
                val best = maxOf(byName, actor?.second ?: 0.0, byComposer)
                if (best <= 0) return@mapNotNull null
                val reason = if (actor != null && actor.second == best && best > byName) "Starring ${actor.first.name}" else null
                Triple(m, best, reason)
            }.sortedWith(compareByDescending<Triple<Movie, Double, String?>> { it.second }.thenByDescending { it.first.year }).take(30)

            val foundSongs = songs.indices.mapNotNull { i ->
                val s = songs[i]
                val byTitle = Fuzzy.match(q, titleWords[i])
                val byMovie = (movies[s.albumId]?.let { Fuzzy.match(q, it.words) } ?: 0.0) * 0.85
                val bySinger = s.singers.maxOfOrNull { people[it.id]?.let { p -> Fuzzy.match(q, p.words) } ?: 0.0 }?.times(0.8) ?: 0.0
                val lyricist = s.lyricists.map { it to (people[it.id]?.let { p -> Fuzzy.match(q, p.words) } ?: 0.0) * 0.8 }.maxByOrNull { it.second }
                val byComposer = (s.composer?.let { people[it.id] }?.let { Fuzzy.match(q, it.words) } ?: 0.0) * 0.7
                val best = maxOf(byTitle, byMovie, bySinger, lyricist?.second ?: 0.0, byComposer)
                if (best <= 0) return@mapNotNull null
                val reason = if (lyricist != null && lyricist.second == best && best > maxOf(byTitle, byMovie, bySinger)) "Lyrics by ${lyricist.first.name}" else null
                Triple(i, best, reason)
            }.sortedWith(compareByDescending<Triple<Int, Double, String?>> { it.second }.thenBy { songs[it.first].title.lowercase() }).take(50)

            log.debug("search '{}': {} people, {} movies, {} songs", raw, foundPeople.size, foundMovies.size, foundSongs.size)
            return SearchResults(
                people = foundPeople.map { hit(it.first) },
                movies = foundMovies.map { (m, _, reason) -> hit(m, reason) },
                songs = foundSongs.map { (i, _, reason) -> SongHit(song(i), reason) },
            )
        }

        fun page(id: String): PersonPage? {
            val p = people[id] ?: return null
            val all = group(p)
            val list = all.flatMap { it.movies }.distinct().mapNotNull { movies[it] }.sortedByDescending { it.year }
            return PersonPage(hit(p), list.map { hit(it) }, all.flatMap { it.songs }.distinct().sortedBy { songs[it].title.lowercase() }.map(::song))
        }

        private fun hit(p: Someone): PersonHit {
            val all = group(p)
            return PersonHit(
                p.id, p.name, all.flatMap { it.roles }.distinct(),
                coverArt = p.coverArt ?: p.movies.firstOrNull()?.let { "al-$it" },
                songCount = all.flatMap { it.songs }.distinct().size, movieCount = all.flatMap { it.movies }.distinct().size,
            )
        }

        private fun hit(m: Movie, reason: String? = null) = MovieHit(
            m.id, m.name, m.year.takeIf { it > 0 }, m.composer, "al-${m.id}", m.songs.size, reason,
        )

        private fun song(i: Int): MixSong = songs[i].let { s ->
            MixSong(
                id = s.id, title = s.title, artist = s.artist.takeIf { it.isNotBlank() }, album = s.album, albumId = s.albumId,
                coverArt = s.coverArt, duration = s.duration, year = s.year.takeIf { it > 0 }, artists = s.singers.map { PersonDto(it.id, it.name) },
            )
        }

        /** Wikipedia sometimes lists a full name and the usual one ("C. Joseph Vijay", "Vijay"): keep the usual one. */
        private fun withoutRepeats(names: List<String>): List<String> {
            val keys = names.map { Fuzzy.words(it).toSet() }
            return names.filterIndexed { i, _ -> keys.indices.none { j -> j != i && keys[j].isNotEmpty() && keys[j] != keys[i] && keys[i].containsAll(keys[j]) } }
                .distinctBy { Fuzzy.key(it) }
        }

        companion object {
            const val ACTOR = "actor-"
        }
    }
}

/**
 * Spelling-tolerant matching for Tamil names written in English letters, where the same word is
 * spelled many ways: zh/l, ee/i, th/t, doubled letters, g/k, d/t...
 */
object Fuzzy {
    private val brackets = Regex("\\(.*?\\)|\\[.*?\\]")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private val softH = Regex("(?<=[bcdgjklmnprstv])h")
    private val repeats = Regex("(.)\\1+")
    private val spellings = listOf(
        "zh" to "l", "ae" to "e", "ai" to "e", "ay" to "e", "ee" to "i", "oo" to "u", "ou" to "u",
        "w" to "v", "y" to "i", "q" to "k", "x" to "ks", "z" to "s",
    )

    /** The words of [text], each reduced to how it sounds. */
    fun words(text: String): List<String> =
        text.lowercase().replace(brackets, " ").split(nonWord).filter { it.isNotEmpty() }.map(::sound).filter { it.isNotEmpty() }

    /** One key for a whole name ("Vijay" and "Vijai" give the same). */
    fun key(name: String): String = words(name).joinToString("")

    fun sound(word: String): String {
        if (word.any { it !in 'a'..'z' && it !in '0'..'9' }) return word // Tamil script and the like: as is
        var s = word
        for ((from, to) in spellings) s = s.replace(from, to)
        s = s.replace(softH, "")
        s = s.map { when (it) { 'g' -> 'k'; 'd' -> 't'; 'b' -> 'p'; 'j' -> 's'; else -> it } }.joinToString("")
        return s.replace(repeats, "$1")
    }

    /**
     * How well the query's words match a name's words, 0 (no match) to 1. Every query word must be
     * close to some word (the last one may be half typed). Also tries the words run together, so
     * "kanavekanave" finds "Kanave Kanave".
     */
    fun match(query: List<String>, name: List<String>): Double {
        if (query.isEmpty() || name.isEmpty()) return 0.0
        var total = 0.0
        var allFound = true
        for ((n, q) in query.withIndex()) {
            val best = name.maxOf { word(q, it, typing = n == query.lastIndex, need = GOOD) }
            if (best < GOOD) { allFound = false; break }
            total += best
        }
        val byWords = if (allFound) total / query.size else 0.0
        val joined = word(query.joinToString(""), name.joinToString(""), typing = true, need = 0.8).takeIf { it >= 0.8 }?.times(0.95) ?: 0.0
        val score = maxOf(byWords, joined)
        if (score == 0.0) return 0.0
        // Prefer names the query covers fully: "roja" ranks the movie Roja above "Roja Poonthottam".
        return score * (0.85 + 0.15 * minOf(1.0, query.size.toDouble() / name.size))
    }

    /** How well [q] matches the word [w]; a score below [need] is only ever compared with [need], so it may be rough. */
    private fun word(q: String, w: String, typing: Boolean, need: Double = 0.0): Double {
        if (q == w) return 1.0
        if (typing && q.length >= 2 && w.startsWith(q)) return 0.96
        if (q.length < 3) return 0.0
        // A near miss counts for less than the real word, so exact matches come first
        // and short words need to be spelled nearly right ("kamal" doesn't find "kadal").
        // The lengths alone often rule a word out (it's at least that many edits away): skip the full comparison then.
        val longest = maxOf(q.length, w.length)
        val reachable = (1.0 - kotlin.math.abs(q.length - w.length).toDouble() / longest) * 0.9 >= need
        val whole = if (reachable) (1.0 - distance(q, w).toDouble() / longest) * 0.9 else 0.0
        val start = if (typing && w.length > q.length) (1.0 - distance(q, w.take(q.length)).toDouble() / q.length) * 0.85 else 0.0
        return maxOf(whole, start)
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur.also { cur = prev }
        }
        return prev[b.length]
    }

    private const val GOOD = 0.75
}
