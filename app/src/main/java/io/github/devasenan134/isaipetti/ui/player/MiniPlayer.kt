package io.github.devasenan134.isaipetti.ui.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** The small "now playing" bar above the bottom tabs. Tap it to open the full player. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val app = LocalApp.current
    val player = app.player
    val now by player.nowPlaying.collectAsStateWithLifecycle()
    if (now.songId == null) return
    // In a jam, only its owner controls the music: listeners get a jam icon instead of the buttons.
    val joined by app.social.listen.joined.collectAsStateWithLifecycle()
    val owners by app.social.listen.owners.collectAsStateWithLifecycle()
    val jamOwner = joined?.let { owners[it] }
    val ownsJam = jamOwner != null && jamOwner == app.social.me?.id
    val listening = jamOwner != null && !ownsJam
    val position by rememberPosition(now.songId, now.isPlaying, intervalMs = 500)
    val scope = rememberCoroutineScope()
    // How far the bar is dragged sideways. Swipe past the threshold to skip, then it springs back.
    val dragX = remember { Animatable(0f) }
    val swipe = if (listening) Modifier else Modifier.pointerInput(Unit) {
        val threshold = 80.dp.toPx()
        detectHorizontalDragGestures(
            onDragEnd = {
                when {
                    dragX.value < -threshold -> player.nextSong()
                    dragX.value > threshold -> player.previousSong()
                }
                scope.launch { dragX.animateTo(0f) }
            },
            onDragCancel = { scope.launch { dragX.animateTo(0f) } },
        ) { change, amount ->
            change.consume()
            scope.launch { dragX.snapTo(dragX.value + amount) }
        }
    }

    Surface(tonalElevation = 3.dp) {
        Column {
            LinearProgressIndicator(
                progress = { if (now.durationMs > 0) (position.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
            )
            Row(
                Modifier.fillMaxWidth()
                    .then(swipe)
                    .graphicsLayer { translationX = dragX.value; alpha = 1f - (kotlin.math.abs(dragX.value) / size.width).coerceIn(0f, 0.6f) }
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = now.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(now.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        now.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (listening) {
                    // Nothing to control: the jam's owner does. The icon opens the player.
                    IconButton(onClick = onOpen) {
                        Icon(
                            painterResource(R.drawable.ic_jam),
                            contentDescription = "Listening together",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    if (ownsJam) JamSpinner(spinning = now.isPlaying)
                    IconButton(onClick = player::togglePlay) {
                        Icon(
                            painterResource(if (now.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = if (now.isPlaying) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = player::next) {
                        Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next")
                    }
                }
            }
        }
    }
}

/** A small record that turns while your jam plays (you run it), and rests where it stopped when paused. */
@Composable
private fun JamSpinner(spinning: Boolean) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        while (spinning) {
            angle.animateTo(angle.value + 360f, animationSpec = tween(durationMillis = 2_400, easing = LinearEasing))
            angle.snapTo(angle.value % 360f)
        }
    }
    Icon(
        painterResource(R.drawable.ic_jam),
        contentDescription = "You're running a jam",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(end = 4.dp).size(22.dp).graphicsLayer { rotationZ = angle.value },
    )
}
