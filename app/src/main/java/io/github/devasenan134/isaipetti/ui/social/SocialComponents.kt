package io.github.devasenan134.isaipetti.ui.social

import io.github.devasenan134.isaipetti.data.Conversation
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

/** Pick the start and end of a clip with a two-handled slider, and preview it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipPicker(song: SongRef, durationMs: Long, clip: LongRange, onChange: (LongRange) -> Unit) {
    val player = LocalApp.current.player
    val now by player.nowPlaying.collectAsStateWithLifecycle()
    val isCurrent = now.songId == song.id
    // Previewing the song that's already playing just jumps to the start and pauses at the end,
    // so the queue stays as it is.
    var previewing by remember { mutableStateOf<LongRange?>(null) }
    LaunchedEffect(previewing) {
        val range = previewing ?: return@LaunchedEffect
        player.seekTo(range.first)
        if (!player.nowPlaying.value.isPlaying) player.togglePlay()
        while (player.positionMs() < range.last && player.nowPlaying.value.songId == song.id) delay(100)
        if (player.nowPlaying.value.isPlaying) player.togglePlay()
        previewing = null
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        RangeSlider(
            value = clip.first.toFloat()..clip.last.toFloat(),
            valueRange = 0f..durationMs.toFloat(),
            onValueChange = { r ->
                val start = (r.start.toLong() / 1000 * 1000)
                val end = (r.endInclusive.toLong() / 1000 * 1000).coerceAtLeast(start + 1_000).coerceAtMost(durationMs)
                if (end - start >= 1_000) onChange(start..end)
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clockTime(clip.first), style = MaterialTheme.typography.bodySmall)
            Text(
                "${(clip.last - clip.first) / 1000} seconds",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(clockTime(clip.last), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            OutlinedButton(onClick = {
                if (isCurrent) previewing = clip else player.playClip(song.copy(clipStartMs = clip.first, clipEndMs = clip.last))
            }) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, Modifier.size(18.dp))
                Text("Preview", Modifier.padding(start = 6.dp))
            }
            // While this song is playing, the start and end can be set from where it is right now.
            if (isCurrent) {
                TextButton(onClick = {
                    val at = player.positionMs() / 1000 * 1000
                    if (at + 1_000 <= durationMs) onChange(at..clip.last.coerceAtLeast(at + 1_000).coerceAtMost(durationMs))
                }) { Text("Start here") }
                TextButton(onClick = {
                    val at = player.positionMs() / 1000 * 1000
                    if (at >= 1_000) onChange(clip.first.coerceAtMost(at - 1_000)..at)
                }) { Text("End here") }
            }
        }
    }
}
