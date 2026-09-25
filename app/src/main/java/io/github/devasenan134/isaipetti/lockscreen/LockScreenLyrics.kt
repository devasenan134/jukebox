package io.github.devasenan134.isaipetti.lockscreen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.devasenan134.isaipetti.IsaipettiApp
import io.github.devasenan134.isaipetti.R

/**
 * Shows the lyrics page over the lock screen: when the screen turns off while music plays (and
 * the setting is on), it asks Android to open [LockLyricsActivity]. Apps can only open a screen
 * from the background with a "full-screen" notification (the way alarm and call apps do); the
 * page opens behind the dark screen and is there when the phone is turned on, then removes the
 * notification.
 *
 * The playback service creates this while it runs, so it only listens while there's music.
 */
class LockScreenLyrics(private val context: Context, private val isPlaying: () -> Boolean) {
    private val app = context.applicationContext as IsaipettiApp

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF && app.lockScreen.lyrics.value && isPlaying()) show()
        }
    }

    fun register() {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun show() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!canShow(context)) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Lyrics on the lock screen", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens the lyrics over the lock screen. It goes away by itself."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, LockLyricsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("Lyrics")
            .setContentText("Showing the lyrics on the lock screen")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setFullScreenIntent(open, true)
            .setTimeoutAfter(15_000)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL = "lockscreen"
        const val NOTIFICATION_ID = 7_001

        /** Android allows full-screen notifications for this app (Android 14 lets people turn it off). */
        fun canShow(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return false
            return Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()
        }
    }
}
