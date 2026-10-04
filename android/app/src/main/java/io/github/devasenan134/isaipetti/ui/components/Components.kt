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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.devasenan134.isaipetti.ui.mixes.startStation
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage

/** Sizes shared by every screen, close to Spotify's on a phone. */
object UiSize {
    /** Width of a tile in a sideways row (Home, Search), including its padding. */
    val Tile = 204.dp
    /** Grids (movies, playlists, Your Library): columns at least this wide, so a phone shows two. */
    val GridCell = 164.dp
    /** Pictures in list rows (Your Library, search results for people). */
    val ListThumb = 64.dp
    /** Your Library is a list of many things, so it's more compact: pictures in its rows, and its grid cells (three across). */
    val LibraryThumb = 72.dp
    val LibraryGridCell = 120.dp
    /** Covers in song rows. */
    val SongThumb = 50.dp
    /** The big picture at the top of a movie, playlist or mix page. */
    val HeaderArt = 256.dp
}

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

/** "1 like", "12 likes", or "No likes yet". */
fun likeCount(n: Int) = when (n) {
    0 -> "No likes yet"
    1 -> "1 like"
    else -> "%,d likes".format(n)
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

@Composable
fun PlaylistCard(playlist: Playlist, modifier: Modifier = Modifier.width(UiSize.Tile), onClick: () -> Unit) {
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
    /** On the Liked songs page every song is liked, so the ✓ only means "also in one of your playlists". */
    inLikedSongs: Boolean = false,
    /** On a playlist you made (its name) every song is in it, so the ✓ only means "also liked, or in another of your playlists". */
    inOwnPlaylist: String? = null,
    /** Replaces the line under the title, e.g. "Lyrics by Vairamuthu · Guru" in search. */
    note: String? = null,
) {
    val app = LocalApp.current
    val player = app.player
    val context = LocalContext.current
    val likedSongs by app.likes.songs.collectAsStateWithLifecycle()
    val liked = likedSongs.any { it.id == song.id }
    // Saved = liked or in one of your playlists; shown with a check mark like Spotify.
    val inPlaylists by app.myPlaylists.songs.collectAsStateWithLifecycle()
    val saved = (liked && !inLikedSongs) || inPlaylists[song.id].orEmpty().any { it != inOwnPlaylist }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var addingToPlaylist by remember { mutableStateOf(false) }
    if (sharing) ShareSongSheet(song.toRef(), onDismiss = { sharing = false })
    if (addingToPlaylist) AddToPlaylistSheet(song, onDismiss = { addingToPlaylist = false })
    // Swipe right: play next. Swipe left: add to the end of the queue. The row springs back either way.
    // In someone else's jam, swiping asks its owner instead: right to play it now (skipping the
    // current song), left to play it next. The swipe box can report one swipe twice; act once per swipe.
    val lastSwipe = remember { longArrayOf(0L) }
    val joinedJam by app.social.listen.joined.collectAsStateWithLifecycle()
    val jamOwners by app.social.listen.owners.collectAsStateWithLifecycle()
    val jamListener = joinedJam?.let { jamOwners[it] }?.let { it != app.social.me?.id } ?: false
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val now = android.os.SystemClock.uptimeMillis()
            if (value != SwipeToDismissBoxValue.Settled && now - lastSwipe[0] > 600) {
                lastSwipe[0] = now
                val requesting = app.social.listen.isListener()
                if (requesting) player.jam?.request(song, playNow = value == SwipeToDismissBoxValue.StartToEnd)
                else if (value == SwipeToDismissBoxValue.StartToEnd) player.playNext(song) else player.addToQueue(song)
                if (!requesting) {
                    val text = if (value == SwipeToDismissBoxValue.StartToEnd) "Playing next" else "Added to queue"
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
            }
            false
        },
    )
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = { SwipeHint(swipe.dismissDirection, requesting = jamListener) },
    ) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable(onClick = onClick)
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The song that's playing gets bouncing bars: over its cover, or instead of its track number.
            val playing = isCurrent && player.nowPlaying.collectAsStateWithLifecycle().value.isPlaying
            if (showCover) {
                Box(contentAlignment = Alignment.Center) {
                    Cover(song.coverArt, Modifier.size(UiSize.SongThumb), size = 150, corner = 4.dp)
                    if (isCurrent) {
                        Box(Modifier.size(UiSize.SongThumb).clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)))
                        PlayingBars(playing, Modifier.size(20.dp))
                    }
                }
            } else if (isCurrent) {
                Box(Modifier.width(28.dp)) { PlayingBars(playing, Modifier.size(width = 16.dp, height = 16.dp)) }
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
                    note ?: listOfNotNull(song.artist, if (showCover) song.album else null).joinToString(" · "),
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
                    DropdownMenuItem(text = { Text("Start song radio") }, onClick = {
                        menuOpen = false
                        scope.launch { startStation(app, context, "song", song.id) }
                    })
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
private fun SwipeHint(direction: SwipeToDismissBoxValue, requesting: Boolean) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val playNext = direction == SwipeToDismissBoxValue.StartToEnd
    Row(
        Modifier.fillMaxSize().background(if (playNext) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 20.dp),
        horizontalArrangement = if (playNext) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_queue), contentDescription = null)
        val label = when {
            requesting && playNext -> "Ask to play now"
            requesting -> "Ask to play next"
            playNext -> "Play next"
            else -> "Add to queue"
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
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
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    /** The page's colour behind the bar (see PageTint.kt); none by default. */
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    /** The title's colour; the normal text colour by default. */
    titleColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    /** A short status between the title and the actions, like "Jamming with Alice". */
    note: String? = null,
    /** Makes the title tappable, e.g. a group chat's name opens its members. */
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().background(color).height(56.dp).padding(horizontal = if (onBack == null) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // With a note, the title takes only its own width (capped) and the note fills the rest, so
            // the actions stay at the right edge. (A non-filling weight would leave a gap after them.)
            modifier = (if (note == null) Modifier.weight(1f) else Modifier.widthIn(max = 160.dp))
                .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier),
        )
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 4.dp),
            )
        }
        actions()
    }
}

