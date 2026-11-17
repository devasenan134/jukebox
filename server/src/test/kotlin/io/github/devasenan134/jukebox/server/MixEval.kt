package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.JukeboxLibrary
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.random.Random

/**
 * The mixes' scoreboard (see build.gradle.kts, task evaluateMixes): how well each [Tuning] of
 * [MixMaker.similarTo], the score every mix, station and suggestion starts from, finds songs one person
 * really put together, on a copy of the real database. Two tests, each a list of questions:
 *
 *  - Playlists: for each song of each of their playlists of 8+ songs, the rest of the playlist are the seeds.
 *    Where does that song rank among the library? (What playlist suggestions are asked.)
 *  - Listening: for every two songs they played one after the other (within 30 minutes, from different movies),
 *    the first is the seed. Where does the second rank? (What a song's station is asked.)
 *
 * Each line: the share of questions whose answer is in the first 10 and 50, and the mean reciprocal rank.
 * Nobody's listening history is used to answer (History.EMPTY, no Together), so nothing hidden leaks in.
 * The last block plays the real station and playlist suggestions end to end (with their random picks).
 */
fun main(args: Array<String>) = runBlocking {
    val (dbPath, featuresDb, username) = args.toList() + List(3) { "" }
    val db = Db(dbPath)
    val source = JukeboxLibrary(db, featuresDb.ifBlank { null })
    val lib = source.snapshot() ?: error("Couldn't read $dbPath")
    val userId = db.tx { queryOne("SELECT id FROM users WHERE username = ?", username) { it.getLong(1) } } ?: error("No user $username")
    val popularity = source.popularity(lib)
    println("${lib.songs.size} songs, ${lib.analyzed} analyzed, ${lib.links.count { it.isNotEmpty() }} with JioSaavn links, ${lib.fame.count { !it.isNaN() }} with JioSaavn plays")

    val playlists = db.tx {
        query(
            """SELECT p.name, e.recording_id FROM playlist_entries e JOIN playlists p ON p.id = e.playlist_id
               WHERE p.owner_id = ? ORDER BY e.playlist_id, e.position""",
            userId,
        ) { rs -> rs.getString(1) to lib.index[rs.getString(2)] }
    }.filter { it.second != null }.groupBy({ it.first }, { it.second!! }).mapValues { it.value.distinct() }.filterValues { it.size >= 8 }

    // Every play with its time: counted plays (events) and the apps' play reports (plays), skips left out.
    val played = db.tx {
        query("SELECT json_extract(payload, '$.recording'), at FROM events WHERE user_id = ? AND type = 'played'", userId) { rs ->
            lib.index[rs.getString(1)]?.let { it to rs.getLong(2) }
        }.filterNotNull() + query("SELECT song_id, at FROM plays WHERE user_id = ? AND skipped = 0", userId) { rs ->
            lib.index[rs.getString(1)]?.let { it to rs.getLong(2) }
        }.filterNotNull()
    }.sortedBy { it.second }
    val pairs = played.zipWithNext()
        .filter { (a, b) -> b.second - a.second in 1..30 * 60_000 && lib.songs[a.first].albumId != lib.songs[b.first].albumId }
        .map { it.first.first to it.second.first }.distinct()

    // Playlist questions: each song against the rest of its playlist (at most 40 a playlist, so big ones don't dominate).
    val playlistQuestions = playlists.values.flatMap { songs ->
        songs.shuffled(Random(songs.size)).take(40).map { held -> songs.filter { it != held } to held }
    }
    println("${playlists.size} playlists of 8+ songs: ${playlistQuestions.size} questions; ${pairs.size} pairs of songs played one after the other")
    // How often the answer is directly linked (JioSaavn lists one with the other), or by the same composer.
    fun linked(a: Int, b: Int) = b in lib.links[a] || a in lib.links[b]
    fun sameComposer(a: Int, b: Int) = lib.songs[a].composer?.id != null && lib.songs[a].composer?.id == lib.songs[b].composer?.id
    println(String.format(
        "Listening: next song linked to the last %.1f%%, same composer %.1f%%. Playlists: hidden song linked to a seed %.1f%%, same composer as a seed %.1f%%\n",
        100.0 * pairs.count { (a, b) -> linked(a, b) } / pairs.size, 100.0 * pairs.count { (a, b) -> sameComposer(a, b) } / pairs.size,
        100.0 * playlistQuestions.count { (s, h) -> s.any { linked(it, h) } } / playlistQuestions.size,
        100.0 * playlistQuestions.count { (s, h) -> s.any { sameComposer(it, h) } } / playlistQuestions.size,
    ))

    // Today's default against the one before JioSaavn's links, and a few neighbours of the default to try.
    val tunings = linkedMapOf(
        "before links (sound, people, year)" to Tuning(links = 0f, composer = 1.2f, year = 0.6f, fame = 0f),
        "default" to Tuning(),
        "default, no links" to Tuning(links = 0f),
        "default, links 6" to Tuning(links = 6f),
        "default, composer 3" to Tuning(composer = 3f),
        "default, sound 0.7" to Tuning(sound = 0.7f),
    )

    fun languageOf(seeds: List<Int>) = seeds.mapNotNull { lib.language[it] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /**
     * Where [answer] ranks by [scores] (1 = best), among songs that aren't [seeds]; songs with the same score
     * count half (else a score most songs share would rank first). Null if it can't be suggested at all.
     */
    fun rank(scores: FloatArray, seeds: Collection<Int>, answer: Int): Int? {
        val target = scores[answer]
        if (target.isNaN()) return null
        val skip = seeds.toSet()
        var better = 0
        var same = 0
        for (i in scores.indices) {
            if (i == answer || i in skip || scores[i].isNaN()) continue
            if (scores[i] > target) better++ else if (scores[i] == target) same++
        }
        return better + same / 2 + 1
    }

    class Score { var n = 0; var top10 = 0; var top50 = 0; var rr = 0.0
        fun add(r: Int?) { n++; if (r != null) { if (r <= 10) top10++; if (r <= 50) top50++; rr += 1.0 / r } }
        override fun toString() = if (n == 0) "–" else String.format("%5.1f%% %5.1f%% %.3f", 100.0 * top10 / n, 100.0 * top50 / n, rr / n)
    }

    println(String.format("%-34s %-24s %-24s", "", "playlists: top10 top50 MRR", "listening: top10 top50 MRR"))
    for ((name, tuning) in tunings) {
        val maker = MixMaker(lib, History.EMPTY, emptyMap(), popularity, emptyMap(), personSeed = 1, today = LocalDate.now(), tuning = tuning)
        val p = Score()
        for ((seeds, held) in playlistQuestions) p.add(rank(maker.similarTo(seeds, languageOf(seeds)), seeds, held))
        val l = Score()
        for ((a, b) in pairs) l.add(rank(maker.similarTo(listOf(a), lib.language[a]), listOf(a), b))
        println(String.format("%-34s %-24s %-24s", name, p, l))
    }

    // End to end: the real station (its random picks from the best) and playlist suggestions, for the first two tunings and the default.
    println("\nEnd to end (station: is the next song in its first 50; suggestions: hidden song in the first 50)")
    for ((name, tuning) in listOf("before links" to Tuning(links = 0f, composer = 1.2f, year = 0.6f, fame = 0f), "default" to Tuning())) {
        val maker = MixMaker(lib, History.EMPTY, emptyMap(), popularity, emptyMap(), personSeed = 1, today = LocalDate.now(), tuning = tuning)
        var hits = 0
        for ((a, b) in pairs) {
            val station = maker.radio("radio-song-${lib.songs[a].id}", emptySet(), 51, Random(7))?.songs.orEmpty().drop(1)
            if (station.any { lib.index[it.id] == b }) hits++
        }
        var found = 0
        for ((seeds, held) in playlistQuestions) {
            if (maker.recommend(seeds.map { lib.songs[it].id }, 50, 0).any { lib.index[it.id] == held }) found++
        }
        println(String.format("%-20s station %5.1f%%   suggestions %5.1f%%", name, 100.0 * hits / pairs.size.coerceAtLeast(1), 100.0 * found / playlistQuestions.size.coerceAtLeast(1)))
    }
}
