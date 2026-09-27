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
 * Fetches an image and hands back only a real one.
 *
 * <p><b>Nothing from the DeArrow thumbnail server may go straight to an image loader.</b>
 * {@code dearrow-thumb.ajay.app} serves frames it already holds and answers <b>HTTP 204 with
 * an empty body</b> for everything else, which is most videos. A 204 is a success as far as
 * an image loader is concerned, so it renders its placeholder — a grey box with a play arrow
 * — over a row that had a perfectly good thumbnail a moment earlier. A cosmetic feature then
 * makes the screen strictly worse, and only on videos DeArrow knows about, so it looks
 * sporadic rather than broken.</p>
 *
 * <p>That happened twice, on two different code paths: the live path in September, and the
 * community-thumbnail path reported on a real device on 2026-09-27. Repairing it from an
 * image loader's error callback was tried and did not reliably fire. So the bytes are
 * fetched here, checked here, decoded here, and a caller can only ever receive a bitmap that
 * really decoded.</p>
 */
public final class DeArrowImageFetch {

    private static final String TAG = "DeArrowImageFetch";

    /** Smaller than a real image could plausibly be; guards against a truncated body. */
    @VisibleForTesting
    static final int MIN_IMAGE_BYTES = 512;

    private static final Map<String, Maybe<Bitmap>> IN_FLIGHT = new ConcurrentHashMap<>();

    private DeArrowImageFetch() {
    }

    /**
     * Fetches one image.
     *
     * <p>Completes empty rather than erroring for anything that is not a usable image — a
     * 204, any other non-200, a short body, bytes that will not decode, an unreachable
     * host. Callers read that as "leave the view alone".</p>
     *
     * @param url the image to fetch
     * @return a Maybe emitting at most one bitmap, on the IO scheduler; never errors
     */
    @NonNull
    public static Maybe<Bitmap> fetch(@NonNull final String url) {
        // Several rows can want the same image at once — the subscription feed does produce
        // that — so they share one request rather than racing. The entry is dropped once it
        // settles, so a later bind can retry after a transient failure.
        return IN_FLIGHT.computeIfAbsent(url, u -> Maybe
                .fromCallable(() -> fetchBlocking(u))
                .doFinally(() -> IN_FLIGHT.remove(u))
                .onErrorComplete()
                .subscribeOn(Schedulers.io())
                .cache());
    }

    /**
     * @param url the image to fetch
     * @return the decoded image, or null if there was nothing usable at that URL
     */
    @Nullable
    private static Bitmap fetchBlocking(@NonNull final String url) {
        try {
            final Response response = NewPipe.getDownloader().get(url);
            if (response.responseCode() != 200) {
                // 204 is the common case, and it carries no body at all.
                return null;
            }
            final byte[] body = response.rawResponseBody();
            if (body == null || body.length < MIN_IMAGE_BYTES) {
                return null;
            }
            return BitmapFactory.decodeByteArray(body, 0, body.length);
        } catch (final Exception | OutOfMemoryError e) {
            // Swallowed on purpose: no image means the uploader's thumbnail stays, which is
            // the correct fallback. A thumbnail must never break browsing.
            Log.d(TAG, "no image at " + url, e);
            return null;
        }
    }
}
