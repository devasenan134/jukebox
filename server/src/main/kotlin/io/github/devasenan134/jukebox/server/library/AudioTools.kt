package io.github.devasenan134.jukebox.server.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** What ffprobe says about an audio file. Tag names are lower case ("title", "album_artist", "discsubtitle"). */
data class Probed(
    val format: String,
    val codec: String?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val channels: Int?,
    val durationMs: Long,
    val tags: Map<String, String>,
    val hasPicture: Boolean,
) {
    fun tag(vararg names: String): String? = names.firstNotNullOfOrNull { tags[it]?.trim()?.takeIf(String::isNotEmpty) }
}

/**
 * Reads audio files with ffprobe and ffmpeg (in the Docker image; any format they know works).
 * Every call has a time limit, so one broken file can't hold up a scan.
 */
open class AudioTools(
    private val ffprobe: String = "ffprobe",
    private val ffmpeg: String = "ffmpeg",
    /** Run at low CPU priority (scans shouldn't slow down anything people are listening to). */
    private val lowPriority: Boolean = false,
) {
    private val json = Json { ignoreUnknownKeys = true }

    open fun probe(file: File): Probed? {
        val out = run(listOf(ffprobe, "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", file.path)) ?: return null
        val root = runCatching { json.parseToJsonElement(String(out)).jsonObject }.getOrNull() ?: return null
        val format = root["format"]?.jsonObject ?: return null
        val streams = root["streams"]?.jsonArray.orEmpty().map { it.jsonObject }
        val audio = streams.firstOrNull { it.str("codec_type") == "audio" } ?: return null
        val tags = buildMap {
            fun add(o: JsonObject?) = o?.forEach { (k, v) -> putIfAbsent(k.lowercase(), v.jsonPrimitive.contentOrNull ?: "") }
            add(format["tags"]?.jsonObject)
            add(audio["tags"]?.jsonObject)
        }
        val seconds = (format.str("duration") ?: audio.str("duration"))?.toDoubleOrNull() ?: return null
        return Probed(
            format = file.extension.lowercase(),
            codec = audio.str("codec_name"),
            bitrate = (audio.str("bit_rate") ?: format.str("bit_rate"))?.toIntOrNull(),
            sampleRate = audio.str("sample_rate")?.toIntOrNull(),
            channels = audio["channels"]?.jsonPrimitive?.intOrNull,
            durationMs = (seconds * 1000).toLong(),
            tags = tags,
            hasPicture = streams.any { it["disposition"]?.jsonObject?.get("attached_pic")?.jsonPrimitive?.intOrNull == 1 },
        )
    }

    /** MD5 of the audio data only (tags left out): the same after retagging or renaming, different after re-encoding. */
    open fun audioMd5(file: File): String? =
        run(listOf(ffmpeg, "-v", "error", "-nostdin", "-i", file.path, "-map", "0:a:0", "-c", "copy", "-f", "md5", "-"))
            ?.let { String(it).trim().removePrefix("MD5=").takeIf { s -> s.length == 32 } }

    /** Chromaprint fingerprint of the first two minutes: close for the same recording, even re-encoded. */
    open fun fingerprint(file: File): IntArray? {
        val raw = run(listOf(ffmpeg, "-v", "error", "-nostdin", "-i", file.path, "-map", "0:a:0", "-t", "120",
            "-f", "chromaprint", "-fp_format", "raw", "-"), timeoutSeconds = 120) ?: return null
        if (raw.size < 4) return null
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return IntArray(buffer.remaining()).also(buffer::get)
    }

    /** The picture embedded in the file, as JPEG or PNG bytes. */
    open fun embeddedPicture(file: File): ByteArray? =
        run(listOf(ffmpeg, "-v", "error", "-nostdin", "-i", file.path, "-an", "-map", "0:v:0", "-c", "copy", "-f", "image2pipe", "-"))
            ?.takeIf { it.size > 100 }

    /** [image] scaled to fit [size] × [size] as JPEG. */
    open fun resize(image: File, size: Int): ByteArray? =
        run(listOf(ffmpeg, "-v", "error", "-nostdin", "-i", image.path, "-vf",
            "scale='min($size,iw)':'min($size,ih)':force_original_aspect_ratio=decrease", "-q:v", "3", "-f", "image2pipe", "-c:v", "mjpeg", "-"))

    /** Transcodes [input] to Opus/Ogg format at [bitrateKbps] into [output]. Returns true on success. */
    open fun transcodeToOpus(input: File, output: File, bitrateKbps: Int = 128): Boolean {
        if (output.isFile && output.length() > 0) return true
        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, "${output.name}.tmp")
        if (temp.exists()) temp.delete()
        val ok = run(
            listOf(
                ffmpeg, "-v", "error", "-nostdin", "-y", "-i", input.path,
                "-c:a", "libopus", "-b:a", "${bitrateKbps}k", "-vbr", "on",
                "-vn", "-f", "opus", temp.path
            ),
            timeoutSeconds = 180
        ) != null
        if (ok && temp.isFile && temp.length() > 0) {
            return temp.renameTo(output)
        }
        temp.delete()
        return false
    }

    private fun run(command: List<String>, timeoutSeconds: Long = 60): ByteArray? {
        val command = if (lowPriority && File("/usr/bin/nice").exists()) listOf("/usr/bin/nice", "-n", "15") + command else command
        val process = runCatching { ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start() }.getOrNull() ?: return null
        val out = process.inputStream.use { it.readBytes() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return out.takeIf { process.exitValue() == 0 }
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        /** How alike two fingerprints are, 0 to 1: the share of matching bits over the shorter one, best of a few offsets. */
        fun similarity(a: IntArray, b: IntArray): Double {
            var best = 0.0
            for (shift in -MAX_SHIFT..MAX_SHIFT) {
                var bits = 0L
                var n = 0
                for (i in a.indices) {
                    val j = i + shift
                    if (j < 0 || j >= b.size) continue
                    bits += Integer.bitCount(a[i] xor b[j])
                    n++
                }
                if (n >= MIN_OVERLAP) best = maxOf(best, 1.0 - bits.toDouble() / (32L * n))
            }
            return best
        }

        private const val MAX_SHIFT = 8
        private const val MIN_OVERLAP = 30
    }
}
