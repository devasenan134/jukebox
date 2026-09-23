package io.github.devasenan134.isaipetti.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.animation.animateColorAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import io.github.devasenan134.isaipetti.ui.library.AddToPlaylistSheet
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.ui.components.LikeButton
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.formatDuration
import io.github.devasenan134.isaipetti.ui.social.ShareSongSheet
import kotlinx.coroutines.delay

/**
 * The player's position in ms, re-read every [intervalMs] while playing.
 * The player doesn't push position updates, so the UI asks for them.
 */
@Composable
fun rememberPosition(songId: String?, isPlaying: Boolean, intervalMs: Long = 200): State<Long> {
    val player = LocalApp.current.player
    return produceState(player.positionMs(), songId, isPlaying) {
        while (true) {
            value = player.positionMs()
            delay(if (isPlaying) intervalMs else 1_000)
        }
    }
}

@Composable
fun PlayerScreen(onClose: () -> Unit, onOpenAlbum: (String) -> Unit) {
    val app = LocalApp.current
    val player = app.player
    val now by player.nowPlaying.collectAsStateWithLifecycle()
    val listen = app.social.listen
    val joined by listen.joined.collectAsStateWithLifecycle()
    val conversations by app.social.conversations.collectAsStateWithLifecycle()
    val position by rememberPosition(now.songId, now.isPlaying)
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showShare by rememberSaveable { mutableStateOf(false) }
    var addingToPlaylist by rememberSaveable { mutableStateOf(false) }
    // While the user drags the slider, show where they're dragging instead of the real position.
    var dragging by remember { mutableStateOf<Float?>(null) }

    val scope = rememberCoroutineScope()

    // A mild gradient in the cover's colour, fading into the normal background (like Spotify, but calmer).
    val base = MaterialTheme.colorScheme.surface
    val dark = base.luminance() < 0.5f
    val coverColor = rememberCoverColor(now.artworkUri, dark)
    val tint by animateColorAsState(
        targetValue = coverColor?.let { lerp(base, it, if (dark) 0.55f else 0.35f) } ?: base,
        animationSpec = tween(700),
        label = "cover tint",
    )

    // Swipe down to minimize: the player follows the finger; let go far enough (or fast) and it closes.
    val density = LocalDensity.current
    val closeAfter = with(density) { 140.dp.toPx() }
    var pulled by remember { mutableFloatStateOf(0f) }
    val pullToClose = remember {
        object : NestedScrollConnection {
            // Dragging back up while pulled down moves the player back first.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0 || pulled <= 0f) return Offset.Zero
                val used = maxOf(available.y, -pulled)
                pulled += used
                return Offset(0f, used)
            }

            // Pulling down when there's nothing left to scroll up moves the whole player.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0 || source != NestedScrollSource.UserInput) return Offset.Zero
                pulled += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulled <= 0f) return Velocity.Zero
                if (pulled > closeAfter || available.y > 2_500f) onClose()
                else animate(pulled, 0f) { value, _ -> pulled = value }
                return available
            }
        }
    }

    Surface(Modifier.fillMaxSize().graphicsLayer { translationY = pulled }) {
        // The player fills the screen; scrolling down shows "About this song" below it.
        BoxWithConstraints(Modifier.background(Brush.verticalGradient(0f to tint, 0.7f to base)).safeDrawingPadding()) {
            val pageHeight = maxHeight
            val scroll = rememberScrollState()
            Column(Modifier.nestedScroll(pullToClose).verticalScroll(scroll)) {
                Column(Modifier.height(pageHeight).padding(horizontal = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close") }
                        Text(
                            now.album,
                            modifier = Modifier.weight(1f).clickable(enabled = now.albumId != null) { now.albumId?.let(onOpenAlbum) },
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { showShare = true }, enabled = now.song != null) {
                            Icon(Icons.Filled.Share, contentDescription = "Share with friends")
                        }
                        IconButton(onClick = { showQueue = true }) {
                            Icon(painterResource(R.drawable.ic_queue), contentDescription = "Queue")
                        }
                    }

                    // In a listen-together session, everything you do here changes the music for everyone.
                    joined?.let { id ->
                        val chat = conversations.firstOrNull { it.id == id }?.title(app.social.me?.id) ?: "a chat"
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_headphones), contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "Listening together in $chat",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            TextButton(onClick = listen::leave) { Text("Leave") }
                        }
                    }

                    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        if (showLyrics) {
                            LyricsView(now.songId, position, onSeek = player::seekTo, modifier = Modifier.fillMaxSize())
                        } else {
                            CoverPager(Modifier.fillMaxWidth())
                        }
                    }

                    val likedSongs by app.likes.songs.collectAsStateWithLifecycle()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(now.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                now.artist,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        now.song?.let { song ->
                            IconButton(onClick = { addingToPlaylist = true }) { Icon(painterResource(R.drawable.ic_playlist_add), contentDescription = "Save to playlist") }
                            LikeButton(likedSongs.any { it.id == song.id }, onToggle = { app.likes.toggle(song.toSong()) })
                        }
                    }

                    val duration = now.durationMs.coerceAtLeast(1)
                    Slider(
                        value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { player.seekTo((it * duration).toLong()) }
                            dragging = null
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row {
                        val shown = dragging?.let { (it * duration).toLong() } ?: position
                        Text(formatDuration((shown / 1000).toInt()), style = MaterialTheme.typography.bodySmall)
                        Box(Modifier.weight(1f))
                        Text(formatDuration((now.durationMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall)
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val active = MaterialTheme.colorScheme.primary
                        // Shuffle is off while listening together, so everyone hears the same order.
                        IconButton(onClick = player::toggleShuffle, enabled = joined == null) {
                            Icon(
                                painterResource(R.drawable.ic_shuffle),
                                contentDescription = "Shuffle",
                                tint = if (now.shuffle && joined == null) active else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = player::previous) {
                            Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous", Modifier.size(36.dp))
                        }
                        FilledIconButton(onClick = player::togglePlay, modifier = Modifier.size(72.dp)) {
                            Icon(
                                painterResource(if (now.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                                contentDescription = if (now.isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        IconButton(onClick = player::next) {
                            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next", Modifier.size(36.dp))
                        }
                        IconButton(onClick = player::cycleRepeat) {
                            Icon(
                                painterResource(if (now.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                                contentDescription = "Repeat",
                                tint = if (now.repeatMode != Player.REPEAT_MODE_OFF) active else LocalContentColor.current,
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                        FilterChip(selected = showLyrics, onClick = { showLyrics = !showLyrics }, label = { Text("Lyrics") })
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }.padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("About this song", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SongDetailsSection(now.songId, onOpenAlbum = onOpenAlbum, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp))
            }
        }
    }

    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
    if (showShare) now.song?.let { ShareSongSheet(it, onDismiss = { showShare = false }) }
    if (addingToPlaylist) now.song?.let { AddToPlaylistSheet(it.toSong(), onDismiss = { addingToPlaylist = false }) }
}
