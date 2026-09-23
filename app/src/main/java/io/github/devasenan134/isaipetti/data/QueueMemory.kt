package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Where you left off in a playlist: the queue in the order it was playing (so a shuffled order is
 * kept as it was) and which song you were on. Not the position inside the song. Kept on the phone.
 */
class QueueMemory(context: Context) {
    private val prefs = context.getSharedPreferences("queues", Context.MODE_PRIVATE)

    @Serializable
    data class Saved(val songIds: List<String>, val currentId: String, val savedAt: Long)

    fun get(source: String): Saved? =
        prefs.getString(source, null)?.let { runCatching { Json.decodeFromString(Saved.serializer(), it) }.getOrNull() }

    fun save(source: String, songIds: List<String>, currentId: String) {
        if (songIds.isEmpty()) return
        val now = System.currentTimeMillis()
        // A small index of when each one was saved, so old ones can be dropped without reading them all.
        val index = savedTimes() + (source to now)
        val keep = index.entries.sortedByDescending { it.value }.take(MAX).associate { it.key to it.value }
        prefs.edit {
            putString(source, Json.encodeToString(Saved.serializer(), Saved(songIds, currentId, now)))
            (index.keys - keep.keys).forEach { remove(it) }
            putString(INDEX, Json.encodeToString(indexSerializer, keep))
        }
    }

    private val indexSerializer = MapSerializer(String.serializer(), Long.serializer())

    private fun savedTimes(): Map<String, Long> =
        prefs.getString(INDEX, null)?.let { runCatching { Json.decodeFromString(indexSerializer, it) }.getOrNull() }.orEmpty()

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val MAX = 30
        const val INDEX = "_index"
    }
}
