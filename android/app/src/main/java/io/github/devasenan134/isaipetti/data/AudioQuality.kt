package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Streaming quality choices for online playback. */
enum class StreamingQuality(val label: String, val description: String) {
    Auto("Auto", "Balances speed and quality automatically"),
    High("High (Original)", "Plays original uncompressed library audio files"),
    DataSaver("Data Saver (Opus)", "Saves mobile data and battery using Opus 128 kbps"),
}

/** Offline downloading quality choices for local storage. */
enum class DownloadQuality(val label: String, val description: String) {
    Auto("Auto", "Downloads Opus copy if available on server, else original"),
    High("High (Original)", "Downloads full original source audio files"),
    DataSaver("Data Saver (Opus)", "Downloads compact Opus 128 kbps copies (~1 MB / min)"),
}

/** Audio quality settings for streaming and offline downloads, saved on the phone. */
class AudioQualitySettings(context: Context) {
    private val prefs = context.getSharedPreferences("audio_quality", Context.MODE_PRIVATE)

    private val _streamingQuality = MutableStateFlow(
        prefs.getString(KEY_STREAMING, null)?.let { runCatching { StreamingQuality.valueOf(it) }.getOrNull() }
            ?: StreamingQuality.Auto
    )
    val streamingQuality: StateFlow<StreamingQuality> = _streamingQuality

    private val _downloadQuality = MutableStateFlow(
        prefs.getString(KEY_DOWNLOAD, null)?.let { runCatching { DownloadQuality.valueOf(it) }.getOrNull() }
            ?: DownloadQuality.Auto
    )
    val downloadQuality: StateFlow<DownloadQuality> = _downloadQuality

    fun setStreamingQuality(quality: StreamingQuality) {
        _streamingQuality.value = quality
        prefs.edit { putString(KEY_STREAMING, quality.name) }
    }

    fun setDownloadQuality(quality: DownloadQuality) {
        _downloadQuality.value = quality
        prefs.edit { putString(KEY_DOWNLOAD, quality.name) }
    }

    companion object {
        private const val KEY_STREAMING = "streaming_quality"
        private const val KEY_DOWNLOAD = "download_quality"
    }
}
