package io.github.devasenan134.isaipetti.ui.social

import io.github.devasenan134.isaipetti.data.Conversation
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.devasenan134.isaipetti.data.Waveforms
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.devasenan134.isaipetti.data.SocialUser
import coil3.request.ImageRequest
import coil3.network.httpHeaders
import coil3.network.NetworkHeaders
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import android.widget.Toast
import androidx.compose.foundation.background
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import io.github.devasenan134.isaipetti.data.clockTime
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.SongRef
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

private val avatarColors = listOf(
    Color(0xFFF5B942), Color(0xFFE8643C), Color(0xFF7FB8A4), Color(0xFF8C7BD8),
    Color(0xFFE38FB0), Color(0xFF6FA8DC), Color(0xFFC9A26B),
)

/** A coloured circle with the person's first letter, and a green dot when they're online. */
@Composable
fun Avatar(
    name: String,
    key: String,
    size: Dp = 44.dp,
    online: Boolean = false,
    /** Whose picture to show, if they set one; otherwise the first letter of [name] on a colour. */
    user: SocialUser? = null,
) {
    val picture = user?.let { LocalApp.current.social.api.avatarUrl(it) }
    Box {
        Box(
            Modifier.size(size).clip(CircleShape).background(avatarColors[key.hashCode().absoluteValue % avatarColors.size]),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                color = Color(0xFF1B1726),
                style = if (size > 40.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            )
            if (picture != null) FriendsServerPicture(picture, Modifier.matchParentSize())
        }
        if (online) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size / 3.5f)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .background(Color(0xFF4CC38A), CircleShape),
            )
        }
    }
}

/** "14:05" for today, "Mon" within a week, else "12 Sep". */
fun chatTime(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val today = LocalDate.now(zone)
    val pattern = when {
        time.toLocalDate() == today -> "HH:mm"
        time.toLocalDate().isAfter(today.minusDays(7)) -> "EEE"
        else -> "d MMM"
    }
    return time.format(DateTimeFormatter.ofPattern(pattern))
}

