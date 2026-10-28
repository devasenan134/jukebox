package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.JukeboxLibrary
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * See build.gradle.kts, task previewMixes: prints how the library's languages were worked out, and
 * every mix one person would get today with the languages in it.
 */
fun main(args: Array<String>) = runBlocking {
    val (dbPath, featuresDb, username) = args.toList() + List(3) { "" }
    val db = Db(dbPath)
    val source = JukeboxLibrary(db, featuresDb.ifBlank { null })
    val lib = source.snapshot() ?: error("Couldn't read $dbPath")
    val userId = db.tx { queryOne("SELECT id FROM users WHERE username = ?", username) { it.getLong(1) } }
    val history = userId?.let { source.history(it, lib) } ?: History.EMPTY
    println("${lib.songs.size} songs, ${lib.analyzed} analyzed; $username has ${history.playCount.size} played and ${history.starred.size} liked songs")

    // Languages: how many of each, how many were tagged, and a few examples of each to check by eye.
    val languages = lib.language
    val steps = IntArray(lib.songs.size)
    Languages.infer(lib.songs, lib.sound, lib.moods["indianfilm"], lib.moods["western"], steps)
    val stepNames = listOf("unknown", "own clue", "movie/people", "Indian or Western sound", "nearest songs")
    println("\nLanguages, and which step decided them:")
    languages.indices.groupBy { languages[it] ?: "unknown" }.entries.sortedByDescending { it.value.size }.forEach { (language, list) ->
        println("  $language: ${list.size}")
        list.groupBy { steps[it] }.toSortedMap().forEach { (step, songsHere) ->
            // Small languages in full, so each song can be checked; big ones by a few examples.
            val shown = if (list.size <= 40) songsHere else songsHere.shuffled(kotlin.random.Random(1)).take(5)
            val examples = shown.joinToString(" | ") { "${lib.songs[it].title} (${lib.songs[it].album})" }
            println("      ${stepNames[step]}: ${songsHere.size}  e.g. $examples")
        }
    }

    val playlists = source.playlists(lib)
    // As the server does: skips, and what was shown before today (see MixService.shown).
    val zone = System.getenv("MIX_TIMEZONE")?.let { java.time.ZoneId.of(it) } ?: java.time.ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val skips = db.tx {
        query("SELECT song_id, sum(skipped), sum(1 - skipped) FROM plays WHERE user_id = ? GROUP BY song_id", userId ?: -1) { rs ->
            lib.index[rs.getString(1)]?.let { it to SkipStats(rs.getInt(2), rs.getInt(3)) }
        }.filterNotNull().toMap()
    }
    val shown = db.tx {
        query(
            """SELECT l.id, l.mix_id, l.served_at, s.song_id FROM suggestion_lists l JOIN suggestions s ON s.list_id = l.id
               WHERE l.user_id = ? AND l.kind IN ('mix', 'recommend') AND l.served_at >= ? AND l.served_at < ? AND (l.kind = 'recommend' OR s.position < 25)""",
            userId ?: -1, start - 70 * MixMaker.DAY, start,
        ) { rs -> Triple(rs.getLong(1), rs.getString(2) to rs.getLong(3), rs.getString(4)) }
    }.groupBy { it.first }.values.map { list ->
        ShownList(list.first().second.first, java.time.Instant.ofEpochMilli(list.first().second.second).atZone(zone).toLocalDate(), list.mapNotNull { lib.index[it.third] })
    }
    println("${shown.size} lists shown before today, ${skips.size} songs with skips; time zone $zone")
    val maker = MixMaker(
        lib, history, skips, source.popularity(lib), emptyMap(), personSeed = (userId ?: 0L) * 1_000_003L, today = today,
        zone = zone, together = Together.build(emptyList(), playlists), shown = shown,
    )
    println("\n${playlists.size} playlists count towards which songs go together")
    val extra = listOf("deepcuts", "gems", "fresh") + maker.topMixes().map { it.id }.filter { it.startsWith("because-") }.take(1)
    val sections = maker.home() + MixSection("extra", "Not on Home today", extra.mapNotNull { maker.byId(it) })
    for (section in sections) {
        println("\n##### ${section.title}")
        for (mix in section.mixes) {
            val mixLanguages = mix.songs.mapNotNull { s -> lib.index[s.id] }.groupingBy { languages[it] ?: "unknown" }.eachCount()
                .entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }
            println("\n== ${mix.title} (${mix.songs.size} songs: $mixLanguages) — ${mix.subtitle}")
            mix.songs.take(8).forEach { s -> println("   ${s.title} — ${s.album} (${s.year ?: "?"}) [${lib.index[s.id]?.let { languages[it] } ?: "?"}]") }
        }
    }
}
