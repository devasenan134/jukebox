package io.github.devasenan134.isaipetti.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Offline download manager for saving songs locally for offline playback.
 */
class OfflineDownloads private constructor(private val context: Context) {

    private val client = OkHttpClient.Builder().build()

    val downloadDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").apply { mkdirs() }

    private val _downloadedIds = MutableStateFlow<Set<String>>(readDownloadedSongIds())
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds

    private fun readDownloadedSongIds(): Set<String> {
        return downloadDir.listFiles()?.mapNotNull { f ->
            if (f.isFile && f.name.endsWith(".audio") && f.length() > 0) {
                f.name.removeSuffix(".audio")
            } else null
        }?.toSet() ?: emptySet()
    }

    fun isDownloaded(songId: String): Boolean {
        return _downloadedIds.value.contains(songId)
    }

    fun isDownloading(songId: String): Boolean {
        return _downloadingIds.value.contains(songId)
    }

    fun getOfflineFile(songId: String): File? {
        val file = File(downloadDir, "$songId.audio")
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    fun downloadedSongIds(): Set<String> = _downloadedIds.value

    fun totalBytesUsed(): Long {
        return downloadDir.listFiles()?.filter { it.isFile && it.name.endsWith(".audio") }?.sumOf { it.length() } ?: 0L
    }

    fun clearAll(): Boolean {
        val files = downloadDir.listFiles()?.filter { it.isFile && it.name.endsWith(".audio") } ?: emptyList()
        var allOk = true
        for (f in files) {
            if (!f.delete()) allOk = false
        }
        _downloadedIds.value = emptySet()
        return allOk
    }

    suspend fun downloadSong(
        songId: String,
        api: SubsonicApi,
        quality: DownloadQuality = DownloadQuality.Auto,
    ): Boolean {
        val url = api.downloadUrl(songId, quality)
        return downloadSong(songId, url)
    }

    suspend fun downloadSong(songId: String, streamUrl: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(downloadDir, "$songId.audio")
        if (target.isFile && target.length() > 0) {
            _downloadedIds.value = _downloadedIds.value + songId
            return@withContext true
        }
        val temp = File(downloadDir, "$songId.audio.tmp")
        if (temp.exists()) temp.delete()

        _downloadingIds.value = _downloadingIds.value + songId
        try {
            val req = Request.Builder().url(streamUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body
                FileOutputStream(temp).use { out ->
                    body.byteStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            if (temp.isFile && temp.length() > 0 && temp.renameTo(target)) {
                _downloadedIds.value = _downloadedIds.value + songId
                true
            } else {
                temp.delete()
                false
            }
        } catch (e: Exception) {
            temp.delete()
            false
        } finally {
            _downloadingIds.value = _downloadingIds.value - songId
        }
    }

    suspend fun downloadSongs(
        songs: List<Song>,
        api: SubsonicApi,
        quality: DownloadQuality = DownloadQuality.Auto,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        var completed = 0
        val total = songs.size
        for ((index, song) in songs.withIndex()) {
            onProgress?.invoke(index, total)
            if (isDownloaded(song.id)) {
                completed++
                continue
            }
            if (downloadSong(song.id, api, quality)) {
                completed++
            }
            onProgress?.invoke(index + 1, total)
        }
        completed
    }

    fun removeSong(songId: String): Boolean {
        val file = File(downloadDir, "$songId.audio")
        val deleted = if (file.exists()) file.delete() else true
        if (deleted) {
            _downloadedIds.value = _downloadedIds.value - songId
        }
        return deleted
    }

    companion object {
        @Volatile
        private var instance: OfflineDownloads? = null

        fun getInstance(context: Context): OfflineDownloads =
            instance ?: synchronized(this) {
                instance ?: OfflineDownloads(context.applicationContext).also { instance = it }
            }
    }
}
