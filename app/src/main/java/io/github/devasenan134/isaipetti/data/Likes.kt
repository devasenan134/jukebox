package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * What you've liked, for the hearts everywhere and the Your Library page.
 *
 * Songs and movies are liked in Navidrome itself ("starred"), so they're the same on every device
 * and in Navidrome's web page. Navidrome can't like playlists, so liked playlists are kept on this phone.
 * Changes show right away and are undone if Navidrome refuses them.
 */
class Likes(context: Context, private val api: SubsonicApi) {
    private val scope = MainScope()
    private val prefs = context.getSharedPreferences("likes", Context.MODE_PRIVATE)
    private val playlistSerializer = ListSerializer(Playlist.serializer())

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums

    private val _playlists = MutableStateFlow(
        prefs.getString(PLAYLISTS, null)?.let { runCatching { Json.decodeFromString(playlistSerializer, it) }.getOrNull() }.orEmpty(),
    )
    val playlists: StateFlow<List<Playlist>> = _playlists

    /** Loads your likes from Navidrome (at start, and when the library opens). */
    fun refresh() {
        scope.launch {
            runCatching { api.starred() }.onSuccess {
                _songs.value = it.song
                _albums.value = it.album
            }
        }
    }

    fun isLiked(song: Song) = _songs.value.any { it.id == song.id }
    fun isLiked(album: Album) = _albums.value.any { it.id == album.id }
    fun isLiked(playlist: Playlist) = _playlists.value.any { it.id == playlist.id }

    fun toggle(song: Song) {
        val like = !isLiked(song)
        val before = _songs.value
        _songs.value = if (like) listOf(song.copy(starred = now())) + before else before.filter { it.id != song.id }
        scope.launch { runCatching { api.like(songId = song.id, liked = like) }.onFailure { _songs.value = before } }
    }

    fun toggle(album: Album) {
        val like = !isLiked(album)
        val before = _albums.value
        _albums.value = if (like) listOf(album.copy(song = emptyList(), starred = now())) + before else before.filter { it.id != album.id }
        scope.launch { runCatching { api.like(albumId = album.id, liked = like) }.onFailure { _albums.value = before } }
    }

    fun toggle(playlist: Playlist) {
        _playlists.update { list ->
            if (list.any { it.id == playlist.id }) list.filter { it.id != playlist.id } else listOf(playlist.copy(entry = emptyList())) + list
        }
        prefs.edit { putString(PLAYLISTS, Json.encodeToString(playlistSerializer, _playlists.value)) }
    }

    /** On logout: the next account has its own likes. */
    fun clear() {
        _songs.value = emptyList()
        _albums.value = emptyList()
        _playlists.value = emptyList()
        prefs.edit { remove(PLAYLISTS) }
    }

    private fun now() = Instant.now().toString()

    private companion object {
        const val PLAYLISTS = "playlists"
    }
}
