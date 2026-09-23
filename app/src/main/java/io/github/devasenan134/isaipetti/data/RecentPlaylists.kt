package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Playlists you played from recently, newest first, kept on the phone. Navidrome records which
 * songs you play but not which playlist they came from, so the app remembers it.
 */
class RecentPlaylists(context: Context) {
    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(Playlist.serializer())

    private val _playlists = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty(),
    )
    val playlists: StateFlow<List<Playlist>> = _playlists

    /** Called when you start playing from a playlist. Its songs aren't kept, just what the card shows. */
    fun played(playlist: Playlist) {
        val card = playlist.copy(entry = emptyList())
        val list = (listOf(card) + _playlists.value.filter { it.id != card.id }).take(MAX)
        _playlists.value = list
        prefs.edit { putString(KEY, Json.encodeToString(serializer, list)) }
    }

    /** A deleted playlist. */
    fun forget(id: String) {
        val list = _playlists.value.filter { it.id != id }
        _playlists.value = list
        prefs.edit { putString(KEY, Json.encodeToString(serializer, list)) }
    }

    fun clear() {
        _playlists.value = emptyList()
        prefs.edit { remove(KEY) }
    }

    private companion object {
        const val KEY = "playlists"
        const val MAX = 15
    }
}
