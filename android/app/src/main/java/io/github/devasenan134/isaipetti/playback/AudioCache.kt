package io.github.devasenan134.isaipetti.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Media3 SimpleCache singleton for caching streamed and pre-buffered audio.
 * Provides instant scrubbing, pause/resume, and gapless replay without hitting the network.
 */
@OptIn(UnstableApi::class)
object AudioCache {
    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache =
        instance ?: synchronized(this) {
            instance ?: run {
                val cacheDir = File(context.cacheDir, "audio_cache")
                val evictor = LeastRecentlyUsedCacheEvictor(250L * 1024 * 1024) // 250 MB LRU limit
                val dbProvider = StandaloneDatabaseProvider(context)
                SimpleCache(cacheDir, evictor, dbProvider).also { instance = it }
            }
        }
}
