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
    val loader = rememberLoader("playlist-$id") { app.api.playlist(id) }
    val likedPlaylists by app.likes.playlists.collectAsStateWithLifecycle()
    Column {
        ScreenHeader("", onBack = nav.back)
        LoadableContent(loader) { playlist ->
            SongList(
                liked = likedPlaylists.any { it.id == playlist.id },
                onToggleLike = { app.likes.toggle(playlist) },
                onPlay = { app.recentPlaylists.played(playlist) },
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
                    Button(onClick = { onPlay(); player.play(songs) }, enabled = songs.isNotEmpty()) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("Play", Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = { onPlay(); player.play(songs, shuffle = true) }, enabled = songs.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null)
                        Text("Shuffle", Modifier.padding(start = 8.dp))
                    }
                    liked?.let { LikeButton(it, onToggleLike) }
                }
            }
        }
        itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
            SongRow(
                song = song,
                onClick = { onPlay(); player.play(songs, index) },
                isCurrent = song.id == nowPlaying.songId,
                showCover = showCovers,
                onOpenAlbum = if (showCovers) nav.openAlbum else null,
            )
        }
    }
}

/** "2026-09-12T18:04:00Z" -> "12 Sep 2026" */
private fun shortDate(iso: String): String = runCatching {
    java.time.OffsetDateTime.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
}.getOrDefault(iso.take(10))
