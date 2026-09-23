package io.github.devasenan134.isaipetti.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.R
import androidx.compose.foundation.layout.Box
import io.github.devasenan134.isaipetti.data.Playlist
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.OutlinedButton
import io.github.devasenan134.isaipetti.ui.components.Loadable
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import io.github.devasenan134.isaipetti.ui.components.songCount
import io.github.devasenan134.isaipetti.ui.components.formatTotalDuration
import androidx.compose.ui.text.style.TextAlign
import io.github.devasenan134.isaipetti.ui.components.LikeButton
import io.github.devasenan134.isaipetti.data.Song
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.AlbumCard
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LoadableContent
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.SongRow
import io.github.devasenan134.isaipetti.ui.components.rememberLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A movie: its songs with Play and Shuffle. */
@Composable
fun AlbumScreen(id: String, nav: Nav) {
    val app = LocalApp.current
    val loader = rememberLoader("album-$id") { app.api.album(id) }
    val likedAlbums by app.likes.albums.collectAsStateWithLifecycle()
    Column {
        ScreenHeader("", onBack = nav.back)
        LoadableContent(loader) { album ->
            SongList(
                liked = likedAlbums.any { it.id == album.id },
                onToggleLike = { app.likes.toggle(album) },
                onPlay = { app.searches.picked(album); app.activity.movie(album) },
                source = "album:${album.id}",
                extraAction = {
                    ResumeButton("album:${album.id}", album.song) { app.searches.picked(album); app.activity.movie(album) }
                },
                coverArt = album.coverArt,
                title = album.name,
                subtitle = listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "),
                songs = album.song,
                onSubtitleClick = album.artistId?.let { artistId -> { nav.openArtist(artistId) } },
                showCovers = false,
                nav = nav,
            )
        }
    }
}

@Composable
fun PlaylistScreen(id: String, nav: Nav) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoader("playlist-$id") { app.api.playlist(id) }
    val likedPlaylists by app.likes.playlists.collectAsStateWithLifecycle()
    val username = app.session.credentials.value?.username
    val playlist = (loader.state as? Loadable.Ready)?.value
    // Only the person who made a playlist can change it (Navidrome checks this too).
    val mine = playlist != null && playlist.owner == username
    Column {
        ScreenHeader("", onBack = nav.back) {
            if (mine) PlaylistOwnerMenu(playlist!!, onChanged = { loader.reload(quietly = true) }, onDeleted = nav.back)
        }
        LoadableContent(loader) { playlist ->
            SongList(
                liked = likedPlaylists.any { it.id == playlist.id },
                onToggleLike = { app.likes.toggle(playlist) },
                onPlay = { app.recentPlaylists.played(playlist); app.activity.playlist(playlist) },
                source = "playlist:${playlist.id}",
                extraAction = {
                    ResumeButton("playlist:${playlist.id}", playlist.entry) {
                        app.recentPlaylists.played(playlist)
                        app.activity.playlist(playlist)
                    }
                },
                onRemoveSong = if (!mine || playlist.readonly) null else { index ->
                    scope.launch {
                        runCatching { app.api.updatePlaylist(playlist.id, removeIndexes = listOf(index)) }
                            .onSuccess { loader.reload(quietly = true); app.myPlaylists.refresh() }
                            .onFailure { Toast.makeText(context, it.message ?: "Couldn't remove it", Toast.LENGTH_SHORT).show() }
                    }
                },
                coverArt = playlist.coverArt,
                title = playlist.name,
                // Who made it, then its description and when it was last updated.
                subtitle = playlist.owner?.let { "By $it" }.orEmpty(),
                details = listOfNotNull(
                    playlist.comment,
                    listOfNotNull(
                        if (playlist.public) "Public playlist" else "Private playlist",
                        playlist.changed?.let { "Updated ${shortDate(it)}" },
                    ).joinToString(" · "),
                    if (mine && playlist.readonly) "Its songs can't be changed here: it's a smart playlist or comes from a playlist file on the server." else null,
                ),
                songs = playlist.entry,
                onSubtitleClick = null,
                showCovers = true,
                nav = nav,
            )
        }
    }
}