/** Section title used above rows and lists. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
    )
}

/**
 * Three bars bouncing up and down, like a level meter: marks the song that's playing. They rest
 * (at different heights) while it's paused.
 */
@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "bars")
    val heights = listOf(420, 300, 520).mapIndexed { i, period ->
        if (!playing) return@mapIndexed remember(i) { mutableStateOf(listOf(0.55f, 0.9f, 0.35f)[i]) }
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(period, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "bar$i",
        )
    }
    androidx.compose.foundation.Canvas(modifier) {
        val gap = size.width / 7
        val barWidth = (size.width - gap * 2) / 3
        heights.forEachIndexed { i, h ->
            val barHeight = size.height * h.value
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(i * (barWidth + gap), size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2),
            )
        }
    }
}

/**
 * A vinyl record that turns while music plays and rests where it stopped when paused: marks what's
 * playing now (Home's "Now playing" tile).
 */
@Composable
fun SpinningDisc(spinning: Boolean, modifier: Modifier = Modifier) {
    val angle = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(spinning) {
        while (spinning) {
            angle.animateTo(
                angle.value + 360f,
                androidx.compose.animation.core.tween(durationMillis = 3_000, easing = androidx.compose.animation.core.LinearEasing),
            )
            angle.snapTo(angle.value % 360f)
        }
    }
    val label = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier.graphicsLayer { rotationZ = angle.value }) {
        val r = size.minDimension / 2
        drawCircle(androidx.compose.ui.graphics.Color(0xFF141217), r)
        // Grooves.
        listOf(0.92f, 0.8f, 0.68f, 0.56f).forEach {
            drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f), r * it, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.02f))
        }
        // A shine across the record, so you can see it turn.
        drawArc(
            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f), startAngle = -60f, sweepAngle = 40f, useCenter = true,
            topLeft = androidx.compose.ui.geometry.Offset(center.x - r * 0.95f, center.y - r * 0.95f),
            size = androidx.compose.ui.geometry.Size(r * 1.9f, r * 1.9f),
        )
        drawCircle(label, r * 0.34f)
        // A mark on the label, also to show the turning.
        drawCircle(androidx.compose.ui.graphics.Color(0xFF141217).copy(alpha = 0.35f), r * 0.07f, center.copy(y = center.y - r * 0.2f))
        drawCircle(androidx.compose.ui.graphics.Color(0xFF141217), r * 0.06f)
    }
}
