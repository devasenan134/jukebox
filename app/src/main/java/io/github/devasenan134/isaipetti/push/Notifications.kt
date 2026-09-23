package io.github.devasenan134.isaipetti.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import io.github.devasenan134.isaipetti.MainActivity
import io.github.devasenan134.isaipetti.R

/** Builds the notifications for chat messages, friend requests and friends listening together. */
object Notifications {
    private const val CHATS = "chats"
    private const val FRIENDS = "friends"
    private const val LISTEN = "listen"
    private const val LISTEN_TAG = "listen"
    private const val FRIENDS_ID = 1
    private const val CHAT_TAG = "chat"

    /** Extras on the intent that opens the app from a notification. */
    const val EXTRA_CONVERSATION = "conversationId"
    const val EXTRA_OPEN_FRIENDS = "openFriends"

    /** Android 8+ groups notifications into channels people can mute separately in system settings. */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHATS, "Chat messages", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(FRIENDS, "Friend requests", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(LISTEN, "Friends listening together", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(context: Context, data: Map<String, String>, openConversationId: Long?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        when (data["type"]) {
            "message" -> message(context, data, openConversationId)
            "friendRequest", "friendAccepted" -> friends(context, data)
            "listen" -> listening(context, data, openConversationId)
        }
    }

    /** Removes a chat's notification (when you open that chat). */
    fun clearChat(context: Context, conversationId: Long) {
        NotificationManagerCompat.from(context).cancel(CHAT_TAG, conversationId.toInt())
    }

    private fun message(context: Context, data: Map<String, String>, openConversationId: Long?) {
        val conversationId = data["conversationId"]?.toLongOrNull() ?: return
        if (conversationId == openConversationId) return // you're looking at it already
        val manager = NotificationManagerCompat.from(context)
        val id = conversationId.toInt()

        // Add to the chat's existing notification, so several messages stack up like in other chat apps.
        val existing = manager.activeNotifications.firstOrNull { it.tag == CHAT_TAG && it.id == id }?.notification
        val style = existing?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
            ?: NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        val isGroup = data["isGroup"] == "true"
        style.isGroupConversation = isGroup
        if (isGroup) style.conversationTitle = data["title"]
        style.addMessage(data["body"].orEmpty(), System.currentTimeMillis(), Person.Builder().setName(data["sender"].orEmpty()).build())

        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_CONVERSATION, conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(context, CHATS)
            .setSmallIcon(R.drawable.ic_music_note)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        runCatching { manager.notify(CHAT_TAG, id, notification) }
    }

    /** "Alice started listening together. Tap to join": opens that chat, where Join is. */
    private fun listening(context: Context, data: Map<String, String>, openConversationId: Long?) {
        val conversationId = data["conversationId"]?.toLongOrNull() ?: return
        if (conversationId == openConversationId) return
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_CONVERSATION, conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(context, LISTEN)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(data["title"])
            .setContentText(data["body"])
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(context, LISTEN_REQUEST + conversationId.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(LISTEN_TAG, conversationId.toInt(), notification) }
    }

    private const val LISTEN_REQUEST = 1_000_000

    private fun friends(context: Context, data: Map<String, String>) {
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_FRIENDS, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(context, FRIENDS)
            .setSmallIcon(R.drawable.ic_person_add)
            .setContentTitle(data["title"])
            .setContentText(data["body"])
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, FRIENDS_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(FRIENDS_ID, notification) }
    }
}
