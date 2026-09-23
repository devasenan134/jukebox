package io.github.devasenan134.isaipetti.playback

import androidx.media3.common.Player
import io.github.devasenan134.isaipetti.data.Mix
import io.github.devasenan134.isaipetti.data.PlayEvent
import io.github.devasenan134.isaipetti.data.SocialApi
import io.github.devasenan134.isaipetti.data.SubsonicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tells the friends server which songs you listened to and which you skipped. Navidrome only hears
 * about songs played to the halfway point; skips are what teach the mixes what you don't want.
 *
 * A skip is moving on to another song in the first 30 seconds. Nothing is reported while listening
 * together (someone else may be skipping) or for shared clips.
 */
class PlayReporter(
    player: Player,
    private val social: () -> SocialApi?,
    private val listeningTogether: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val pending = mutableListOf<PlayEvent>()

    init {
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                val item = oldPosition.mediaItem ?: return
                val finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                val movedOn = reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex != newPosition.mediaItemIndex
                if (!finished && !movedOn) return // a seek inside the song, or the queue being replaced
                val extras = item.mediaMetadata.extras
                if (listeningTogether() || (extras?.getLong(EXTRA_CLIP_END_MS, -1L) ?: -1L) > 0) return
                val duration = item.mediaMetadata.durationMs ?: 0
                val played = if (finished) duration else oldPosition.positionMs
                add(
                    PlayEvent(
                        songId = item.mediaId,
                        at = System.currentTimeMillis(),
                        playedMs = played,
                        durationMs = duration,
                        skipped = movedOn && played < SKIP_BEFORE_MS,
                        source = extras?.getString(EXTRA_SOURCE),
                    ),
                )
            }
        })
        // Send in small batches, at most once a minute.
        scope.launch {
            while (isActive) {
                delay(60_000)
                flush()
            }
        }
    }

    private fun add(event: PlayEvent) {
        pending += event
        if (pending.size > MAX_PENDING) pending.subList(0, pending.size - MAX_PENDING).clear()
        if (pending.size >= 10) scope.launch { flush() }
    }

    private suspend fun flush() {
        val api = social() ?: return
        if (pending.isEmpty()) return
        val batch = pending.toList()
        runCatching { api.recordPlays(batch) }.onSuccess { pending.removeAll(batch) }
    }

    private companion object {
        const val SKIP_BEFORE_MS = 30_000L
        const val MAX_PENDING = 300
    }
}

/**
 * Keeps a station (a mix whose [Mix.endless] is set) playing forever: when fewer than 5 of its songs
 * are left in the queue, it asks the friends server for more like it and adds them to the end.
 */
class Stations(
    private val player: Player,
    private val api: SubsonicApi,
    private val social: () -> SocialApi?,
    private val scope: CoroutineScope,
) {
    private var fetching = false

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) topUp()
            }
        })
    }

    private fun topUp() {
        if (fetching) return
        val source = player.currentMediaItem?.mediaMetadata?.extras?.getString(EXTRA_SOURCE) ?: return
        if (!source.startsWith(Mix.SOURCE_PREFIX + "radio-")) return
        val stationId = source.removePrefix(Mix.SOURCE_PREFIX)
        val current = player.currentMediaItemIndex
        val left = (current + 1 until player.mediaItemCount).count { player.getMediaItemAt(it).mediaMetadata.extras?.getString(EXTRA_SOURCE) == source }
        if (left >= 5 || player.repeatMode == Player.REPEAT_MODE_ONE) return
        val api = social() ?: return
        fetching = true
        scope.launch {
            val queued = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            runCatching { api.radio(stationId, queued.takeLast(500), count = 20) }.onSuccess { more ->
                // Still the same station? (You may have started something else meanwhile.)
                if (player.currentMediaItem?.mediaMetadata?.extras?.getString(EXTRA_SOURCE) == source) {
                    player.addMediaItems(more.songs.map { it.toSong().toMediaItem(this@Stations.api, source = source) })
                }
            }
            fetching = false
        }
    }
}
