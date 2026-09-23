package io.github.devasenan134.isaipetti.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.devasenan134.isaipetti.IsaipettiApp
import io.github.devasenan134.isaipetti.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays music in the background. Android keeps this service alive while music plays,
 * and Media3 shows the notification and lock-screen controls for it.
 * The UI never touches the player directly; it sends commands through [PlayerConnection].
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var listenSync: ListenSync? = null
    private val scope = MainScope()
    private val app get() = application as IsaipettiApp
    private val api get() = app.api

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Turn "isaipetti://song/<id>" into a stream URL with the current login, right when it's needed.
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec ->
            if (spec.uri.scheme == SONG_SCHEME) spec.withUri(api.streamUrl(spec.uri.lastPathSegment!!).toUri()) else spec
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // pause for phone calls and other apps
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK) // keep streaming with the screen off
            .build()

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        startScrobbling(player)
        rememberQueues(player)
        stopAtClipEnds(player)
        listenSync = ListenSync(player, api, app.social.listen, scope)
        // Tell friends what's playing (only while it's actually playing).
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) {
                    app.social.onPlayback(player.currentMediaItem?.toSongRef(), player.isPlaying)
                }
                // Remember songs that actually play, for "recently played" when sharing in a chat.
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying) {
                    player.currentMediaItem?.let { app.recent.played(it.toSongRef()) }
                }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        app.social.onPlayback(null, false)
        // Without a player there's nothing to keep in sync.
        listenSync?.release()
        listenSync = null
        app.social.listen.leave()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        // Items sent from the UI arrive without their address, so rebuild it from the song id.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { it.buildUpon().setUri(songUri(it.mediaId)).build() }.toMutableList()
        )
    }

    /**
     * For queues started from a playlist: saves the play order (shuffled or not) and the current song
     * whenever either changes, so opening that playlist later can offer to resume.
     */
    private fun rememberQueues(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) return
                val current = player.currentMediaItem ?: return
                val source = current.mediaMetadata.extras?.getString(EXTRA_SOURCE) ?: return
                val timeline = player.currentTimeline
                val ids = mutableListOf<String>()
                var index = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
                while (index != C.INDEX_UNSET) {
                    val item = player.getMediaItemAt(index)
                    // Only the songs that came from this source (songs added to the queue later don't count).
                    if (item.mediaMetadata.extras?.getString(EXTRA_SOURCE) == source) ids += item.mediaId
                    index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
                }
                app.queueMemory.save(source, ids, current.mediaId)
            }
        })
    }

    /** A shared clip pauses once at its end point; pressing play afterwards carries on with the rest of the song. */
    private fun stopAtClipEnds(player: ExoPlayer) {
        var stoppedFor: MediaItem? = null
        scope.launch {
            while (isActive) {
                delay(100)
                val item = player.currentMediaItem ?: continue
                val end = item.mediaMetadata.extras?.getLong(EXTRA_CLIP_END_MS, -1L)?.takeIf { it > 0 } ?: continue
                if (item !== stoppedFor && player.isPlaying && player.currentPosition >= end) {
                    stoppedFor = item
                    player.pause()
                }
            }
        }
    }

    /**
     * Reports plays to Navidrome: "now playing" when a song starts, then a real play
     * after half the song (or 4 minutes). This is the listening history the ML playlists will use.
     */
    private fun startScrobbling(player: ExoPlayer) {
        var submittedId: String? = null
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                submittedId = null
                val id = mediaItem?.mediaId ?: return
                scope.launch { runCatching { api.scrobble(id, submission = false) } }
            }
        })
        scope.launch {
            while (isActive) {
                delay(5_000)
                val id = player.currentMediaItem?.mediaId ?: continue
                val duration = player.duration
                if (id == submittedId || duration <= 0) continue
                if (player.currentPosition >= minOf(duration / 2, 240_000L)) {
                    submittedId = id
                    launch { runCatching { api.scrobble(id, submission = true) } }
                }
            }
        }
    }
}
