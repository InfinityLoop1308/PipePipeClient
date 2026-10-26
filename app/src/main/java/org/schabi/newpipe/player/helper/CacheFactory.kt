package org.schabi.newpipe.player.helper

import android.content.Context
import android.util.Log
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.FileDataSource
import com.google.android.exoplayer2.upstream.TransferListener
import com.google.android.exoplayer2.upstream.cache.CacheDataSink
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.CacheKeyFactory
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import java.io.File
import java.io.IOException

/**
 * Disk-cached [DataSource.Factory] around a fixed [upstream].
 *
 * The upstream is fully configured by the caller (user agent, headers); this class never
 * modifies it, so one playback's request headers cannot leak into another.
 */
internal class CacheFactory(
    private val context: Context,
    private val transferListener: TransferListener,
    private val upstream: DataSource.Factory,
    private val cacheKeyFactory: CacheKeyFactory? = null,
) : DataSource.Factory {

    private val cache: SimpleCache = obtainCache(context)
    private val maxFileSize: Long = PlayerHelper.getPreferredFileSize()

    override fun createDataSource(): DataSource {
        val upstreamSource = DefaultDataSource.Factory(context, upstream)
            .setTransferListener(transferListener)
            .createDataSource()
        return CacheDataSource(
            cache,
            upstreamSource,
            FileDataSource(),
            CacheDataSink(cache, maxFileSize),
            CACHE_FLAGS,
            null,
            cacheKeyFactory,
        )
    }

    companion object {
        private const val TAG = "CacheFactory"
        private const val CACHE_FOLDER_NAME = "exoplayer"
        private const val CACHE_FLAGS = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR

        private var sharedCache: SimpleCache? = null

        @Synchronized
        private fun obtainCache(context: Context): SimpleCache {
            sharedCache?.let { return it }
            val cacheDir = File(context.externalCacheDir, CACHE_FOLDER_NAME)
            if (!cacheDir.exists()) {
                cacheDir.mkdir()
            }
            val evictor = LeastRecentlyUsedCacheEvictor(PlayerHelper.getPreferredCacheSize())
            val created = try {
                SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(context)).also {
                    Log.d(TAG, "initExoPlayerCache: cacheDir = ${cacheDir.absolutePath}")
                }
            } catch (e: Exception) {
                clearCacheFolderLock(cacheDir)
                SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(context))
            }
            sharedCache = created
            return created
        }

        private fun clearCacheFolderLock(cacheDir: File) {
            try {
                val method = SimpleCache::class.java
                    .getDeclaredMethod("unlockFolder", File::class.java)
                method.isAccessible = true
                method.invoke(null, cacheDir)
            } catch (e: Exception) {
                Log.w(TAG, "Could not unlock cache folder", e)
            }
        }

        /** Clears media entries without destroying the process; used by cold-cache benchmarks. */
        @JvmStatic
        @Synchronized
        @Throws(IOException::class)
        fun clearMediaCache() {
            val cache = sharedCache ?: return
            for (key in HashSet(cache.keys)) {
                cache.removeResource(key)
            }
        }
    }
}
