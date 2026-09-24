package io.github.devasenan134.isaipetti.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The loudness shape of a song (its waveform), for picking a part of it to share: [BARS] numbers
 * from 0 to 1. The phone works it out by decoding the whole song once (a few seconds), then keeps
 * it in the cache folder so it shows at once next time.
 */
class Waveforms(context: Context, private val api: SubsonicApi) {
    private val dir = File(context.cacheDir, "waveforms")
    private val memory = LruCache<String, FloatArray>(30)
    // One song at a time: decoding is heavy, and two sheets never need two songs at once.
    private val lock = Mutex()

    suspend fun get(songId: String, durationMs: Long): FloatArray = withContext(Dispatchers.IO) {
        memory.get(songId) ?: lock.withLock {
            memory.get(songId) ?: (read(songId) ?: decode(api.streamUrl(songId), durationMs).also { write(songId, it) })
                .also { memory.put(songId, it) }
        }
    }

    private fun file(songId: String) = File(dir, songId.filter { it.isLetterOrDigit() || it == '-' || it == '_' })

    private fun read(songId: String): FloatArray? = runCatching {
        val bytes = file(songId).takeIf { it.exists() }?.readBytes() ?: return null
        val floats = ByteBuffer.wrap(bytes).asFloatBuffer()
        FloatArray(floats.remaining()).also { floats.get(it) }.takeIf { it.size == BARS }
    }.getOrNull()

    private fun write(songId: String, bars: FloatArray) {
        runCatching {
            dir.mkdirs()
            val buffer = ByteBuffer.allocate(bars.size * 4)
            buffer.asFloatBuffer().put(bars)
            file(songId).writeBytes(buffer.array())
        }
    }

    /** Streams the song through the phone's audio decoder and measures how loud each slice is. */
    private suspend fun decode(url: String, durationMs: Long): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(url)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L)
                .takeIf { it > 0 } ?: (durationMs * 1000).coerceAtLeast(1)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, null, null, 0)
                start()
            }

            val squares = DoubleArray(BARS)
            val counts = IntArray(BARS)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = codec.outputFormat
                    floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    if (info.size > 0) {
                        // Each decoded chunk is ~25 ms, so it all belongs to one bar.
                        val bar = (info.presentationTimeUs * BARS / durationUs).toInt().coerceIn(0, BARS - 1)
                        val bytes = codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
                            .slice().order(ByteOrder.nativeOrder())
                        // Every 8th sample is plenty to tell loud from quiet.
                        if (floatPcm) {
                            val samples = bytes.asFloatBuffer()
                            for (k in 0 until samples.limit() step SKIP) {
                                val s = samples.get(k).toDouble(); squares[bar] += s * s; counts[bar]++
                            }
                        } else {
                            val samples = bytes.asShortBuffer()
                            for (k in 0 until samples.limit() step SKIP) {
                                val s = samples.get(k) / 32768.0; squares[bar] += s * s; counts[bar]++
                            }
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return shape(DoubleArray(BARS) { if (counts[it] > 0) sqrt(squares[it] / counts[it]) else 0.0 })
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    companion object {
        const val BARS = 80
        private const val SKIP = 8

        /**
         * Film songs are mixed loud almost all the way through, so plain loudness looks like a flat
         * block. Stretch the range from the quiet parts to the loudest so the shape stands out.
         */
        internal fun shape(rms: DoubleArray): FloatArray {
            val heard = rms.filter { it > 0 }.sorted()
            if (heard.isEmpty()) return FloatArray(rms.size) { MIN_BAR }
            val floor = heard[heard.size / 20] * 0.8
            val top = heard.last().coerceAtLeast(floor + 1e-9)
            return FloatArray(rms.size) {
                (MIN_BAR + (1 - MIN_BAR) * ((rms[it] - floor) / (top - floor)).coerceIn(0.0, 1.0)).toFloat()
            }
        }

        const val MIN_BAR = 0.1f
    }
}
