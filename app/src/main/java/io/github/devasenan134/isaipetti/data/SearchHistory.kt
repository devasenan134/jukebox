package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Your search history, newest first, kept on the phone: the words you searched, and what you
 * picked from the results (songs you played, movies and composers you opened).
 */
class SearchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)

    private val queryList = Saved("queries", String.serializer(), MAX_QUERIES)
    private val songList = Saved("songs", SongRef.serializer(), MAX_PICKS)
    private val albumList = Saved("albums", Album.serializer(), MAX_PICKS)
    private val artistList = Saved("artists", Artist.serializer(), MAX_PICKS)

    val queries: StateFlow<List<String>> = queryList.flow
    val songs: StateFlow<List<SongRef>> = songList.flow
    val albums: StateFlow<List<Album>> = albumList.flow
    val artists: StateFlow<List<Artist>> = artistList.flow

    /** Saves a search you actually used (not every half-typed word). */
    fun add(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        queryList.put(q) { it.equals(q, ignoreCase = true) }
    }

    fun remove(query: String) = queryList.save(queryList.flow.value - query)

    /** A song you played from the search results. */
    fun picked(song: Song) = songList.put(song.toRef()) { it.id == song.id }

    /** A movie you opened from the search results (without its song list). */
    fun picked(album: Album) = albumList.put(album.copy(song = emptyList())) { it.id == album.id }

    /** A composer you opened from the search results. */
    fun picked(artist: Artist) = artistList.put(artist.copy(album = emptyList())) { it.id == artist.id }

    /** Clears the searched words (the "Clear" button). */
    fun clear() = queryList.save(emptyList())

    /** Everything, on logout. */
    fun clearAll() = listOf(queryList, songList, albumList, artistList).forEach { it.save(emptyList()) }

    /** One list saved in the phone's preferences, newest first and capped at [max]. */
    private inner class Saved<T>(private val key: String, item: KSerializer<T>, private val max: Int) {
        private val serializer = ListSerializer(item)
        val flow = MutableStateFlow(
            prefs.getString(key, null)?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty(),
        )

        fun put(value: T, same: (T) -> Boolean) = save((listOf(value) + flow.value.filterNot(same)).take(max))

        fun save(list: List<T>) {
            flow.value = list
            prefs.edit { putString(key, Json.encodeToString(serializer, list)) }
        }
    }

    private companion object {
        const val MAX_QUERIES = 15
        const val MAX_PICKS = 15
    }
}
