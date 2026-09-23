package io.github.devasenan134.isaipetti.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import io.github.devasenan134.isaipetti.data.ListenState
import io.github.devasenan134.isaipetti.data.SongRef
import io.github.devasenan134.isaipetti.data.SubsonicApi
import io.github.devasenan134.isaipetti.social.ListenTogether
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Keeps the player in step with a listen-together session, both ways:
 *  - changes from the others (a new queue, skip, seek, play/pause) are applied to our player;
 *  - changes we make are sent to them.
 *
 * Shuffle is off during a session, so everyone's queue plays in the same order. Pauses that only
 * concern this phone (a phone call, unplugged headphones) aren't sent. A song ending on its own
 * isn't sent either: everyone's player moves on by itself.
 */
class ListenSync(
    private val player: ExoPlayer,
    private val api: SubsonicApi,
    private val listen: ListenTogether,
    scope: CoroutineScope,
) {
    /** Which queue the player holds, as the session knows it. */
    private var queueId = ListenTogether.newQueueId()

    // Player callbacks for our own changes arrive while [applying] is set; those aren't sent back.
    private var applying = false
    private var changed = false
    private var queueChanged = false

    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || applying) return
            queueId = ListenTogether.newQueueId()
            queueChanged = true
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!applying && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) changed = true
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!applying && reason == Player.DISCONTINUITY_REASON_SEEK) changed = true
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!applying && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) changed = true
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled && listen.joined.value != null) player.shuffleModeEnabled = false
        }

        // Called once after each batch of the callbacks above.
        override fun onEvents(player: Player, events: Player.Events) {
            if (!changed && !queueChanged) return
            if (listen.joined.value != null) listen.update(snapshot(includeQueue = queueChanged))
            changed = false
            queueChanged = false
        }
    }

    init {
        listen.snapshot = { snapshot(includeQueue = true) }
        player.addListener(listener)
        scope.launch {
            listen.joined.collect { if (it != null) player.shuffleModeEnabled = false }
        }
        scope.launch {
            listen.remote.collect { it?.let(::apply) }
        }
    }

    fun release() {
        player.removeListener(listener)
        listen.snapshot = null
    }

    private fun snapshot(includeQueue: Boolean) = ListenState(
        queue = if (includeQueue) (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSongRef() } else null,
        queueId = queueId,
        index = player.currentMediaItemIndex.coerceAtLeast(0),
        positionMs = player.currentPosition,
        playing = player.playWhenReady,
    )

    private fun apply(remote: ListenTogether.Remote) {
        val state = remote.state
        val queue = state.queue
        // An update for a queue we don't have (shouldn't happen): wait for the next full one.
        if (queue == null && state.queueId != queueId) return
        applying = true
        try {
            val position = remote.positionNow()
            if (queue != null && (state.queueId != queueId || !holds(queue))) {
                queueId = state.queueId
                replaceQueue(queue, state.index, position)
            } else if (player.currentMediaItemIndex != state.index) {
                if (state.index < player.mediaItemCount) player.seekTo(state.index, position)
            } else if (abs(player.currentPosition - position) > MAX_DRIFT_MS) {
                player.seekTo(position)
            }
            player.shuffleModeEnabled = false
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
            player.playWhenReady = state.playing
        } finally {
            applying = false
            changed = false
            queueChanged = false
        }
    }

    private fun holds(queue: List<SongRef>) =
        player.mediaItemCount == queue.size && queue.indices.all { player.getMediaItemAt(it).mediaId == queue[it].id }

    /**
     * Puts the session's queue in the player. If the song playing now is the one the session
     * wants, it keeps playing and only the songs around it change (no gap in the music).
     */
    private fun replaceQueue(queue: List<SongRef>, index: Int, positionMs: Long) {
        val items = queue.map { it.toSong().toMediaItem(api) }
        val current = player.currentMediaItemIndex
        if (current >= 0 && current < player.mediaItemCount && index in items.indices &&
            player.getMediaItemAt(current).mediaId == items[index].mediaId
        ) {
            player.removeMediaItems(current + 1, player.mediaItemCount)
            player.removeMediaItems(0, current)
            player.addMediaItems(0, items.subList(0, index))
            player.addMediaItems(items.subList(index + 1, items.size))
            if (abs(player.currentPosition - positionMs) > MAX_DRIFT_MS) player.seekTo(positionMs)
        } else if (items.isEmpty()) {
            player.clearMediaItems()
        } else {
            player.setMediaItems(items, index.coerceIn(items.indices), positionMs)
        }
    }

    private companion object {
        /** How far apart two phones may drift before we jump to the right spot. */
        const val MAX_DRIFT_MS = 1_500L
    }
}
