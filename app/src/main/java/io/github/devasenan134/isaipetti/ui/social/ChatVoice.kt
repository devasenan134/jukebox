package io.github.devasenan134.isaipetti.ui.social

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.IsaipettiApp
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.ChatMessage
import io.github.devasenan134.isaipetti.data.clockTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Voice messages are at most this long; recording stops by itself there. */
const val MAX_VOICE_MS = 5 * 60_000

/**
 * Records one voice message into the app's cache: AAC in an .m4a file, mono, about 350 KB a
 * minute. [start] throws if the microphone can't be used.
 */
class VoiceRecorder(private val context: Context) {
    val file = File(File(context.cacheDir, "voice").apply { mkdirs() }, "recording.m4a")
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L

    /** How long it has been recording. */
    val elapsedMs get() = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    fun start(onMaxReached: () -> Unit) {
        file.delete()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(48_000)
            r.setMaxDuration(MAX_VOICE_MS)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxReached() }
            r.setOutputFile(file.path)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Stops and returns the recording and its length, or null if it failed or was too short to keep. */
    fun finish(): Pair<ByteArray, Long>? {
        val r = recorder ?: return null
        val length = elapsedMs.coerceAtMost(MAX_VOICE_MS.toLong())
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess // stop() throws if nothing was recorded yet
        r.release()
        if (!ok || length < 700) return null.also { file.delete() }
        return file.readBytes().also { file.delete() } to length
    }

    fun cancel() {
        recorder?.let { r -> runCatching { r.stop() }; r.release() }
        recorder = null
        file.delete()
    }
}

/**
 * Plays voice messages, one at a time. Recordings are downloaded once into the cache. While one
 * plays, your music pauses and carries on after (not in a jam: that would pause it for everyone).
 */
class VoicePlayer(private val context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext as IsaipettiApp
    private var player: MediaPlayer? = null
    private var resumeMusic = false

    /** The message playing (or paused), where it is, and whether it's playing. */
    var current by mutableStateOf<Long?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf<Long?>(null)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    fun toggle(message: ChatMessage) {
        val p = player
        if (current == message.id && p != null) {
            if (p.isPlaying) { p.pause(); playing = false; giveMusicBack() } else { pauseMusic(); p.start(); playing = true }
            return
        }
        stop()
        loading = message.id
        scope.launch {
            val file = File(File(context.cacheDir, "voice").apply { mkdirs() }, "${message.id}.m4a")
            try {
                if (!file.exists()) app.social.api.downloadVoice(message, file)
                if (loading != message.id) return@launch // another one was tapped meanwhile
                val mp = MediaPlayer().apply {
                    setDataSource(file.path)
                    prepare()
                    setOnCompletionListener { stop() }
                }
                pauseMusic()
                mp.start()
                player = mp
                current = message.id
                playing = true
                positionMs = 0
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't play it", Toast.LENGTH_SHORT).show()
            } finally {
                if (loading == message.id) loading = null
            }
        }
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.toInt())
        positionMs = ms
    }

    /** Called often while playing, to move the progress along. */
    fun tick() {
        player?.let { if (it.isPlaying) positionMs = it.currentPosition.toLong() }
    }

    fun stop() {
        player?.release()
        player = null
        current = null
        playing = false
        positionMs = 0
        giveMusicBack()
    }

    private fun pauseMusic() {
        val inJam = app.social.listen.joined.value != null
        if (!inJam && app.player.nowPlaying.value.isPlaying) {
            app.player.togglePlay()
            resumeMusic = true
        }
    }

    private fun giveMusicBack() {
        if (resumeMusic && !app.player.nowPlaying.value.isPlaying) app.player.togglePlay()
        resumeMusic = false
    }
}

/** One [VoicePlayer] for the chat screen, stopped when you leave it. */
@Composable
fun rememberVoicePlayer(scope: CoroutineScope): VoicePlayer {
    val context = LocalContext.current
    val player = remember { VoicePlayer(context, scope) }
    DisposableEffect(player) { onDispose { player.stop() } }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.tick()
            delay(200)
        }
    }
    return player
}

/** A voice message in a bubble: play/pause, how far along (drag to move), and how long. */
@Composable
fun VoiceMessage(message: ChatMessage, player: VoicePlayer) {
    val length = message.voiceMs ?: return
    val isCurrent = player.current == message.id
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(240.dp)) {
        if (player.loading == message.id) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = { player.toggle(message) }) {
                Icon(
                    painterResource(if (isCurrent && player.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (isCurrent && player.playing) "Pause" else "Play voice message",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Slider(
                value = if (isCurrent) (player.positionMs.toFloat() / length).coerceIn(0f, 1f) else 0f,
                onValueChange = { if (isCurrent) player.seekTo((it * length).toLong()) },
                enabled = isCurrent,
            )
            Text(
                if (isCurrent) "${clockTime(player.positionMs)} / ${clockTime(length)}" else "🎤 ${clockTime(length)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** While recording: a red dot and the time, ✕ to throw it away, ➤ to send it. */
@Composable
fun RecordingBar(recorder: VoiceRecorder, onCancel: () -> Unit, onSend: () -> Unit) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recorder) {
        while (true) {
            elapsed = recorder.elapsedMs
            delay(200)
        }
    }
    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Delete, contentDescription = "Throw away the recording")
        }
        Text(
            "●",
            color = if (elapsed / 500 % 2 == 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            "Recording  ${clockTime(elapsed)}" + if (elapsed > MAX_VOICE_MS - 30_000) "  (up to ${clockTime(MAX_VOICE_MS.toLong())})" else "",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSend) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send the voice message",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Says the microphone couldn't be used (and why, if Android said). */
fun micProblem(context: Context, e: Throwable?) =
    Toast.makeText(context, e?.message?.let { "Couldn't record: $it" } ?: "Couldn't use the microphone", Toast.LENGTH_SHORT).show()
