package io.github.devasenan134.isaipetti

import android.app.Application
import io.github.devasenan134.isaipetti.data.SessionStore
import io.github.devasenan134.isaipetti.data.Likes
import io.github.devasenan134.isaipetti.data.RecentPlaylists
import io.github.devasenan134.isaipetti.data.RecentSongs
import io.github.devasenan134.isaipetti.data.SearchHistory
import io.github.devasenan134.isaipetti.data.SubsonicApi
import io.github.devasenan134.isaipetti.data.Updates
import io.github.devasenan134.isaipetti.playback.PlayerConnection
import io.github.devasenan134.isaipetti.push.Notifications
import io.github.devasenan134.isaipetti.push.PushSetup
import io.github.devasenan134.isaipetti.social.Social
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Created once when the app process starts. It holds the app-wide objects that
 * screens and the playback service share (a very small hand-made "dependency container").
 */
class IsaipettiApp : Application() {
    private val appScope = MainScope()

    lateinit var session: SessionStore
        private set
    lateinit var api: SubsonicApi
        private set
    lateinit var player: PlayerConnection
        private set
    lateinit var social: Social
        private set
    lateinit var updates: Updates
        private set
    lateinit var recent: RecentSongs
        private set
    lateinit var recentPlaylists: RecentPlaylists
        private set
    lateinit var searches: SearchHistory
        private set
    lateinit var likes: Likes
        private set

    /** A screen to open, set when the app is launched from a notification. */
    val pendingOpen = MutableStateFlow<PendingOpen?>(null)

    override fun onCreate() {
        super.onCreate()
        session = SessionStore(this).also { it.load() }
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        api = SubsonicApi(http, credentials = { session.credentials.value }, onLoginRejected = { rejected ->
            // Only if the rejected login is still the saved one (not an old request racing a password change).
            appScope.launch {
                if (session.credentials.value == rejected) {
                    player.stop()
                    recent.clear()
                    recentPlaylists.clear()
                    searches.clearAll()
                    likes.clear()
                    social.logout() // also stops notifications to this phone
                    session.clear("Your password was changed. Log in again with the new one.")
                }
            }
        })
        player = PlayerConnection(this, api)
        // Notifications can wake the app before any screen opens: start Firebase from the saved settings first.
        PushSetup.startSaved(this)
        social = Social(this, session, http)
        updates = Updates(this, http)
        recent = RecentSongs(this)
        recentPlaylists = RecentPlaylists(this)
        searches = SearchHistory(this)
        likes = Likes(this, api, session) { social.api }
        if (session.credentials.value != null) likes.refresh()
        appScope.launch { updates.checkNowAndThen() }
        Notifications.createChannels(this)
    }
}

/** Where a tapped notification should take you. */
sealed interface PendingOpen {
    data class Chat(val conversationId: Long) : PendingOpen
    data object Friends : PendingOpen
}
