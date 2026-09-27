package org.schabi.newpipe.util.dearrow;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The one place DeArrow keeps decoded frames.
 *
 * <p><b>Bounded in bytes, not in entries.</b> {@link LruCache} counts entries unless
 * {@code sizeOf} is overridden, which is an easy thing to get wrong and an expensive thing
 * to get wrong: three caches of 120, 60 and 40 entries sound modest and are roughly 62 MB,
 * 31 MB and 37 MB of {@code ARGB_8888} bitmaps. That is enough to have the app killed on a
 * 2 GB device after scrolling a long feed, from a feature whose entire job is cosmetic.</p>
 *
 * <p>It also remembers <b>failures</b>. Without that, a video whose frame 404s is retried on
 * every single rebind — scroll a feed down and back up five times and it has issued five
 * requests for an image that was never going to arrive. A miss is cheap to remember and
 * expires on its own, so a transient network failure is not permanent.</p>
 *
 * <p>And it can be <b>emptied</b>. The settings screen offers to forget every downloaded
 * title and thumbnail; before this existed that button cleared the title map only, so the
 * stale thumbnail a user was trying to get rid of stayed exactly where it was.</p>
 */
public final class DeArrowFrameCache {

    /**
     * How much of the heap decoded frames may occupy.
     *
     * <p>An eighth is the conventional share for a secondary image cache — Picasso already
     * takes about 15% for the thumbnails these sit beside, and this must not compete with
     * it. On a typical 256 MB heap that is ~32 MB, or roughly 60 list-sized frames.</p>
     */
    @VisibleForTesting
    static final int HEAP_FRACTION = 8;

    /** How long a failure is remembered. Long enough to stop a scroll storm, short enough
     * that a video which was merely unreachable gets another chance in the same session. */
    @VisibleForTesting
    static final long MISS_TTL_MS = 10 * 60 * 1000L;

    private static final Map<DeArrowFrameCache, Boolean> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * How many failures to remember. Counted in entries rather than bytes on purpose —
     * these are a video id and a timestamp, so a few hundred is kilobytes, not megabytes.
     */
    @VisibleForTesting
    static final int MAX_REMEMBERED_MISSES = 512;

    private final LruCache<String, Bitmap> frames;
    private final LruCache<String, Long> misses = new LruCache<>(MAX_REMEMBERED_MISSES);

    /**
     * @param maxBytes the byte budget for this cache
     */
    DeArrowFrameCache(final int maxBytes) {
        frames = new LruCache<String, Bitmap>(maxBytes) {
            @Override
            protected int sizeOf(@NonNull final String key, @NonNull final Bitmap value) {
                // getByteCount, not width*height*4: a frame may be RGB_565 or hardware-backed,
                // and guessing the config is how a "safe" budget silently doubles.
                return value.getByteCount();
            }
        };
        INSTANCES.put(this, Boolean.TRUE);
    }

    /**
     * @param share how many of these caches divide the budget between them
     * @return a cache holding its share of {@link #HEAP_FRACTION} of the heap
     */
    static DeArrowFrameCache sharingHeap(final int share) {
        final long heap = Runtime.getRuntime().maxMemory();
        final long budget = heap / HEAP_FRACTION / Math.max(1, share);
        // Clamped so a tiny-heap device still caches something and a huge-heap one does not
        // hoard: below the floor every scroll re-fetches, above the ceiling it is just waste.
        final long clamped = Math.max(2L * 1024 * 1024, Math.min(budget, 24L * 1024 * 1024));
        return new DeArrowFrameCache((int) clamped);
    }

    /**
     * @param videoId the video
     * @return the frame, or null if it is not cached
     */
    @Nullable
    Bitmap get(@NonNull final String videoId) {
        return frames.get(videoId);
    }

    /**
     * @param videoId the video
     * @param frame   the decoded frame
     */
    void put(@NonNull final String videoId, @NonNull final Bitmap frame) {
        misses.remove(videoId);
        frames.put(videoId, frame);
    }

    /**
     * Records that this video has no frame to give.
     *
     * @param videoId the video
     */
    void recordMiss(@NonNull final String videoId) {
        misses.put(videoId, System.currentTimeMillis());
    }

    /**
     * @param videoId the video
     * @return whether a recent attempt failed, so this one should not be retried yet
     */
    boolean isKnownMiss(@NonNull final String videoId) {
        final Long at = misses.get(videoId);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > MISS_TTL_MS) {
            misses.remove(videoId);
            return false;
        }
        return true;
    }

    /** Drops everything this cache holds, including remembered failures. */
    void clear() {
        frames.evictAll();
        misses.evictAll();
    }

    /** Drops every DeArrow frame cache. Backs the settings screen's clear-cache action. */
    public static void clearAll() {
        synchronized (INSTANCES) {
            for (final DeArrowFrameCache cache : INSTANCES.keySet()) {
                cache.clear();
            }
        }
    }

    /**
     * Hands back memory when the system asks for it.
     *
     * <p>Registered once from the application object. Without it these caches are
     * process-lifetime and the system's only way to reclaim them is to kill the app —
     * which, for cosmetic thumbnails, is the wrong trade every time.</p>
     *
     * @param context any context; the application context is used
     */
    public static void registerTrimCallback(@NonNull final Context context) {
        context.getApplicationContext().registerComponentCallbacks(new ComponentCallbacks2() {
            @Override
            public void onTrimMemory(final int level) {
                if (level >= TRIM_MEMORY_BACKGROUND) {
                    // Backgrounded or worse: the user is not looking at these, and being the
                    // reason another app gets killed is not worth a cached thumbnail.
                    clearAll();
                }
            }

            @Override
            public void onConfigurationChanged(@NonNull final Configuration newConfig) {
            }

            @Override
            public void onLowMemory() {
                clearAll();
            }
        });
    }
}
