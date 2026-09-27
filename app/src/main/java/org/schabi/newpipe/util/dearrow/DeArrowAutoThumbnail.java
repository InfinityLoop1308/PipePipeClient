package org.schabi.newpipe.util.dearrow;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Gets a frame out of a video without downloading any of the video.
 *
 * <p>YouTube already stores three automatically-extracted frames for every upload, at roughly
 * a quarter, a half and three quarters of the way through, and serves them from the same
 * public image host as the uploader's own thumbnail:</p>
 *
 * <pre>https://i.ytimg.com/vi/&lt;videoId&gt;/hq1.jpg   (also hq2, hq3)</pre>
 *
 * <p>They are ~7 KB each, need no API key, no player request, no signature deciphering and no
 * video decoding — a single plain HTTP GET, the same cost as the thumbnail the row is already
 * loading. Crucially they are <em>real frames</em>, not crops of the uploader's artwork:
 * measured against {@code hqdefault.jpg} they differ by 0.28–0.34 normalised RMSE, and from
 * each other by a similar margin (2026-09-25, eight-video sample).</p>
 *
 * <p><b>Why this exists at all.</b> {@link DeArrowFrameRenderer} produces a frame at the exact
 * seeded timestamp, which is more faithful, but each one costs an extractor call to resolve a
 * playback URL plus range reads into the video container — seconds per thumbnail. On a list of
 * twelve results that is slower than the user scrolls, so most rows were still showing
 * clickbait by the time they were looked at, and the feature read as broken. Three fixed
 * frames fetched instantly beat one perfect frame that arrives after the user has moved on.
 * The renderer is kept as the fallback for the videos this cannot serve.</p>
 *
 * <p><b>This does not work for live broadcasts, and there is a trap in finding that out.</b>
 * {@code hq1}–{@code hq3} are written when an upload is processed and 404 for a stream that is
 * still running. YouTube does serve a {@code hq720_live.jpg} for a broadcast, and it is
 * tempting — 1280×720, natively 16:9, present on every live stream tried. <b>It is not a
 * frame.</b> It is the broadcaster's own thumbnail at 720p: for a stream with clickbait
 * artwork, {@code hq720_live.jpg} is that same artwork, so using it replaces the thumbnail
 * with itself.
 *
 * <p>Measuring RMSE against {@code hqdefault.jpg} does <em>not</em> catch this. That comparison
 * reads 0.28–0.40 for a live stream and looks exactly like a real difference — but it is the
 * letterboxing, since {@code hqdefault} is boxed into 4:3 and {@code hq720_live} is not. The
 * two images are the same picture. What exposed it was looking at them (2026-09-27); what
 * should have raised the alarm earlier was a live stream whose {@code hq720_live} matched its
 * {@code maxresdefault} at <em>exactly</em> RMSE 0, which is what happens when a broadcaster
 * sets no custom thumbnail and YouTube fills both slots from the same source.
 *
 * <p>Live has no cheap frame source at all: the storyboard sprite sheets that uploads carry are
 * absent on a broadcast, and {@code dearrow-thumb.ajay.app} answered HTTP 204 for every live
 * stream tried on three separate days. The only thing that yields a real frame is decoding the
 * broadcast itself at the live edge, which is {@link DeArrowFrameRenderer#renderLive}.</p>
 */
public final class DeArrowAutoThumbnail {

    private static final String TAG = "DeArrowAutoThumb";

    /** The host serving YouTube's stored thumbnails. No key, no auth, no rate limit in practice. */
    private static final String THUMBNAIL_HOST = "https://i.ytimg.com/vi/";

    /** How many automatically-extracted frames YouTube stores per upload: hq1, hq2, hq3. */
    @VisibleForTesting
    static final int AUTO_FRAME_COUNT = 3;

    private static final int MAX_CACHED_FRAMES = 120;

    /** Smaller than a real JPEG could be; guards against a truncated or error body. */
    @VisibleForTesting
    static final int MIN_IMAGE_BYTES = 512;

    /**
     * A row is near-black if its mean luminance is below this, on a 0–255 scale.
     *
     * <p>Letterbox bars are encoded flat black but pick up a little JPEG ringing at the
     * boundary, so an exact-zero test misses them.</p>
     */
    @VisibleForTesting
    static final int BLACK_LUMA = 16;

    /**
     * The most that may be cropped off each edge, as a fraction of height.
     *
     * <p>Without this, a frame that is legitimately dark at the top — a night scene, a fade —
     * would be cropped to a sliver. 16:9 inside a 4:3 box needs 12.5% off each edge, so this
     * leaves room for that and little else.</p>
     */
    @VisibleForTesting
    static final float MAX_CROP_FRACTION = 0.2f;

    private static DeArrowAutoThumbnail instance;

    private final DeArrowFrameCache frames = DeArrowFrameCache.sharingHeap(3);
    private final Map<String, Maybe<Bitmap>> inFlight = new ConcurrentHashMap<>();

    private DeArrowAutoThumbnail() {
    }

    public static synchronized DeArrowAutoThumbnail getInstance() {
        if (instance == null) {
            instance = new DeArrowAutoThumbnail();
        }
        return instance;
    }

    /**
     * Picks which of the three stored frames to show.
     *
     * <p>Driven by the same seeded generator the DeArrow server uses to choose a timestamp
     * ({@link DeArrowRandomTime}), so the choice is arbitrary but stable: one video always
     * gets the same frame, on every device, across restarts. A row that scrolls away and
     * comes back does not change picture.</p>
     *
     * @param videoId the video
     * @return 1, 2 or 3 — the {@code N} in {@code hqN.jpg}
     */
    @VisibleForTesting
    static int frameIndexFor(@NonNull final String videoId) {
        // fractionFor is in [0, TAIL_TO_AVOID); rescaling to [0, 1) keeps all three frames
        // equally likely rather than starving hq3.
        final double scaled = DeArrowRandomTime.fractionFor(videoId)
                / DeArrowRandomTime.TAIL_TO_AVOID;
        final int index = (int) (scaled * AUTO_FRAME_COUNT) + 1;
        return Math.min(index, AUTO_FRAME_COUNT);
    }

    /**
     * @param videoId the video
     * @return the URL of the stored frame chosen for this video
     */
    @VisibleForTesting
    @NonNull
    static String urlFor(@NonNull final String videoId) {
        return String.format(Locale.US, "%s%s/hq%d.jpg",
                THUMBNAIL_HOST, videoId, frameIndexFor(videoId));
    }

    /**
     * A frame already fetched for this video, if there is one that is still current.
     *
     * <p>Lets a recycled row paint with no asynchronous hop, so scrolling back over a video
     * does not make it flip a second time.</p>
     *
     * @param videoId the video
     * @return the cached frame, or null
     */
    @Nullable
    public Bitmap getCached(@NonNull final String videoId) {
        return frames.get(videoId);
    }

    /**
     * Fetches the stored frame for a video.
     *
     * <p>Completes empty rather than erroring for anything that is not a usable image — a 404
     * (live broadcasts, and uploads still being processed), a truncated body, bytes that will
     * not decode, an unreachable host. Callers read that as "leave the row alone", and for a
     * 404 specifically as "try the slow renderer instead".</p>
     *
     * @param videoId the video
     * @return a Maybe emitting at most one bitmap, on the IO scheduler
     */
    @NonNull
    public Maybe<Bitmap> fetch(@NonNull final String videoId) {
        final Bitmap cached = getCached(videoId);
        if (cached != null) {
            return Maybe.just(cached);
        }
        if (frames.isKnownMiss(videoId)) {
            // Asked recently, nothing there. Retrying on every rebind turns one scroll up
            // and down into a request per row per pass.
            return Maybe.empty();
        }
        // Two rows showing the same video — which the subscription feed does produce — share
        // one request instead of racing; the entry is dropped once it settles so a later bind
        // can retry after a transient failure.
        return inFlight.computeIfAbsent(videoId, id -> Maybe
                .fromCallable(() -> fetchBlocking(id))
                .doFinally(() -> inFlight.remove(id))
                .onErrorComplete()
                .subscribeOn(Schedulers.io())
                .cache());
    }

    /**
     * @param videoId the video
     * @param live    whether this is a broadcast in progress rather than an upload
     * @return the decoded, de-letterboxed frame, or null if there is nothing usable
     */
    @Nullable
    private Bitmap fetchBlocking(@NonNull final String videoId) {
        try {
            final Response response = NewPipe.getDownloader().get(urlFor(videoId));
            if (response.responseCode() != 200) {
                // 404 here means the upload is too fresh to have been processed, or that a
                // row reported the wrong stream type — the caller falls back accordingly.
                // Remembered, so scrolling past the same row repeatedly does not re-ask for
                // an image that is not coming.
                frames.recordMiss(videoId);
                return null;
            }
            final byte[] body = response.rawResponseBody();
            if (body == null || body.length < MIN_IMAGE_BYTES) {
                frames.recordMiss(videoId);
                return null;
            }
            final Bitmap decoded = BitmapFactory.decodeByteArray(body, 0, body.length);
            if (decoded == null) {
                frames.recordMiss(videoId);
                return null;
            }
            final Bitmap frame = stripLetterbox(decoded);
            frames.put(videoId, frame);
            return frame;
        } catch (final Exception | OutOfMemoryError e) {
            // Swallowed on purpose: no frame means the uploader's thumbnail stays, which is
            // the correct fallback. A cosmetic feature must never break browsing.
            frames.recordMiss(videoId);
            Log.d(TAG, "no stored frame for " + videoId, e);
            return null;
        }
    }

    /**
     * Removes the black bars YouTube pads these frames with.
     *
     * <p>They are served in a 4:3 box — 480×360 — so a widescreen video arrives with a black
     * band above and below it. Pasted into a 16:9 list row unchanged, the picture is squashed
     * into the middle third and looks obviously wrong next to the untouched rows around it.
     * The bars are detected rather than assumed, because a genuinely 4:3 upload has none and
     * cropping it blind would cut the top and bottom off the picture.</p>
     *
     * @param source the decoded frame
     * @return the frame with its bars removed, or {@code source} itself if it has none
     */
    @VisibleForTesting
    @NonNull
    static Bitmap stripLetterbox(@NonNull final Bitmap source) {
        final int width = source.getWidth();
        final int height = source.getHeight();
        final int limit = (int) (height * MAX_CROP_FRACTION);

        int top = 0;
        while (top < limit && isRowBlack(source, top, width)) {
            top++;
        }
        int bottom = height - 1;
        while (height - 1 - bottom < limit && isRowBlack(source, bottom, width)) {
            bottom--;
        }
        final int cropped = bottom - top + 1;
        if (top == 0 && cropped == height) {
            return source;
        }
        // A frame that is dark at BOTH edges is a night scene or a fade, not a letterboxed
        // one — cropping it would cut picture rather than padding. The test has to be "did
        // we hit the cap at both ends", because a test against height/2 can never fire:
        // MAX_CROP_FRACTION caps each edge at 20%, so `cropped` is always at least 60% of
        // the height and the comparison is dead code (2026-09-27).
        if (top >= limit && height - 1 - bottom >= limit) {
            return source;
        }
        return Bitmap.createBitmap(source, 0, top, width, cropped);
    }

    /**
     * @param bitmap the frame
     * @param y      the row to test
     * @param width  the frame's width
     * @return whether that row is dark enough to be a letterbox bar
     */
    private static boolean isRowBlack(@NonNull final Bitmap bitmap, final int y, final int width) {
        // Every eighth pixel is plenty to tell a flat black bar from picture, and keeps this
        // to a few hundred reads per frame rather than a few hundred thousand.
        final int step = Math.max(1, width / 60);
        long total = 0;
        int samples = 0;
        for (int x = 0; x < width; x += step) {
            final int pixel = bitmap.getPixel(x, y);
            final int r = (pixel >> 16) & 0xFF;
            final int g = (pixel >> 8) & 0xFF;
            final int b = pixel & 0xFF;
            // Rec. 601 luma, integer-scaled to avoid a float per pixel.
            total += (299L * r + 587L * g + 114L * b) / 1000L;
            samples++;
        }
        return samples > 0 && total / samples < BLACK_LUMA;
    }
}
