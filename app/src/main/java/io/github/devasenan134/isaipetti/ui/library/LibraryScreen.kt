package io.github.devasenan134.isaipetti.ui.library

import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Add
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.Playlist
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.mixes.MixCover
import io.github.devasenan134.isaipetti.data.MIX_AUTHOR
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.SongRow
import io.github.devasenan134.isaipetti.ui.components.formatTotalDuration
import io.github.devasenan134.isaipetti.ui.components.songCount

private enum class LibraryFilter(val label: String) { All("All"), Movies("Movies"), Playlists("Playlists") }

/** Your Library: liked songs, saved mixes, liked movies, liked playlists and playlists you created. */
@Composable
fun LibraryScreen(nav: Nav) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val likedSongs by app.likes.songs.collectAsStateWithLifecycle()
    val likedAlbums by app.likes.albums.collectAsStateWithLifecycle()
    val likedPlaylists by app.likes.playlists.collectAsStateWithLifecycle()
    // Mixes by Isai Pettai you saved; they keep updating here.
    val savedMixes by app.mixes.followed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.likes.refresh(); app.mixes.refresh() }
    val credentials by app.session.credentials.collectAsStateWithLifecycle()
    // Playlists you created in Navidrome, next to the ones you liked.
    var reloadOwn by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val ownPlaylists by produceState(emptyList<Playlist>(), credentials?.username, reloadOwn) {
        value = runCatching { app.api.playlists().filter { it.owner == credentials?.username } }.getOrDefault(emptyList())
    }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.All) }
    // Liked first (newest like first), then the rest of your own. Every playlist is under Search → Playlists.
    val playlists = likedPlaylists + ownPlaylists.filter { own -> likedPlaylists.none { it.id == own.id } }

    Column {
        ScreenHeader("Your Library") {
            IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, contentDescription = "New playlist") }
        }
        if (creating) {
            NameDialog(title = "New playlist", confirm = "Create", onDismiss = { creating = false }) { name ->
                scope.launch {
                    runCatching { app.api.createPlaylist(name) }
                        .onSuccess { reloadOwn++; nav.openPlaylist(it.id) }
                        .onFailure { Toast.makeText(context, it.message ?: "Couldn't create it", Toast.LENGTH_SHORT).show() }
                    creating = false
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryFilter.entries) { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            if (filter != LibraryFilter.Movies) {
                item {
                    LibraryRow(
                        title = "Liked songs",
                        subtitle = "Playlist · ${likedSongs.size} songs",
                        leading = { LikedTile(56.dp) },
                        onClick = nav.openLikedSongs,
                    )
                }
            }
            if (filter != LibraryFilter.Movies) {
                items(savedMixes, key = { "mix-${it.id}" }) { mix ->
                    LibraryRow(
                        title = mix.title,
                        subtitle = listOfNotNull(
                            if (mix.endless) "Station" else "Mix",
                            "by $MIX_AUTHOR",
                            if (mix.endless || mix.songCount == 0) null else songCount(mix.songCount),
                        ).joinToString(" · "),
                        leading = { MixCover(mix, size = 56.dp) },
                        onClick = { nav.openMix(mix.id) },
                    )
                }
            }
            if (filter != LibraryFilter.Playlists) {
                items(likedAlbums, key = { "album-${it.id}" }) { album ->
                    LibraryRow(
                        title = album.name,
                        subtitle = listOfNotNull("Movie", album.artist).joinToString(" · "),
                        leading = { Cover(album.coverArt, Modifier.size(56.dp), size = 150, corner = 6.dp) },
                        onClick = { nav.openAlbum(album.id) },
                    )
                }
            }
            if (filter != LibraryFilter.Movies) {
                items(playlists, key = { "playlist-${it.id}" }) { playlist ->
                    LibraryRow(
                        title = playlist.name,
                        subtitle = listOfNotNull("Playlist", playlist.owner?.let { "by $it" }, songCount(playlist.songCount)).joinToString(" · "),
                        leading = { Cover(playlist.coverArt, Modifier.size(56.dp), size = 150, corner = 6.dp) },
                        onClick = { nav.openPlaylist(playlist.id) },
                    )
                }
            }
            val empty = when (filter) {
                LibraryFilter.All -> likedSongs.isEmpty() && likedAlbums.isEmpty() && playlists.isEmpty() && savedMixes.isEmpty()
                LibraryFilter.Movies -> likedAlbums.isEmpty()
                LibraryFilter.Playlists -> likedSongs.isEmpty() && playlists.isEmpty()
            }
            if (empty) {
                item {
                    Text(
                        "Tap ♡ on songs, movies and playlists to keep them here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** All your liked songs, newest likes first, with Play and Shuffle. */
@Composable
fun LikedSongsScreen(nav: Nav) {
    val app = LocalApp.current
    val player = app.player
    val songs by app.likes.songs.collectAsStateWithLifecycle()
    val nowPlaying by player.nowPlaying.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.likes.refresh() }
    Column {
        ScreenHeader("", onBack = nav.back)
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LikedTile(180.dp)
                    Text("Liked songs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        "${songCount(songs.size)} · ${formatTotalDuration(songs.sumOf { it.duration })}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { app.activity.liked(); player.play(songs, source = LIKED) }, enabled = songs.isNotEmpty()) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Text("Play", Modifier.padding(start = 8.dp))
                        }
                        FilledTonalButton(onClick = { app.activity.liked(); player.play(songs, shuffle = true, source = LIKED) }, enabled = songs.isNotEmpty()) {
                            Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null)
                            Text("Shuffle", Modifier.padding(start = 8.dp))
                        }
                    }
                    Box(Modifier.padding(top = 8.dp)) { ResumeButton(LIKED, songs) { app.activity.liked() } }
                }
            }
            if (songs.isEmpty()) {
                item {
                    Text(
                        "Songs you like show up here. Tap ♡ in the player, or Like in a song's ⋮ menu.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { app.activity.liked(); player.play(songs, index, source = LIKED) },
                    isCurrent = song.id == nowPlaying.songId,
                    showCover = true,
                    onOpenAlbum = nav.openAlbum,
                )
            }
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Queue source for Liked songs, so it can be resumed like a playlist. */
private const val LIKED = "liked"

/** The "Liked songs" artwork: a heart on the brand colour. */
@Composable
internal fun LikedTile(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 10)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.45f))
    }
}
