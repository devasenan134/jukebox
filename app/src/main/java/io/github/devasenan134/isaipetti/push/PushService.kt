package io.github.devasenan134.isaipetti.push

import io.github.devasenan134.isaipetti.IsaipettiApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives push messages from Firebase, even when the app is closed.
 * The friends server sends "data" messages; [Notifications] turns them into notifications.
 */
class PushService : FirebaseMessagingService() {
    private val app get() = application as IsaipettiApp

    /** Firebase gave this phone a new address for notifications; tell the friends server. */
    override fun onNewToken(token: String) {
        app.social.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Notifications.show(this, message.data, app.social.openConversationId)
    }
}
