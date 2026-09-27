package org.schabi.newpipe.util.dearrow;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Fetches the current frame of a live broadcast.
 *
 * <p>A live stream has no fixed duration, so the seeded-timestamp path
 * ({@link DeArrowRandomTime}) has nothing to work with and {@link DeArrowFrameRenderer}
 * has nothing to seek to. The thumbnail server solves it instead: asked with
 * {@code generateNow=true} and no timestamp, it renders the broadcast as it is right now.
 * This is what the browser extension does — {@code dataFetching.ts}: <em>"Live videos have
 * no backup, so try to generate it now"</em>.</p>
 *
 * <p><b>The response must be checked before it reaches a view.</b> That endpoint answers
 * HTTP 204 with an empty body for broadcasts it cannot render, and handing a 204 to an
 * image loader paints an empty grey box over a row that previously had a perfectly good
 * thumbnail — a cosmetic feature making the screen worse. Letting the image loader fetch
 * the URL directly and repairing it from an error callback did not reliably fire
 * (2026-09-24), so the bytes are fetched here, decoded here, and only a real bitmap is
 * ever returned.</p>
 *
 * <p>Unlike a normal video's frame, a live thumbnail legitimately changes as the broadcast
 * moves on, so it is cached only briefly — long enough that scrolling does not re-fetch
 * it, not so long that it goes stale.</p>
 */
public final class DeArrowLiveFrame {

    private static final String TAG = "DeArrowLiveFrame";

    /** Smaller than a real image could plausibly be; guards against a truncated body. */
    @VisibleForTesting
    static final int MIN_IMAGE_BYTES = 512;

    private static DeArrowLiveFrame instance;

    private final Map<String, Maybe<Bitmap>> inFlight = new ConcurrentHashMap<>();

    private DeArrowLiveFrame() {
    }

    public static synchronized DeArrowLiveFrame getInstance() {
        if (instance == null) {
            instance = new DeArrowLiveFrame();
        }
        return instance;
    }
    /**
     * Deliberately NOT cached.
     *
     * <p>This used to hold forty decoded bitmaps — counted in entries, so roughly 37 MB —
     * for a path that has never once returned an image: the thumbnail server answers HTTP
     * 204 for every live broadcast tried, on three separate days. Caching nothing is the
     * honest size for a cache with no contents, and the in-flight map below already stops
     * two rows for the same broadcast from asking twice.</p>
     *
     * @param videoId the broadcast
     * @return always null; kept so callers need no special case
     */
    @Nullable
    public Bitmap getCached(@NonNull final String videoId) {
        return null;
    }

    /**
     * Fetches the broadcast's current frame.
     *
     * <p>Completes empty rather than erroring whenever there is no usable image — a 204, a
     * short body, bytes that will not decode, or an unreachable server. Callers read that
     * as "leave the row alone".</p>
     *
     * @param videoId the broadcast
     * @param config  the user's settings, for the thumbnail host
     * @return a Maybe emitting at most one bitmap, on the IO scheduler
     */
    @NonNull
    public Maybe<Bitmap> fetch(@NonNull final String videoId,
                               @NonNull final DeArrowConfig config) {
        final Bitmap cached = getCached(videoId);
        if (cached != null) {
            return Maybe.just(cached);
        }
        return inFlight.computeIfAbsent(videoId, id -> Maybe
                .fromCallable(() -> fetchBlocking(id, config))
                .doFinally(() -> inFlight.remove(id))
                .onErrorComplete()
                .subscribeOn(Schedulers.io())
                .cache());
    }

    /**
     * @param videoId the broadcast
     * @param config  the user's settings
     * @return the decoded frame, or null if the server had nothing usable to give
     */
    @Nullable
    private Bitmap fetchBlocking(@NonNull final String videoId,
                                 @NonNull final DeArrowConfig config) {
        try {
            final Response response = NewPipe.getDownloader()
                    .get(DeArrowLiveThumbnail.urlFor(videoId, config));

            // 204 means "I cannot render this broadcast". It is the common case for a
            // stream the server has never seen, and it carries no body at all.
            if (response.responseCode() != 200) {
                return null;
            }
            final byte[] body = response.rawResponseBody();
            if (body == null || body.length < MIN_IMAGE_BYTES) {
                return null;
            }
            final Bitmap frame = BitmapFactory.decodeByteArray(body, 0, body.length);
            if (frame == null) {
                return null;
            }

            return frame;
        } catch (final Exception | OutOfMemoryError e) {
            // Swallowed on purpose: no frame means the broadcaster's own thumbnail stays,
            // which is the correct fallback. A thumbnail must never break browsing.
            Log.d(TAG, "no live frame for " + videoId, e);
            return null;
        }
    }
}
