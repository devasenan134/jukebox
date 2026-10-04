package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * What you've liked, for the hearts everywhere and the Your Library page.
 *
 * Songs and movies are liked in Navidrome itself ("starred"), so they're the same on every device
 * and in Navidrome's web page. Navidrome can't like playlists, so liked playlists are saved with your
 * account on the friends server (or only on this phone if you don't use one). A copy is kept on the
 * phone so the library shows right away. Changes show immediately and are undone if refused.
 */
class Likes(
    context: Context,
    private val api: SubsonicApi,
    private val session: SessionStore,
    private val social: () -> SocialApi,
) {
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

    init {
        // Once connected to the friends server, fetch liked playlists from it.
        scope.launch { session.social.collect { if (it != null) syncPlaylists() } }
    }

    /** Loads your likes (at start, and when the library opens). */
    fun refresh() {
        scope.launch {
            runCatching { api.starred() }.onSuccess {
                _songs.value = it.song
                _albums.value = it.album
            }
        }
        if (session.social.value != null) scope.launch { syncPlaylists() }
    }

    private val usesServer get() = session.social.value != null

    /**
     * Gets liked playlists from the friends server. Playlists liked on this phone before they were
     * saved on the server are uploaded once.
     */
    private suspend fun syncPlaylists() {
        runCatching {
            val server = social()
            if (!prefs.getBoolean(UPLOADED, false)) {
                _playlists.value.forEach { server.likePlaylist(it) }
                prefs.edit { putBoolean(UPLOADED, true) }
            }
            savePlaylists(server.likedPlaylists())
        }
    }

    private fun savePlaylists(list: List<Playlist>) {
        _playlists.value = list
        prefs.edit { putString(PLAYLISTS, Json.encodeToString(playlistSerializer, list)) }
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
        val like = !isLiked(playlist)
        val before = _playlists.value
        savePlaylists(if (like) listOf(playlist.copy(entry = emptyList())) + before else before.filter { it.id != playlist.id })
        if (!usesServer) return
        scope.launch {
            runCatching { if (like) social().likePlaylist(playlist) else social().unlikePlaylist(playlist.id) }
                .onFailure { savePlaylists(before) }
        }
    }

    /** On logout: the next account has its own likes. */
    fun clear() {
        _songs.value = emptyList()
        _albums.value = emptyList()
        _playlists.value = emptyList()
        prefs.edit { remove(PLAYLISTS); remove(UPLOADED) }
    }

    private fun now() = Instant.now().toString()

    private companion object {
        const val PLAYLISTS = "playlists"
        const val UPLOADED = "playlists_uploaded"
    }
}
