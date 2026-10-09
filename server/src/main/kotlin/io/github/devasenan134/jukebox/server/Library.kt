package io.github.devasenan134.jukebox.server

/** One song, with what the mixes need to know about it. */
data class LibrarySong(
    val id: String,
    val title: String,
    val album: String,
    val albumId: String,
    /** The singers, as shown ("S. Janaki • Malaysia Vasudevan"). */
    val artist: String,
    val singers: List<Person>,
    val composer: Person?,
    val year: Int,
    val duration: Int,
    /** The language here ("Tamil", "Telugu"...), or "" if unknown. */
    val genre: String,
    val addedAt: Long = 0,
    val karaoke: Boolean = false,
    /** Who wrote the words. */
    val lyricists: List<Person> = emptyList(),
    /** Whether this track is a background score, theme music or BGM (from OBS or soundtrack). */
    val score: Boolean = false,
) {
    val coverArt get() = "al-$albumId"
}

data class Person(val id: String, val name: String)

/**
 * Everything the mixes are made from, as of one moment: the songs, and (if the analyzer has run)
 * what each one sounds like. Song positions in [songs] are used as indexes everywhere.
 */
class LibrarySnapshot(
    val songs: List<LibrarySong>,
    /** Per song: 512 numbers of length 1 from the CLAP model, or null if not analyzed (yet). */
    val sound: Array<FloatArray?>,
    /** Per description ("sad", "party"...): how well it fits each song compared with the others (z-score; NaN if not analyzed). */
    val moods: Map<String, FloatArray>,
    /** The descriptions' own CLAP numbers, to see how well a whole taste fits one. */
    val moodVectors: Map<String, FloatArray>,
    val tempo: FloatArray,
    val energy: FloatArray,
    /** Changes whenever the songs or the analysis change. */
    val version: String,
    /** How punchy the beats are (onset strength); NaN if unknown. */
    val rhythm: FloatArray = FloatArray(songs.size) { Float.NaN },
) {
    val index: Map<String, Int> = songs.withIndex().associate { (i, s) -> s.id to i }

    /** Each song's language ("Tamil", "English"...), worked out from its tags, people and sound; null if unknown. */
    val language: Array<String?> by lazy { Languages.infer(songs, sound, moods["indianfilm"], moods["western"]) }
    val analyzed = sound.count { it != null }
    val hasSound get() = analyzed >= songs.size / 4 && analyzed >= 20

    val byComposer: Map<String, List<Int>> = songs.indices.filter { songs[it].composer != null }.groupBy { songs[it].composer!!.id }
    val bySinger: Map<String, List<Int>> = buildMap<String, MutableList<Int>> {
        songs.forEachIndexed { i, s -> s.singers.forEach { getOrPut(it.id) { mutableListOf() } += i } }
    }
    val byAlbum: Map<String, List<Int>> = songs.indices.groupBy { songs[it].albumId }
    val people: Map<String, Person> = buildMap {
        songs.forEach { s -> s.composer?.let { put(it.id, it) }; s.singers.forEach { put(it.id, it) } }
    }
}

/** Plays, likes and ratings of one person, by song position in the snapshot. */
class History(
    val playCount: Map<Int, Int>,
    val lastPlayed: Map<Int, Long>,
    /** Each play with its time, most recent first. */
    val plays: List<Pair<Int, Long>>,
    val starred: Set<Int>,
    val starredAlbums: Set<String>,
    val starredArtists: Set<String>,
    val rating: Map<Int, Int>,
    /**
     * How much this person's playlists say they like each song: the ones they made, and (less) the ones
     * they liked. Already weighted (see [playlistTaste]).
     */
    val playlisted: Map<Int, Double> = emptyMap(),
) {
    /** Changes whenever this person plays or likes something, or changes their playlists. */
    val version = "${playCount.values.sum()}-${plays.firstOrNull()?.second}-${starred.size}-${starredAlbums.size}-${starredArtists.size}-${rating.size}" +
        "-${playlisted.size}-${playlisted.values.sum().hashCode()}"

    companion object {
        val EMPTY = History(emptyMap(), emptyMap(), emptyList(), emptySet(), emptySet(), emptySet(), emptyMap())
    }
}

/** A playlist as a taste signal: its songs, whether this person made it (or only liked it), and when each song was added. */
class PlaylistTaste(val songs: List<Pair<Int, Long>>, val own: Boolean)

/**
 * How much someone's playlists say they like each song. Putting a song in a playlist is a deliberate
 * choice, like a like, so it counts about half as much as liking it, a bit more if added in the last
 * 30 days. A small, careful playlist says more per song than a 500-song dump, and a playlist someone
 * else made that you liked says less than one you made. A song in several playlists adds up, to a limit.
 */
fun playlistTaste(playlists: List<PlaylistTaste>, now: Long): Map<Int, Double> {
    val out = HashMap<Int, Double>()
    for (p in playlists) {
        val songs = p.songs.distinctBy { it.first }
        if (songs.isEmpty()) continue
        val careful = kotlin.math.sqrt(30.0 / songs.size).coerceIn(0.4, 1.0)
        for ((i, added) in songs) {
            var w = (if (p.own) 1.0 else 0.4) * careful
            if (p.own && added > now - 30 * MixMaker.DAY) w += 0.3
            out.merge(i, w, Double::plus)
        }
    }
    return out.mapValues { it.value.coerceAtMost(2.0) }
}

/** Where the mixes get their music from. Tests use a pretend library. */
interface MusicSource {
    suspend fun snapshot(): LibrarySnapshot?
    suspend fun history(userId: Long, snapshot: LibrarySnapshot): History
    /** Total plays per song across everyone, for "popular" picks. */
    suspend fun popularity(snapshot: LibrarySnapshot): Map<Int, Int>
    /** Everyone's playlists, as songs by position in [snapshot] (for which songs people put together). */
    suspend fun playlists(snapshot: LibrarySnapshot): List<List<Int>> = emptyList()
}

/**
 * How well each description fits each song, compared with how it fits songs in general (a z-score):
 * +2 means "fits much better than most songs". Raw CLAP scores are all similar, so only this comparison says anything.
 */
fun moodScores(sound: Array<FloatArray?>, prompts: Map<String, FloatArray>): Map<String, FloatArray> =
    prompts.mapValues { (_, prompt) ->
        val raw = FloatArray(sound.size) { i -> sound[i]?.let { dot(it, prompt) } ?: Float.NaN }
        zScores(raw)
    }

fun zScores(values: FloatArray): FloatArray {
    val known = values.filter { !it.isNaN() }
    if (known.size < 2) return FloatArray(values.size) { Float.NaN }
    val mean = known.average()
    val sd = kotlin.math.sqrt(known.sumOf { (it - mean) * (it - mean) } / known.size).coerceAtLeast(1e-6)
    return FloatArray(values.size) { if (values[it].isNaN()) Float.NaN else ((values[it] - mean) / sd).toFloat() }
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
    return s
}
