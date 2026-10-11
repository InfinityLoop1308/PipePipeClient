package org.schabi.newpipe.util.dearrow

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.schabi.newpipe.extractor.NewPipe
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place DeArrow keeps decoded images, and the only way one reaches a view.
 *
 * Three sources go through here: a community frame from the DeArrow thumbnail server, one of
 * YouTube's three automatically-extracted frames (`i.ytimg.com/vi/<id>/hq1..hq3.jpg`), and a frame
 * the DeArrow server renders on demand for a broadcast or a brand-new upload. Nothing is handed to
 * an image loader directly, because the DeArrow thumbnail server answers **HTTP 204 with an empty
 * body** for frames it does not already hold, and a 204 makes an image loader paint its
 * placeholder over a perfectly good thumbnail.
 *
 * Frames are cached by URL and, for a row, scaled down before they are stored. The DeArrow server
 * serves 1280x720 frames; kept at full size they are ~3.7 MB each, so a handful would evict the
 * whole cache and every scroll back would re-fetch. A rendered frame gets a TTL instead of living
 * for the session, because its URL never changes and a broadcast's picture should not freeze.
 */
object DeArrowImages {
    private const val TAG = "DeArrowImages"

    /** The host serving YouTube's stored frames. No key, no auth. */
    private const val THUMBNAIL_HOST = "https://i.ytimg.com/vi/"

    /** How many automatically-extracted frames YouTube stores per upload: hq1, hq2, hq3. */
    private const val AUTO_FRAME_COUNT = 3

    /** Smaller than a real image could be; guards against a truncated or error body. */
    private const val MIN_IMAGE_BYTES = 512

    /** A row is near-black below this mean luminance on a 0-255 scale. */
    private const val BLACK_LUMA = 16

    /** At most this fraction of the height may be cropped off each edge. */
    private const val MAX_CROP_FRACTION = 0.2f

    /** How long a failure is remembered, so a scroll storm does not re-ask for a missing image. */
    private const val MISS_TTL_MS = 10 * 60 * 1000L
    private const val MAX_REMEMBERED_MISSES = 512

    /** How long a server-rendered frame is reused before it is fetched again. */
    private const val RENDERED_TTL_MS = 10 * 60 * 1000L

    /** Decoded images target this fraction of the heap. */
    private const val HEAP_FRACTION = 8

    /** The fraction is clamped to a range a thumbnail cache can justify on this device. */
    private const val MIN_CACHE_BYTES = 16L * 1024 * 1024
    private const val MAX_CACHE_BYTES = 48L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val misses = LruCache<String, Long>(MAX_REMEMBERED_MISSES)

    private val cache = object : LruCache<String, Entry>(budgetBytes()) {
        override fun sizeOf(key: String, value: Entry): Int = value.bitmap.byteCount
    }

    /**
     * A decoded frame. An [expiresAt] of 0 means it never expires; a rendered frame carries a
     * deadline because its URL never changes and a broadcast's picture must not be frozen for the
     * whole session.
     */
    private class Entry(val bitmap: Bitmap, val expiresAt: Long)

    /**
     * One of YouTube's stored frames, chosen stably from the video id so a row does not change
     * picture when it scrolls away and back.
     */
    fun storedFrameUrl(videoId: String): String {
        val index = Math.floorMod(videoId.hashCode(), AUTO_FRAME_COUNT) + 1
        return String.format(Locale.US, "%s%s/hq%d.jpg", THUMBNAIL_HOST, videoId, index)
    }

    /** A community-submitted frame, if the server actually holds one. */
    suspend fun loadCommunityImage(url: String, targetWidth: Int): Bitmap? =
        load(url, "$url#$targetWidth") { scaleToWidth(it, targetWidth) }

    /** A stored frame, de-letterboxed, or null if the video has none. */
    suspend fun loadStoredFrame(videoId: String): Bitmap? {
        val url = storedFrameUrl(videoId)
        return load(url, url) { stripLetterbox(it) }
    }

    /**
     * A frame the server renders on demand, for a live broadcast or an upload too fresh to have a
     * stored frame. Returns null when it answers HTTP 204, which happens for broadcasts it cannot
     * render.
     */
    suspend fun loadRenderedFrame(videoId: String, targetWidth: Int): Bitmap? =
        load(DeArrowApi.renderedThumbnailUrl(videoId), "$videoId#$targetWidth", RENDERED_TTL_MS) {
            scaleToWidth(it, targetWidth)
        }