/** A music director: their movies, newest first, and "Shuffle all". */
@Composable
fun ArtistScreen(id: String, nav: Nav) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoader("artist-$id") { app.api.artist(id) }
    var shuffling by remember { mutableStateOf(false) }

    Column {
        LoadableContent(loader) { artist ->
            ScreenHeader(artist.name, onBack = nav.back)
            val albums = artist.album.sortedByDescending { it.year ?: 0 }
            LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(10.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${albums.size} movies",
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            enabled = !shuffling,
                            onClick = {
                                shuffling = true
                                app.searches.picked(artist) // shows under "Your recent composers and artists"
                                app.activity.composer(artist)
                                scope.launch {
                                    // Fetch every movie's songs in parallel, then shuffle them together.
                                    val songs = runCatching {
                                        coroutineScope { albums.map { async { app.api.album(it.id).song } }.awaitAll().flatten() }
                                    }.getOrDefault(emptyList())
                                    app.player.play(songs, shuffle = true)
                                    shuffling = false
                                }
                            },
                        ) {
                            Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null)
                            Text("Shuffle all", Modifier.padding(start = 8.dp))
                        }
                    }
                }
                items(albums, key = { it.id }) { album -> AlbumCard(album, onClick = { nav.openAlbum(album.id) }) }
            }
        }
    }
}

@Composable
internal fun SongList(
    liked: Boolean? = null,
    onToggleLike: () -> Unit = {},
    onPlay: () -> Unit = {},
    /** On a playlist you own: remove the song at this position. */
    onRemoveSong: ((Int) -> Unit)? = null,
    /** What plays from here are started from (e.g. "playlist:<id>"), to remember where you left off. */
    source: String? = null,
    /** Shown under Play and Shuffle, e.g. "Resume". */
    extraAction: (@Composable () -> Unit)? = null,
    coverArt: String?,
    title: String,
    subtitle: String,
    /** Extra lines under the title, like a playlist's description and when it was updated. */
    details: List<String> = emptyList(),
    songs: List<Song>,
    onSubtitleClick: (() -> Unit)?,
    showCovers: Boolean,
    nav: Nav,
) {
    val player = LocalApp.current.player
    val nowPlaying by player.nowPlaying.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(coverArt, Modifier.size(220.dp), size = 600, corner = 12.dp)
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                if (subtitle.isNotEmpty()) {
                    val style = MaterialTheme.typography.bodyLarge
                    if (onSubtitleClick != null) {
                        androidx.compose.material3.TextButton(onClick = onSubtitleClick) { Text(subtitle, style = style) }
                    } else {
                        Text(subtitle, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                details.filter { it.isNotBlank() }.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    "${songCount(songs.size)} · ${formatTotalDuration(songs.sumOf { it.duration })}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onPlay(); player.play(songs, source = source) }, enabled = songs.isNotEmpty()) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("Play", Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = { onPlay(); player.play(songs, shuffle = true, source = source) }, enabled = songs.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null)
                        Text("Shuffle", Modifier.padding(start = 8.dp))
                    }
                    liked?.let { LikeButton(it, onToggleLike) }
                }
                extraAction?.let { Box(Modifier.padding(top = 8.dp)) { it() } }
            }
        }
        itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
            SongRow(
                song = song,
                onClick = { onPlay(); player.play(songs, index, source = source) },
                isCurrent = song.id == nowPlaying.songId,
                showCover = showCovers,
                onOpenAlbum = if (showCovers) nav.openAlbum else null,
                onRemoveFromPlaylist = onRemoveSong?.let { remove -> { remove(index) } },
            )
        }
    }
}

/** "2026-09-12T18:04:00Z" -> "12 Sep 2026" */
private fun shortDate(iso: String): String = runCatching {
    java.time.OffsetDateTime.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
}.getOrDefault(iso.take(10))

/**
 * "Resume · Song name": continue a playlist, movie or Liked songs from the song you were on last
 * time, in the same order as then (a shuffled order stays as it was). Songs added since join at the end.
 */
@Composable
internal fun ResumeButton(source: String, songs: List<Song>, onResume: () -> Unit = {}) {
    val app = LocalApp.current
    val now by app.player.nowPlaying.collectAsStateWithLifecycle()
    if (now.source == source) return // already playing from here
    val saved = remember(source, songs, now.source) { app.queueMemory.get(source) } ?: return
    val byId = songs.associateBy { it.id }
    val current = byId[saved.currentId] ?: return
    OutlinedButton(onClick = {
        val order = saved.songIds.mapNotNull { byId[it] }.distinctBy { it.id }
        val added = songs.filter { song -> order.none { it.id == song.id } }
        val queue = order + added
        onResume()
        app.player.play(queue, queue.indexOfFirst { it.id == current.id }.coerceAtLeast(0), source = source)
    }) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Text("Resume · ${current.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
    }
}
