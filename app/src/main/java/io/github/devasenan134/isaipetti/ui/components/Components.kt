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
import io.github.devasenan134.isaipetti.data.Song
import io.github.devasenan134.isaipetti.data.toRef
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
) {
    val app = LocalApp.current
    val player = app.player
    val likedSongs by app.likes.songs.collectAsStateWithLifecycle()
    val liked = likedSongs.any { it.id == song.id }
    var menuOpen by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    if (sharing) ShareSongSheet(song.toRef(), onDismiss = { sharing = false })
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
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
        if (liked) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Liked",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp).size(16.dp),
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
                DropdownMenuItem(text = { Text("Share with friends") }, onClick = { sharing = true; menuOpen = false })
                if (onOpenAlbum != null && song.albumId != null) {
                    DropdownMenuItem(text = { Text("Go to movie") }, onClick = { onOpenAlbum(song.albumId); menuOpen = false })
                }
            }
        }
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
