package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Your recent searches, newest first, kept on the phone. */
class SearchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(String.serializer())

    private val _queries = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty(),
    )
    val queries: StateFlow<List<String>> = _queries

    /** Saves a search you actually used (not every half-typed word). */
    fun add(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        save((listOf(q) + _queries.value.filterNot { it.equals(q, ignoreCase = true) }).take(MAX))
    }

    fun remove(query: String) = save(_queries.value - query)
    fun clear() = save(emptyList())

    private fun save(list: List<String>) {
        _queries.value = list
        prefs.edit { putString(KEY, Json.encodeToString(serializer, list)) }
    }

    private companion object {
        const val KEY = "queries"
        const val MAX = 15
    }
}
