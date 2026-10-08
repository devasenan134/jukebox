package io.github.devasenan134.isaipetti.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

    fun isDownloaded(songId: String): Boolean {
        val f = getOfflineFile(songId)
        return f != null && f.isFile && f.length() > 0
    }

    fun getOfflineFile(songId: String): File? {
        val file = File(downloadDir, "$songId.audio")
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    fun downloadedSongIds(): Set<String> {
        return downloadDir.listFiles()?.mapNotNull { f ->
            if (f.isFile && f.name.endsWith(".audio") && f.length() > 0) {
                f.name.removeSuffix(".audio")
            } else null
        }?.toSet() ?: emptySet()
    }

    suspend fun downloadSong(songId: String, streamUrl: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(downloadDir, "$songId.audio")
        if (target.isFile && target.length() > 0) return@withContext true
        val temp = File(downloadDir, "$songId.audio.tmp")
        if (temp.exists()) temp.delete()

        try {
            val req = Request.Builder().url(streamUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                FileOutputStream(temp).use { out ->
                    body.byteStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            if (temp.isFile && temp.length() > 0) {
                temp.renameTo(target)
            } else {
                temp.delete()
                false
            }
        } catch (e: Exception) {
            temp.delete()
            false
        }
    }

    fun removeSong(songId: String): Boolean {
        val file = File(downloadDir, "$songId.audio")
        return if (file.exists()) file.delete() else true
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
