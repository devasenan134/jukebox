package io.github.devasenan134.isaipetti.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Which of your own playlists each song is in, so song lists can mark saved songs (like Spotify's
 * check mark). Loaded at start and refreshed after you change a playlist.
 */
class MyPlaylists(private val api: SubsonicApi, private val session: SessionStore) {
    private val scope = MainScope()
    private var job: Job? = null

    private val _songs = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Song id -> names of your playlists it's in. */
    val songs: StateFlow<Map<String, List<String>>> = _songs

    fun refresh() {
        val username = session.credentials.value?.username ?: return
        job?.cancel()
        job = scope.launch {
            runCatching {
                val mine = api.playlists().filter { it.owner == username }
                val full = coroutineScope { mine.map { p -> async { runCatching { api.playlist(p.id) }.getOrDefault(p) } }.awaitAll() }
                val index = mutableMapOf<String, MutableList<String>>()
                full.forEach { p -> p.entry.map { it.id }.distinct().forEach { index.getOrPut(it) { mutableListOf() } += p.name } }
                _songs.value = index
            }
        }
    }

    fun clear() {
        job?.cancel()
        _songs.value = emptyMap()
    }
}
