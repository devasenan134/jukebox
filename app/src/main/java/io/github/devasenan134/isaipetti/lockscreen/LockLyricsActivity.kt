package io.github.devasenan134.isaipetti.lockscreen

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.devasenan134.isaipetti.IsaipettiApp
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.player.LyricsView
import io.github.devasenan134.isaipetti.ui.player.rememberCoverColor
import io.github.devasenan134.isaipetti.ui.player.rememberLyrics
import io.github.devasenan134.isaipetti.ui.player.rememberPosition
import io.github.devasenan134.isaipetti.ui.theme.IsaipettiTheme
import io.github.devasenan134.isaipetti.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The lyrics over the lock screen: the time, a small player and the synced lyrics, on the song's
 * colours, a little see-through so the lock screen shows faintly behind. The phone stays locked:
 * swipe up (or Back) to get to the normal lock screen, and unlocking closes it too.
 */
class LockLyricsActivity : ComponentActivity() {
    private val app get() = application as IsaipettiApp

    // Unlocking the phone (the lock screen goes away) closes this.
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        // The notification that opened this has done its job.
        getSystemService(NotificationManager::class.java).cancel(LockScreenLyrics.NOTIFICATION_ID)
        ContextCompat.registerReceiver(this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            val mode by app.appearance.mode.collectAsStateWithLifecycle()
            val wallpaper by app.appearance.wallpaper.collectAsStateWithLifecycle()
            IsaipettiTheme(mode, wallpaper) {
                CompositionLocalProvider(LocalApp provides app) {
                    LockLyricsScreen(onClose = ::finish, keepScreenOn = ::keepScreenOn)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        getSystemService(NotificationManager::class.java).cancel(LockScreenLyrics.NOTIFICATION_ID)
    }

    // The player's controls work while this is on screen, like the app's own screens.
    override fun onStart() {
        super.onStart()
        app.player.connect()
    }

    override fun onStop() {
        app.player.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        super.onDestroy()
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun LockLyricsScreen(onClose: () -> Unit, keepScreenOn: (Boolean) -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val player = app.player
    val now by player.nowPlaying.collectAsStateWithLifecycle()
    val keepOn by app.lockScreen.keepScreenOn.collectAsStateWithLifecycle()
    val position by rememberPosition(now.songId, now.isPlaying)
    val lyrics by rememberLyrics(now.songId)
    // In someone else's jam the music follows them: no controls here either.
    val joined by app.social.listen.joined.collectAsStateWithLifecycle()
    val owners by app.social.listen.owners.collectAsStateWithLifecycle()
    val jamOwner = joined?.let { owners[it] }
    val listening = jamOwner != null && jamOwner != app.social.me?.id

    // The screen stays on while the music plays (if that's on in Settings); paused, it times out as usual.
    LaunchedEffect(keepOn, now.isPlaying) { keepScreenOn(keepOn && now.isPlaying) }
    // Nothing playing anymore (the music was stopped): nothing to show.
    LaunchedEffect(now.songId) {
        if (now.songId == null) {
            delay(3_000) // the player may still be connecting
            if (player.nowPlaying.value.songId == null) onClose()
        }
    }

    // The song's colour at the top easing into the dark, both a little see-through.
    val base = MaterialTheme.colorScheme.surface
    val cover = rememberCoverColor(now.artworkUri, darkTheme = base.luminance() < 0.5f)
    val tint by animateColorAsState(cover ?: base, tween(700), label = "cover tint")
    val clock by produceState(LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(1_000)
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to tint.copy(alpha = 0.92f), 0.5f to tint.copy(alpha = 0.85f), 1f to base.copy(alpha = 0.88f)),
        ),
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
            Text(
                clock.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm")),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
            )
            // The small player: cover, title and artist, and the controls.
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = now.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(now.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        now.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = player::previous, enabled = !listening) {
                    Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous")
                }
                FilledIconButton(onClick = player::togglePlay, enabled = !listening, modifier = Modifier.size(56.dp)) {
                    Icon(
                        painterResource(if (now.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (now.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = player::next, enabled = !listening) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next")
                }
            }
            LyricsView(
                lyrics, position,
                onSeek = { if (!listening) player.seekTo(it) },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
            )
            // Swipe up here (or tap) to go to the normal lock screen.
            Column(
                Modifier.fillMaxWidth()
                    .pointerInput(Unit) {
                        var pulled = 0f
                        detectVerticalDragGestures(
                            onDragStart = { pulled = 0f },
                            onDragEnd = { if (pulled < -60f) onClose() },
                        ) { change, amount ->
                            pulled += amount
                            change.consume()
                        }
                    }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Close the lyrics") }
                Text("Swipe up to close", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