/** A shared song (or part of one) inside a chat bubble: cover, title, and a play button. */
@Composable
fun SongCard(song: SongRef, modifier: Modifier = Modifier) {
    val player = LocalApp.current.player
    val activity = LocalApp.current.activity
    val play = {
        activity.song(song.toSong())
        if (song.isClip) player.playClip(song) else player.play(listOf(song.toSong()))
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = modifier.clickable(onClick = play),
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(song.coverArt, Modifier.size(48.dp), size = 150, corner = 6.dp)
            Column(Modifier.weight(1f, fill = false).padding(horizontal = 10.dp)) {
                Text(song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(song.artist, song.album).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (song.isClip) {
                    Text(
                        "Clip ${clockTime(song.clipStartMs!!)}–${clockTime(song.clipEndMs!!)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = play) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = "Play")
            }
        }
    }
}

/** Pick a chat or friend to send [song] to, optionally just a part of it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSongSheet(song: SongRef, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val social = app.social
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversations by social.conversations.collectAsStateWithLifecycle()
    val friends by social.friends.collectAsStateWithLifecycle()
    val me = social.me?.id
    // Friends you don't have a DM with yet also appear, so you can share with anyone.
    val dmPartners = conversations.filter { !it.isGroup }.flatMap { c -> c.members.map { it.id } }.toSet()
    val newPeople = friends.filter { it.user.id !in dmPartners }
    val targets = conversations.filter { it.canMessage }

    fun send(shared: SongRef, title: String, conversationId: suspend () -> Long) {
        scope.launch {
            try {
                social.api.sendMessage(conversationId(), "", shared)
                Toast.makeText(context, "Sent to $title", Toast.LENGTH_SHORT).show()
                social.refreshConversationsSoon()
                onDismiss()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't send", Toast.LENGTH_SHORT).show()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SectionTitle("Share \"${song.title}\"")
        val shared = clipOptions(song)
        if (targets.isEmpty() && newPeople.isEmpty()) {
            Text(
                "Add friends first, from the Friends tab.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Groups and people are listed separately, so it's clear who will get it.
        val groups = targets.filter { it.isGroup }
        val dms = targets.filter { !it.isGroup }
        val online = friends.filter { it.online }.map { it.user.id }.toSet()
        LazyColumn(Modifier.padding(bottom = 16.dp)) {
            if (groups.isNotEmpty()) {
                item { SectionTitle("Groups") }
                items(groups, key = { "c${it.id}" }) { c ->
                    val title = c.title(me)
                    val others = c.members.filter { it.id != me }.map { it.displayName }
                    ShareTarget(title, "c${c.id}", conversation = c, subtitle = others.joinToString(), group = true) { send(shared, title) { c.id } }
                }
            }
            if (dms.isNotEmpty() || newPeople.isNotEmpty()) {
                item { SectionTitle("People") }
                items(dms, key = { "c${it.id}" }) { c ->
                    val title = c.title(me)
                    val other = c.members.firstOrNull { it.id != me }
                    ShareTarget(title, other?.username ?: "c${c.id}", user = other, online = other?.id in online) { send(shared, title) { c.id } }
                }
                items(newPeople, key = { "f${it.user.id}" }) { f ->
                    ShareTarget(f.user.displayName, f.user.username, user = f.user, online = f.online) {
                        send(shared, f.user.displayName) { social.api.openDm(f.user.id).id }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareTarget(
    title: String,
    key: String,
    user: SocialUser? = null,
    conversation: Conversation? = null,
    subtitle: String? = null,
    group: Boolean = false,
    online: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (group) GroupAvatar(key, size = 40.dp, conversation = conversation) else Avatar(title, key, size = 40.dp, online = online, user = user)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A coloured circle with a group icon, so groups don't look like a person. */
@Composable
fun GroupAvatar(key: String, size: Dp = 44.dp, conversation: Conversation? = null) {
    val picture = conversation?.let { LocalApp.current.social.api.groupPictureUrl(it) }
    Box(
        Modifier.size(size).clip(CircleShape).background(avatarColors[key.hashCode().absoluteValue % avatarColors.size]),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_group), contentDescription = "Group", tint = Color(0xFF1B1726), modifier = Modifier.size(size * 0.55f))
        if (picture != null) FriendsServerPicture(picture, Modifier.matchParentSize())
    }
}

