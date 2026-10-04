package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Songs you played recently, newest first, kept on the phone (for quick sharing in chats). */
class RecentSongs(context: Context) {
    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(SongRef.serializer())

    private val _songs = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty(),
    )
    val songs: StateFlow<List<SongRef>> = _songs

    /** Called when a song starts playing. Clip details aren't kept: it's the song that was played. */
    fun played(song: SongRef) {
        val plain = song.copy(clipStartMs = null, clipEndMs = null)
        val list = (listOf(plain) + _songs.value.filter { it.id != plain.id }).take(MAX)
        _songs.value = list
        prefs.edit { putString(KEY, Json.encodeToString(serializer, list)) }
    }

    fun clear() {
        _songs.value = emptyList()
        prefs.edit { remove(KEY) }
    }

    private companion object {
        const val KEY = "songs"
        const val MAX = 20
    }
}