    /** Forget everything, including remembered failures. */
    fun clear() {
        cache.evictAll()
        misses.evictAll()
    }

    /** Hands memory back when the system asks for it. */
    @JvmStatic
    fun registerTrimCallback(context: Context) {
        context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) clear()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                clear()
            }
        })
    }

    private suspend fun load(
        fetchUrl: String,
        cacheKey: String,
        ttlMs: Long = 0,
        transform: (Bitmap) -> Bitmap,
    ): Bitmap? {
        cache.get(cacheKey)?.let { entry ->
            if (entry.expiresAt == 0L || System.currentTimeMillis() < entry.expiresAt) {
                return entry.bitmap
            }
            cache.remove(cacheKey)
        }
        if (isMiss(fetchUrl)) return null
        // Rows showing the same image share one request; the entry is dropped once it settles so
        // a later bind can retry after a transient failure.
        val deferred = inFlight.computeIfAbsent(cacheKey) {
            scope.async {
                try {
                    val image = fetch(fetchUrl)?.let(transform)
                    if (image != null) {
                        val expiresAt = if (ttlMs > 0) System.currentTimeMillis() + ttlMs else 0L
                        cache.put(cacheKey, Entry(image, expiresAt))
                    } else {
                        recordMiss(fetchUrl)
                    }
                    image
                } finally {
                    inFlight.remove(cacheKey)
                }
            }
        }
        return deferred.await()
    }

    private fun fetch(url: String): Bitmap? {
        return try {
            val response = NewPipe.getDownloader().get(url)
            if (response.responseCode() != 200) return null
            val body = response.rawResponseBody()
            if (body == null || body.size < MIN_IMAGE_BYTES) return null
            BitmapFactory.decodeByteArray(body, 0, body.size)
        } catch (e: Exception) {
            Log.d(TAG, "no image at $url", e)
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun isMiss(url: String): Boolean {
        val at = misses.get(url) ?: return false
        if (System.currentTimeMillis() - at > MISS_TTL_MS) {
            misses.remove(url)
            return false
        }
        return true
    }

    private fun recordMiss(url: String) {
        misses.put(url, System.currentTimeMillis())
    }

    private fun budgetBytes(): Int {
        val budget = Runtime.getRuntime().maxMemory() / HEAP_FRACTION
        return budget.coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES).toInt()
    }

    /** Shrinks a frame to something a list row or detail header can actually show. */
    private fun scaleToWidth(source: Bitmap, targetWidth: Int): Bitmap {
        if (targetWidth <= 0 || source.width <= targetWidth) return source
        val height = (source.height.toLong() * targetWidth / source.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    /**
     * Removes the black bars YouTube pads stored frames with.
     *
     * They are served in a 4:3 box, so a widescreen video arrives with a band above and below.
     * The bars are detected rather than assumed, because a genuinely 4:3 upload has none.
     */
    private fun stripLetterbox(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val limit = (height * MAX_CROP_FRACTION).toInt()

        var top = 0
        while (top < limit && isRowBlack(source, top, width)) top++
        var bottom = height - 1
        while (height - 1 - bottom < limit && isRowBlack(source, bottom, width)) bottom--
        val cropped = bottom - top + 1
        if (top == 0 && cropped == height) return source
        // Dark at BOTH edges is a night scene or a fade, not a letterboxed frame.
        if (top >= limit && height - 1 - bottom >= limit) return source

        val result = Bitmap.createBitmap(source, 0, top, width, cropped)
        source.recycle()
        return result
    }

    private fun isRowBlack(bitmap: Bitmap, y: Int, width: Int): Boolean {
        val step = (width / 60).coerceAtLeast(1)
        var total = 0L
        var samples = 0
        var x = 0
        while (x < width) {
            val pixel = bitmap.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Rec. 601 luma, integer-scaled to avoid a float per pixel.
            total += (299L * r + 587L * g + 114L * b) / 1000L
            samples++
            x += step
        }
        return samples > 0 && total / samples < BLACK_LUMA
    }
}