/** A picture kept on the friends server (it needs the login with the request). Its address changes with each new picture. */
@Composable
private fun FriendsServerPicture(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val api = LocalApp.current.social.api
    val request = remember(url) {
        ImageRequest.Builder(context).data(url)
            .httpHeaders(NetworkHeaders.Builder().apply { api.authHeader()?.let { set("Authorization", it) } }.build())
            .build()
    }
    AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

/**
 * The "Share only a part" switch and, when it's on, the clip picker. Returns what to send:
 * the whole [song], or the chosen part of it.
 */
@Composable
fun clipOptions(song: SongRef): SongRef {
    val player = LocalApp.current.player
    val durationMs = song.duration * 1000L
    if (durationMs < 2_000) return song
    var clipping by rememberSaveable(song.id) { mutableStateOf(false) }
    // Start where the song is now if it's playing (whole seconds), and take 30 seconds.
    var clip by remember(song.id) {
        val start = if (player.nowPlaying.value.songId == song.id) player.positionMs() / 1000 * 1000 else 0L
        val from = start.coerceAtMost((durationMs - 1_000).coerceAtLeast(0))
        mutableStateOf(from..(from + 30_000).coerceAtMost(durationMs))
    }
    Row(
        Modifier.fillMaxWidth().clickable { clipping = !clipping }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Share only a part", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = clipping, onCheckedChange = { clipping = it })
    }
    if (clipping) ClipPicker(song, durationMs, clip, onChange = { clip = it })
    return if (clipping) song.copy(clipStartMs = clip.first, clipEndMs = clip.last) else song
}

/**
 * Pick the start and end of a clip on the song's waveform, and preview it. A small pointer above
 * the waveform can be dragged anywhere in the song ("Start here" and "End here" use it); while the
 * preview plays, the pointer follows it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipPicker(song: SongRef, durationMs: Long, clip: LongRange, onChange: (LongRange) -> Unit) {
    val app = LocalApp.current
    val player = app.player
    val now by player.nowPlaying.collectAsStateWithLifecycle()
    val joined by app.social.listen.joined.collectAsStateWithLifecycle()
    // In a listen-together session the music is everyone's: previewing over it would clash, so it waits for a pause.
    val sessionPlaying = joined != null && now.isPlaying

    // The preview plays on its own player, so the queue (and a listen-together session) is left alone.
    val preview = rememberPreviewPlayer(song.id)
    var previewing by remember { mutableStateOf(false) }
    var previewEnd by remember { mutableLongStateOf(0L) }
    // Your own music, paused for the preview, plays again after it.
    var resumeAfter by remember { mutableStateOf(false) }
    var pointer by remember(song.id) { mutableLongStateOf(clip.first) }
    var draggingPointer by remember { mutableStateOf(false) }

    fun stopPreview() {
        preview.pause()
        previewing = false
        if (resumeAfter) {
            resumeAfter = false
            if (!player.nowPlaying.value.isPlaying) player.togglePlay()
        }
    }

    // Plays to the end of the chosen part, or to the end of the song when starting after it.
    fun playPreview(from: Long) {
        if (!previewing && joined == null && player.nowPlaying.value.isPlaying) {
            player.togglePlay()
            resumeAfter = true
        }
        previewEnd = if (from < clip.last) clip.last else durationMs
        if (preview.playbackState == Player.STATE_IDLE) preview.prepare()
        preview.seekTo(from)
        preview.play()
        previewing = true
    }

    LaunchedEffect(previewing) {
        while (previewing) {
            val at = preview.currentPosition
            if (!draggingPointer) pointer = at.coerceAtMost(durationMs)
            if (at >= previewEnd || preview.playbackState == Player.STATE_ENDED) stopPreview()
            delay(50)
        }
    }
    LaunchedEffect(sessionPlaying) { if (sessionPlaying && previewing) stopPreview() }
    DisposableEffect(Unit) {
        onDispose { if (resumeAfter && !player.nowPlaying.value.isPlaying) player.togglePlay() }
    }

    // The song's waveform is the slider's track, like picking a part of a song on Instagram.
    val bars by produceState<FloatArray?>(null, song.id) {
        value = runCatching { app.waveforms.get(song.id, durationMs) }.getOrNull()
    }
    val handle = MaterialTheme.colorScheme.primary
    Column(Modifier.padding(horizontal = 16.dp)) {
        SongPointer(
            fraction = pointer.toFloat() / durationMs,
            onMove = {
                draggingPointer = true
                pointer = (it * durationMs).toLong().coerceIn(0, durationMs)
            },
            onRelease = {
                draggingPointer = false
                if (previewing) playPreview(pointer)
            },
        )
        RangeSlider(
            value = clip.first.toFloat()..clip.last.toFloat(),
            valueRange = 0f..durationMs.toFloat(),
            onValueChange = { r ->
                val start = (r.start.toLong() / 1000 * 1000)
                val end = (r.endInclusive.toLong() / 1000 * 1000).coerceAtLeast(start + 1_000).coerceAtMost(durationMs)
                if (end - start >= 1_000) onChange(start..end)
            },
            startThumb = { ClipHandle(handle) },
            endThumb = { ClipHandle(handle) },
            track = { state ->
                val range = state.valueRange.endInclusive - state.valueRange.start
                Waveform(
                    bars,
                    from = (state.activeRangeStart - state.valueRange.start) / range,
                    to = (state.activeRangeEnd - state.valueRange.start) / range,
                    pointer = pointer.toFloat() / durationMs,
                )
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clockTime(clip.first), style = MaterialTheme.typography.bodySmall)
            Text(
                "${(clip.last - clip.first) / 1000} seconds · pointer at ${clockTime(pointer)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(clockTime(clip.last), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            OutlinedButton(
                onClick = { if (previewing) stopPreview() else playPreview(clip.first) },
                enabled = previewing || !sessionPlaying,
            ) {
                Icon(painterResource(if (previewing) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = null, Modifier.size(18.dp))
                Text(if (previewing) "Stop" else "Preview", Modifier.padding(start = 6.dp))
            }
            TextButton(onClick = {
                val at = pointer / 1000 * 1000
                if (at + 1_000 <= durationMs) onChange(at..clip.last.coerceAtLeast(at + 1_000).coerceAtMost(durationMs))
            }) { Text("Start here") }
            TextButton(onClick = {
                val at = pointer / 1000 * 1000
                if (at >= 1_000) onChange(clip.first.coerceAtMost(at - 1_000)..at)
            }) { Text("End here") }
        }
        if (sessionPlaying) {
            Text(
                "Pause the listen-together music to preview",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A player just for previewing a part of [songId], separate from the app's music player. */
@Composable
private fun rememberPreviewPlayer(songId: String): ExoPlayer {
    val context = LocalContext.current
    val api = LocalApp.current.api
    val preview = remember(songId) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ false, // the preview pauses your music itself, and plays it again after
            )
            .build()
            .apply { setMediaItem(MediaItem.fromUri(api.streamUrl(songId))) }
    }
    DisposableEffect(preview) { onDispose { preview.release() } }
    return preview
}

