package io.github.devasenan134.isaipetti.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import io.github.devasenan134.isaipetti.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.IsaipettiApp
import io.github.devasenan134.isaipetti.data.Album
import io.github.devasenan134.isaipetti.data.Playlist
import io.github.devasenan134.isaipetti.data.Song
import io.github.devasenan134.isaipetti.data.toRef
import io.github.devasenan134.isaipetti.ui.library.AddToPlaylistSheet
import io.github.devasenan134.isaipetti.ui.social.ShareSongSheet
import coil3.compose.AsyncImage

/** Lets any screen reach the app-wide objects (API, player) without passing them down by hand. */
val LocalApp = staticCompositionLocalOf<IsaipettiApp> { error("LocalApp not provided") }

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** A total playing time: "45 min", "2 hr 15 min", "3 days 4 hr". */
fun formatTotalDuration(seconds: Int): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3600
    val minutes = seconds % 3600 / 60
    return when {
        days > 0 -> "$days ${if (days == 1) "day" else "days"}" + if (hours > 0) " $hours hr" else ""
        hours > 0 -> "$hours hr" + if (minutes > 0) " $minutes min" else ""
        minutes > 0 -> "$minutes min"
        else -> "$seconds sec"
    }
}

/** "1 song", "3,264 songs" */
fun songCount(n: Int) = if (n == 1) "1 song" else "%,d songs".format(n)

/** Album art from Navidrome, with a plain placeholder behind it while loading or if missing. */
@Composable
fun Cover(coverArt: String?, modifier: Modifier = Modifier, size: Int = 300, corner: Dp = 8.dp) {
    val app = LocalApp.current
    AsyncImage(
        model = app.api.coverUrl(coverArt, size),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp)) {
        Cover(album.coverArt, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(6.dp))
        Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(album.year?.toString(), album.artist).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, modifier: Modifier = Modifier.width(140.dp), onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp)) {
        Cover(playlist.coverArt, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(6.dp))
        Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${playlist.songCount} songs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One song in a list. Shows the track number, or a small cover when [showCover] is true
 * (useful when songs come from different albums, like search results).
 */
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    isCurrent: Boolean,
    showCover: Boolean = false,
    onOpenAlbum: ((String) -> Unit)? = null,
    /** Set on a playlist you own: removes this song from it. */
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val app = LocalApp.current
    val player = app.player
    val context = LocalContext.current
    val likedSongs by app.likes.songs.collectAsStateWithLifecycle()
    val liked = likedSongs.any { it.id == song.id }
    // Saved = liked or in one of your playlists; shown with a check mark like Spotify.
    val inPlaylists by app.myPlaylists.songs.collectAsStateWithLifecycle()
    val saved = liked || !inPlaylists[song.id].isNullOrEmpty()
    var menuOpen by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var addingToPlaylist by remember { mutableStateOf(false) }
    if (sharing) ShareSongSheet(song.toRef(), onDismiss = { sharing = false })
    if (addingToPlaylist) AddToPlaylistSheet(song, onDismiss = { addingToPlaylist = false })
    // Swipe right: play next. Swipe left: add to the end of the queue. The row springs back either way.
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    player.playNext(song)
                    Toast.makeText(context, "Playing next", Toast.LENGTH_SHORT).show()
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    player.addToQueue(song)
                    Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = { SwipeHint(swipe.dismissDirection) },
    ) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable(onClick = onClick)
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCover) {
                Cover(song.coverArt, Modifier.size(44.dp), size = 100, corner = 4.dp)
            } else {
                Text(
                    song.track?.toString() ?: "",
                    modifier = Modifier.width(28.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else null,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(song.artist, if (showCover) song.album else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (saved) {
                // Tap to see (and change) where it's saved: Liked songs and which playlists.
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Saved. Tap to see where",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp).clip(CircleShape).clickable { addingToPlaylist = true }.padding(2.dp).size(18.dp),
                )
            }
            Text(formatDuration(song.duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (liked) "Remove from liked songs" else "Like") },
                        onClick = { app.likes.toggle(song); menuOpen = false },
                    )
                    DropdownMenuItem(text = { Text("Play next") }, onClick = { player.playNext(song); menuOpen = false })
                    DropdownMenuItem(text = { Text("Add to queue") }, onClick = { player.addToQueue(song); menuOpen = false })
                    DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { addingToPlaylist = true; menuOpen = false })
                    onRemoveFromPlaylist?.let { remove ->
                        DropdownMenuItem(text = { Text("Remove from this playlist") }, onClick = { remove(); menuOpen = false })
                    }
                    DropdownMenuItem(text = { Text("Share with friends") }, onClick = { sharing = true; menuOpen = false })
                    if (onOpenAlbum != null && song.albumId != null) {
                        DropdownMenuItem(text = { Text("Go to movie") }, onClick = { onOpenAlbum(song.albumId); menuOpen = false })
                    }
                }
            }
        }
    }
}

/** What shows behind a song row while you swipe it. */
@Composable
private fun SwipeHint(direction: SwipeToDismissBoxValue) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val playNext = direction == SwipeToDismissBoxValue.StartToEnd
    Row(
        Modifier.fillMaxSize().background(if (playNext) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 20.dp),
        horizontalArrangement = if (playNext) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_queue), contentDescription = null)
        Text(if (playNext) "Play next" else "Add to queue", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A heart: filled when liked. */
@Composable
fun LikeButton(liked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (liked) "Remove from liked" else "Like",
            tint = if (liked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

/** Screen title with an optional back arrow. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = if (onBack == null) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** Section title used above rows and lists. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
