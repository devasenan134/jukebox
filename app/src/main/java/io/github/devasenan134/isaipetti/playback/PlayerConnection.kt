package io.github.devasenan134.isaipetti.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.devasenan134.isaipetti.data.Song
import io.github.devasenan134.isaipetti.data.SongRef
import io.github.devasenan134.isaipetti.data.SubsonicApi
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the UI shows about playback. */
data class NowPlaying(
    val songId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String? = null,
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** The current song in the form friends can play too (for sharing). */
    val song: SongRef? = null,
    /** What the queue was started from, e.g. "playlist:<id>". */
    val source: String? = null,
)

/** A queue entry. [index] is its position in the player's list, used to jump to it. */
data class QueueEntry(val index: Int, val item: MediaItem)

/**
 * The UI's remote control for [PlaybackService]. It connects through a MediaController
 * and turns player events into [StateFlow]s that Compose screens can observe.
 */
class PlayerConnection(private val context: Context, private val api: SubsonicApi) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying

    /** The queue in play order (follows shuffle), plus which entry is current. */
    private val _queue = MutableStateFlow<Pair<List<QueueEntry>, Int>>(emptyList<QueueEntry>() to C.INDEX_UNSET)
    val queue: StateFlow<Pair<List<QueueEntry>, Int>> = _queue

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    /**
     * Listening along in someone else's jam: the music follows its owner, so controls do nothing
     * (and say why), and "play next" / "add to queue" ask the owner instead. Set by the app.
     */
    interface Jam {
        fun isListener(): Boolean
        /** Asks the owner to play [song] next, or right away (skipping the current song) if [playNow]. */
        fun request(song: Song, playNow: Boolean)
        fun explain()
    }

    var jam: Jam? = null

    /** True (after explaining) when controls are off because someone else runs the jam. */
    private fun locked(): Boolean {
        val j = jam ?: return false
        if (!j.isListener()) return false
        j.explain()
        return true
    }

    /** Accepted song requests still waiting to play, so they play in the order they were accepted. */
    private val acceptedRequests = mutableListOf<String>()

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()?.also {
                it.addListener(listener)
                refresh()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    /** Current position in ms. Read it often (e.g. every 200 ms) for progress bars and lyrics. */
    fun positionMs(): Long = controller?.currentPosition ?: 0

    /** Plays [songs]. [source] (e.g. "playlist:<id>") lets the app remember where you left off in it. */
    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, source: String? = null) {
        if (locked()) return
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) songs.indices.random() else startIndex
        c.setMediaItems(songs.map { it.toMediaItem(api, source = source) }, start, 0)
        c.prepare()
        c.play()
    }

    /** Plays just the shared part of a song: starts at the clip's start and pauses at its end. Play again to hear the rest. */
    fun playClip(clip: SongRef) {
        if (locked()) return
        val c = controller ?: return
        val start = clip.clipStartMs ?: return play(listOf(clip.toSong()))
        c.shuffleModeEnabled = false
        c.setMediaItems(listOf(clip.toSong().toMediaItem(api, clipEndMs = clip.clipEndMs)), 0, start)
        c.prepare()
        c.play()
    }

    fun playNext(song: Song) {
        if (jam?.isListener() == true) return jam!!.request(song, playNow = false)
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(song))
        c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem())
    }

    fun addToQueue(song: Song) {
        if (jam?.isListener() == true) return jam!!.request(song, playNow = false)
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(song))
        c.addMediaItem(song.toMediaItem())
    }

    /**
     * The jam's owner accepted a song request: it plays after the current song and any requests
     * accepted before it, so requests play in the order they were accepted.
     */
    fun queueRequested(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(song))
        val current = c.currentMediaItemIndex
        // Forget requests that already played (they're at or behind the current song now).
        val upcoming = (current + 1 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        acceptedRequests.retainAll(upcoming.toSet())
        val lastWaiting = (current + 1 until c.mediaItemCount).lastOrNull { c.getMediaItemAt(it).mediaId in acceptedRequests }
        c.addMediaItem((lastWaiting ?: current) + 1, song.toMediaItem())
        acceptedRequests += song.id
    }

    /** The jam's owner accepted a "play it now" request: it goes right after the current song, and we skip to it. */
    fun playRequestedNow(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(song))
        val next = c.currentMediaItemIndex + 1
        c.addMediaItem(next, song.toMediaItem())
        c.seekTo(next, 0)
        c.play()
    }

    /** Takes the song at [index] (a [QueueEntry.index]) out of the queue, if it's still [songId]. */
    fun removeFromQueue(index: Int, songId: String) {
        if (locked()) return
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount && c.getMediaItemAt(index).mediaId == songId) c.removeMediaItem(index)
    }

    /** Moves the song at [from] to [to] (both player indices, as in [QueueEntry.index]). */
    fun moveInQueue(from: Int, to: Int) {
        if (locked()) return
        val c = controller ?: return
        if (from != to && from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun togglePlay() {
        if (locked()) return
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0)
            c.play()
        }
    }

    /** Stops playback and empties the queue (used when logging out). */
    fun stop() = controller?.run { stop(); clearMediaItems() } ?: Unit

    fun next() = if (locked()) Unit else controller?.seekToNext() ?: Unit
    fun previous() = if (locked()) Unit else controller?.seekToPrevious() ?: Unit
    fun seekTo(positionMs: Long) = if (locked()) Unit else controller?.seekTo(positionMs) ?: Unit
    fun jumpTo(index: Int) = if (locked()) Unit else controller?.run { seekTo(index, 0); play() } ?: Unit

    /** Moves to another song in the queue, keeping the current play/pause state (used by swiping). */
    fun skipTo(index: Int) = if (locked()) Unit else controller?.seekTo(index, 0) ?: Unit

    /** Always the previous song. The previous button instead restarts the current song if it's past 3 seconds. */
    fun previousSong() = if (locked()) Unit else controller?.seekToPreviousMediaItem() ?: Unit
    fun nextSong() = if (locked()) Unit else controller?.seekToNextMediaItem() ?: Unit

    fun toggleShuffle() {
        if (locked()) return
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** off -> repeat all -> repeat one -> off */
    fun cycleRepeat() {
        if (locked()) return
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val meta = item?.mediaMetadata
        _nowPlaying.value = NowPlaying(
            songId = item?.mediaId,
            title = meta?.title?.toString().orEmpty(),
            artist = meta?.artist?.toString().orEmpty(),
            album = meta?.albumTitle?.toString().orEmpty(),
            albumId = meta?.extras?.getString(EXTRA_ALBUM_ID),
            artworkUri = meta?.artworkUri,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            song = item?.toSongRef(),
            source = meta?.extras?.getString(EXTRA_SOURCE),
        )

        // Walk the timeline in play order, so the queue matches what will actually play next.
        val timeline = c.currentTimeline
        val entries = mutableListOf<QueueEntry>()
        var index = timeline.getFirstWindowIndex(c.shuffleModeEnabled)
        while (index != C.INDEX_UNSET) {
            entries += QueueEntry(index, c.getMediaItemAt(index))
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        }
        _queue.value = entries to c.currentMediaItemIndex
    }

    private fun Song.toMediaItem(): MediaItem = toMediaItem(api)
}