/**
 * The pointer above the waveform: a small triangle at [fraction] of the song. Tap or drag
 * anywhere along it to move it ([onMove] gets 0 to 1); [onRelease] when the finger lifts.
 */
@Composable
private fun SongPointer(fraction: Float, onMove: (Float) -> Unit, onRelease: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)
    // Inset by half a handle's width, like the slider's track, so the pointer lines up with the bars.
    Canvas(
        Modifier.fillMaxWidth().padding(horizontal = 3.dp).height(24.dp)
            .pointerInput(Unit) {
                detectTapGestures { move((it.x / size.width).coerceIn(0f, 1f)); release() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { move((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, _ -> move((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val x = fraction.coerceIn(0f, 1f) * size.width
        val half = 7.dp.toPx()
        drawPath(
            Path().apply {
                moveTo(x - half, size.height - 2 * half)
                lineTo(x + half, size.height - 2 * half)
                lineTo(x, size.height)
                close()
            },
            color,
        )
    }
}

/** A tall rounded bar that marks the start or end of the part being shared. */
@Composable
private fun ClipHandle(color: Color) {
    Box(Modifier.size(width = 6.dp, height = 64.dp).background(color, RoundedCornerShape(3.dp)))
}

/**
 * The song as bars of loudness: the chosen part ([from] to [to], as fractions of the song) in the
 * accent colour, the rest faded. A line marks the [pointer]. Until the
 * waveform is ready ([bars] is null) the bars sit flat and dim.
 */
@Composable
private fun Waveform(bars: FloatArray?, from: Float, to: Float, pointer: Float) {
    val chosen = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val line = MaterialTheme.colorScheme.onSurface
    // Grow from flat to the real shape once it arrives.
    val grow by animateFloatAsState(if (bars == null) 0f else 1f, tween(500), label = "waveform")
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val count = bars?.size ?: Waveforms.BARS
        val slot = size.width / count
        val barWidth = slot * 0.6f
        for (i in 0 until count) {
            val level = Waveforms.MIN_BAR + ((bars?.get(i) ?: Waveforms.MIN_BAR) - Waveforms.MIN_BAR) * grow
            val h = size.height * level
            val centre = (i + 0.5f) / count
            drawRoundRect(
                color = if (centre in from..to) chosen else rest,
                topLeft = Offset(i * slot + (slot - barWidth) / 2, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
        val x = pointer.coerceIn(0f, 1f) * size.width
        drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    }
}
