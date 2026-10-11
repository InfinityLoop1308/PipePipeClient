package org.schabi.newpipe.util.dearrow

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.schabi.newpipe.extractor.NewPipe
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches and caches DeArrow branding, one API bucket at a time.
 *
 * Lookups are keyed by video id but fetched by hash bucket, which is what makes one request
 * answer ~130 videos. Requests for a bucket already in flight share its result.
 *
 * The cache is in-memory only: community submissions are edited and removed, and a persisted
 * cache would keep showing a title the server has since taken down.
 */
object DeArrowRepository {
    private const val TAG = "DeArrowRepository"

    /** One bucket is ~130 videos; this holds roughly the last 40 buckets. */
    private const val MAX_CACHED_VIDEOS = 5000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<Map<String, DeArrowBranding>>>()

    private val cache: MutableMap<String, DeArrowBranding> = Collections.synchronizedMap(
        object : LinkedHashMap<String, DeArrowBranding>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, DeArrowBranding>,
            ): Boolean = size > MAX_CACHED_VIDEOS
        }
    )

    /** An already-cached result, or null. Lets view binding apply a known title synchronously. */
    fun cached(videoId: String): DeArrowBranding? = cache[videoId]

    /**
     * The branding for a video, never null. A disabled feature, an unreachable API, a malformed
     * response and a video DeArrow has never heard of all resolve to [DeArrowBranding.NONE].
     */
    suspend fun load(videoId: String): DeArrowBranding {
        cache[videoId]?.let { return it }
        val prefix = DeArrowApi.hashPrefix(videoId)
        val bucket = inFlight.computeIfAbsent(prefix) {
            scope.async {
                try {
                    fetchBucket(videoId)
                } finally {
                    inFlight.remove(prefix)
                }
            }
        }.await()
        return bucket[videoId] ?: DeArrowBranding.NONE
    }

    private fun fetchBucket(videoId: String): Map<String, DeArrowBranding> {
        return try {
            val response = NewPipe.getDownloader().get(DeArrowApi.bucketUrl(videoId))
            if (response.responseCode() != 200) return emptyMap()
            val body = response.responseBody() ?: return emptyMap()
            val parsed = DeArrowApi.parseBucket(body)
            cache.putAll(parsed)
            // Remember "no submissions" for the requested video too, so scrolling past it again
            // does not re-request the bucket.
            cache.getOrPut(videoId) { DeArrowBranding.NONE }
            parsed
        } catch (e: Exception) {
            Log.d(TAG, "DeArrow lookup failed", e)
            emptyMap()
        }
    }

    /** Forgets every cached branding. Backs the settings screen's clear-cache action. */
    fun clear() {
        cache.clear()
    }
}
