package io.github.devasenan134.isaipetti.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.devasenan134.isaipetti.data.Eq
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Plays the equalizer (Settings › Sound): a preamp and a peaking filter per band, on the decoded audio before it
 * reaches the speakers. The filters are the website's (Web Audio's BiquadFilterNode), so a profile sounds the same.
 *
 * ExoPlayer hands its audio processors 16-bit PCM (DefaultAudioSink converts everything else first, as float
 * output is off), so that's what this takes. It always stays in the chain; switched off it just copies the audio,
 * so turning it on and off works mid-song.
 */
@UnstableApi
class EqualizerProcessor : BaseAudioProcessor() {
    /** The gains to play (dB per band), or null when off. Set from the main thread, read on the playback thread. */
    @Volatile private var wanted: List<Float>? = null

    /** The gains the filters below were worked out for, at this format. */
    private var applied: List<Float>? = null
    /** The bands that do something (not flat, and below the format's highest frequency). */
    private var active: List<Int> = emptyList()
    /** Their normalised coefficients: b0, b1, b2, a1, a2 each. */
    private var coefficients: Array<DoubleArray> = emptyArray()
    /** Filter memory (transposed direct form II): per band, per channel, two values. */
    private var memory = DoubleArray(0)
    private var preamp = 1.0

    /** Plays these gains (dB per band), or nothing (null: off). */
    fun set(gains: List<Float>?) {
        wanted = gains?.takeIf { g -> g.any { it != 0f } }
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val gains = wanted
        if (gains == null) {
            if (applied != null) prepare(null)
            output.put(inputBuffer).flip()
            return
        }
        if (gains !== applied) prepare(gains)

        val channels = inputAudioFormat.channelCount
        val bands = coefficients
        val mem = memory
        while (inputBuffer.remaining() >= 2 * channels) {
            for (ch in 0 until channels) {
                var x = inputBuffer.short / 32768.0 * preamp
                for (b in bands.indices) {
                    val k = bands[b]
                    val m = 2 * (b * channels + ch)
                    val y = k[0] * x + mem[m]
                    mem[m] = k[1] * x - k[3] * y + mem[m + 1]
                    mem[m + 1] = k[2] * x - k[4] * y
                    x = y
                }
                output.putShort((x * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    /** Works out the filters for [gains] at the current sample rate. Off (null) clears their memory too. */
    private fun prepare(gains: List<Float>?) {
        applied = gains
        if (gains == null) {
            active = emptyList()
            coefficients = emptyArray()
            memory = DoubleArray(0)
            return
        }
        val fs = inputAudioFormat.sampleRate.toDouble()
        val bands = gains.indices.filter { gains[it] != 0f && Eq.BANDS[it] < fs * 0.45 }
        coefficients = bands
            .map { i ->
                val k = Eq.peaking(Eq.BANDS[i].toDouble(), gains[i].toDouble(), fs)
                doubleArrayOf(k[0] / k[3], k[1] / k[3], k[2] / k[3], k[4] / k[3], k[5] / k[3])
            }
            .toTypedArray()
        preamp = 10.0.pow(Eq.preampDb(gains) / 20.0)
        // Keep the filters' memory while a band is dragged; start afresh when the bands in use change.
        val size = 2 * bands.size * inputAudioFormat.channelCount
        if (bands != active || memory.size != size) memory = DoubleArray(size)
        active = bands
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // A seek or a new format: the old audio's filter memory and the old sample rate's filters don't apply.
        applied = null
        active = emptyList()
        memory = DoubleArray(0)
    }

    override fun onReset() {
        applied = null
        active = emptyList()
        coefficients = emptyArray()
        memory = DoubleArray(0)
    }
}
