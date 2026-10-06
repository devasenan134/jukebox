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
    val maker = MixMaker(
        lib, history, emptyMap(), source.popularity(lib), emptyMap(), personSeed = username.hashCode().toLong(), today = LocalDate.now(),
        together = Together.build(emptyList(), playlists),
    )
    println("\n${playlists.size} playlists count towards which songs go together")
    for (section in maker.home()) {
        println("\n##### ${section.title}")
        for (mix in section.mixes) {
            val mixLanguages = mix.songs.mapNotNull { s -> lib.index[s.id] }.groupingBy { languages[it] ?: "unknown" }.eachCount()
                .entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }
            println("\n== ${mix.title} (${mix.songs.size} songs: $mixLanguages) — ${mix.subtitle}")
            mix.songs.take(8).forEach { s -> println("   ${s.title} — ${s.album} (${s.year ?: "?"}) [${lib.index[s.id]?.let { languages[it] } ?: "?"}]") }
        }
    }
}
