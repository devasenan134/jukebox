package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.JukeboxLibrary
import kotlinx.coroutines.runBlocking
import java.io.File

/** See build.gradle.kts, task previewSearch: prints what search finds for each query. */
fun main(args: Array<String>) = runBlocking {
    val (dbPath, castFile) = args.toList()
    val search = LibrarySearch(JukeboxLibrary(Db(dbPath)), File(castFile))
    for (query in args.drop(2)) {
        val started = System.currentTimeMillis()
        val r = search.search(query)
        println("\n##### \"$query\" (${System.currentTimeMillis() - started} ms)")
        r.people.take(5).forEach { println("  person: ${it.name} ${it.roles} songs=${it.songCount} movies=${it.movieCount}") }
        r.movies.take(5).forEach { println("  movie:  ${it.name} (${it.year}) ${it.reason ?: ""}") }
        r.songs.take(6).forEach { println("  song:   ${it.song.title} — ${it.song.album} ${it.reason ?: ""}") }
    }
}
