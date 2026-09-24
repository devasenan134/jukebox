package io.github.devasenan134.isaipetti

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.devasenan134.isaipetti.push.Notifications
import io.github.devasenan134.isaipetti.ui.AppRoot
import io.github.devasenan134.isaipetti.ui.theme.IsaipettiTheme
import io.github.devasenan134.isaipetti.ui.theme.isAppInDarkTheme
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val app get() = application as IsaipettiApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationTap(intent)
        // Back with nowhere left to go closes the app, and the music with it. (Screens and the
        // player handle Back themselves first; this only runs when none of them does. Home still
        // leaves the music playing.)
        onBackPressedDispatcher.addCallback(this) {
            app.player.stop()
            finish()
        }
        setContent {
            val mode by app.appearance.mode.collectAsStateWithLifecycle()
            val wallpaper by app.appearance.wallpaper.collectAsStateWithLifecycle()
            // Status and navigation bar icons follow the app's light/dark choice, not just the phone's.
            val dark = isAppInDarkTheme(mode)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            IsaipettiTheme(mode, wallpaper) {
                AppRoot(app)
            }
        }
    }

    // The app is already open and a notification was tapped.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationTap(intent)
    }

    private fun handleNotificationTap(intent: Intent?) {
        val conversationId = intent?.getLongExtra(Notifications.EXTRA_CONVERSATION, -1L) ?: -1L
        when {
            conversationId > 0 -> app.pendingOpen.value = PendingOpen.Chat(conversationId)
            intent?.getBooleanExtra(Notifications.EXTRA_OPEN_FRIENDS, false) == true -> app.pendingOpen.value = PendingOpen.Friends
        }
    }

    // Connect to the playback service while the app is on screen. Music keeps playing
    // after we disconnect, because the service runs on its own.
    override fun onStart() {
        super.onStart()
        app.player.connect()
        app.social.setForeground(true)
    }

    override fun onStop() {
        app.player.disconnect()
        app.social.setForeground(false)
        super.onStop()
    }
}
