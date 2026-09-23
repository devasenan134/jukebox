package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Everything you played recently, newest first, for "Recently played" on Home: songs, movies,
 * playlists, composers, artists and Liked songs. Kept on the phone.
 */
class RecentActivity(context: Context) {
    enum class Kind { Song, Movie, Playlist, Composer, Artist, Liked, Mix }

    @Serializable
    data class Item(
        val kind: Kind,
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val coverArt: String? = null,
        /** For a song: what's needed to play it again. */
        val song: SongRef? = null,
        val playedAt: Long = 0,
    )

    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(Item.serializer())

    private val _items = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty(),
    )
    val items: StateFlow<List<Item>> = _items

    fun played(item: Item) {
        val stamped = item.copy(playedAt = System.currentTimeMillis())
        val list = (listOf(stamped) + _items.value.filterNot { it.kind == item.kind && it.id == item.id }).take(MAX)
        _items.value = list
        prefs.edit { putString(KEY, Json.encodeToString(serializer, list)) }
    }

    fun song(song: Song) = played(Item(Kind.Song, song.id, song.title, song.artist, song.coverArt, song = song.toRef()))
    fun movie(album: Album) = played(Item(Kind.Movie, album.id, album.name, album.artist, album.coverArt))
    fun playlist(playlist: Playlist) = played(Item(Kind.Playlist, playlist.id, playlist.name, "Playlist", playlist.coverArt))
    fun composer(artist: Artist) = played(Item(Kind.Composer, artist.id, artist.name, "Composer", artist.coverArt))
    fun artist(id: String, name: String, coverArt: String?) = played(Item(Kind.Artist, id, name, "Artist", coverArt))
    fun liked() = played(Item(Kind.Liked, "liked", "Liked songs", "Playlist"))
    fun mix(mix: Mix) =
        played(Item(Kind.Mix, mix.id, mix.title, (if (mix.endless) "Station by " else "By ") + MIX_AUTHOR, mix.covers.firstOrNull()))

    fun forget(kind: Kind, id: String) {
        val list = _items.value.filterNot { it.kind == kind && it.id == id }
        _items.value = list
        prefs.edit { putString(KEY, Json.encodeToString(serializer, list)) }
    }

    fun clear() {
        _items.value = emptyList()
        prefs.edit { remove(KEY) }
    }

    private companion object {
        const val KEY = "activity"
        const val MAX = 20
    }
}
