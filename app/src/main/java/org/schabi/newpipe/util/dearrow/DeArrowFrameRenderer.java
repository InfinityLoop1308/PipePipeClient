package org.schabi.newpipe.util.dearrow;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Pulls a single frame out of a video, for videos nobody has submitted a thumbnail for.
 *
 * <p>This is the Android equivalent of what the DeArrow extension does in
 * {@code thumbnailRenderer.ts}: it creates a {@code <video>} on the playback URL, seeks to a
 * timestamp and draws that to a canvas. Here that is
 * {@link MediaMetadataRetriever#getFrameAtTime} against the same kind of URL.</p>
 *
 * <p><b>Why this cannot be left to the server.</b> The obvious approach — ask
 * {@code dearrow-thumb.ajay.app} to render it with {@code generateNow=true} — does not work.
 * That endpoint returned HTTP 204 on every attempt across several videos (2026-09-23): it
 * serves frames it already holds and will not generate new ones. The extension agrees,
 * treating any non-200 from the cache as "no thumbnail" and rendering locally. So a client
 * that wants a frame for an unsubmitted video has to produce it itself.</p>
 *
 * <p><b>This is the expensive path and it is treated as one.</b> Each render costs an
 * extractor call to resolve a stream URL plus range requests into the video. Live streams
 * and unknown durations are skipped, at most {@link #MAX_CONCURRENT_RENDERS} run at a time,
 * results are cached by video id, and a failure is always a silent no-op that leaves the
 * uploader's thumbnail alone.</p>
 */
public final class DeArrowFrameRenderer {

    private static final String TAG = "DeArrowFrameRenderer";

    /**
     * Renders in flight at once.
     *
     * <p>This was 2, and that was the difference between the feature working and the feature
     * looking dead. Each render is dominated by network waiting — resolving a stream, then
     * range-reading into it — so a low cap does not save CPU, it just serialises latency: a
     * screen of eight results rendered four frames in forty seconds, and a user who scrolls
     * before then sees the uploader's thumbnails and concludes nothing happened
     * (2026-09-24).</p>
     *
     * <p>Six is chosen to cover a visible screen in roughly one pass while still bounding
     * how many video streams are open at once.</p>
     */
    @VisibleForTesting
    static final int MAX_CONCURRENT_RENDERS = 6;

    /**
     * How many rendered frames to keep. Each is a scaled-down bitmap, so this is a few MB
     * rather than a few hundred.
     */
    private static final int MAX_CACHED_FRAMES = 60;

    /**
     * How many formats to try before giving up on a video. Three covers the case that
     * motivated this — the smallest format refusing to open while the next one works —
     * without turning one thumbnail into an unbounded series of network attempts.
     */
    @VisibleForTesting
    static final int MAX_STREAMS_TRIED = 3;

    /** Frames are rendered small: they are shown in a list row, never full screen. */
    private static final int TARGET_WIDTH = 480;
    private static final int TARGET_HEIGHT = 270;

    private static DeArrowFrameRenderer instance;

    private final LruCache<String, Bitmap> frames = new LruCache<>(MAX_CACHED_FRAMES);
    private final Map<String, Maybe<Bitmap>> inFlight = new ConcurrentHashMap<>();
    private final Semaphore renderSlots = new Semaphore(MAX_CONCURRENT_RENDERS, true);

    private DeArrowFrameRenderer() {
    }

    public static synchronized DeArrowFrameRenderer getInstance() {
        if (instance == null) {
            instance = new DeArrowFrameRenderer();
        }
        return instance;
    }

    /**
     * An already-rendered frame, if there is one.
     *
     * <p>Lets a recycled row show its frame with no asynchronous step, so scrolling back
     * over a video does not visibly flip a second time.</p>
     *
     * @param videoId the video
     * @return the cached frame, or null
     */
    @Nullable
    public Bitmap getCached(@NonNull final String videoId) {
        return frames.get(videoId);
    }

    /**
     * Renders the frame DeArrow would pick for this video.
     *
     * <p>Never errors: everything that can go wrong — a live stream, an unresolvable URL, a
     * codec that will not seek — completes empty, which callers read as "keep what YouTube
     * gave us".</p>
     *
     * @param serviceId the service the stream belongs to
     * @param url       the stream page URL
     * @param videoId   the video id, which seeds the timestamp
     * @param duration  the video's length in seconds; 0 or less means do nothing
     * @return a Maybe that emits at most one bitmap, on the IO scheduler
     */
    @NonNull
    public Maybe<Bitmap> render(final int serviceId,
                                @NonNull final String url,
                                @NonNull final String videoId,
                                final long duration) {
        final Bitmap cached = frames.get(videoId);
        if (cached != null) {
            return Maybe.just(cached);
        }
        final double seconds = DeArrowRandomTime.secondsFor(videoId, duration);
        if (seconds < 0) {
            // A live stream, or an item with no known length. Nothing to seek to.
            return Maybe.empty();
        }
        return inFlight.computeIfAbsent(videoId, id -> ExtractorHelper
                .getStreamInfo(serviceId, url, false)
                .flatMapMaybe(info -> Maybe.fromCallable(() -> renderBlocking(info, id, seconds)))
                .doFinally(() -> inFlight.remove(id))
                .doOnError(e -> Log.d(TAG, "could not resolve a stream for " + id, e))
                .onErrorComplete()
                .subscribeOn(Schedulers.io())
                .cache());
    }

    /**
     * Grabs the current frame of a live broadcast.
     *
     * <p><b>Why a broadcast needs its own path, and why it costs what it costs.</b> Everything
     * cheap has been tried and does not work. The stored frames an upload has
     * ({@link DeArrowAutoThumbnail}) are never written for a stream in progress. The
     * {@code hq720_live.jpg} that broadcasts do have looks like the answer and is not — it is
     * the broadcaster's own thumbnail at 720p, so using it replaces the clickbait with itself.
     * The storyboard sprite sheets uploads carry are absent on a broadcast. And
     * {@code dearrow-thumb.ajay.app} answered HTTP 204 for every live stream tried, on three
     * separate days. That leaves decoding the broadcast, which is what this does.</p>
     *
     * <p>There is no timestamp to seek to — a broadcast has no fixed length and the seeded
     * generator has nothing to work with — so the frame taken is simply wherever the stream
     * currently is. That is the right answer anyway: "what is happening right now" is what a
     * viewer wants from a live thumbnail, and it is what the DeArrow extension asks the
     * thumbnail server for.</p>
     *
     * @param serviceId the service the stream belongs to
     * @param url       the stream page URL
     * @param videoId   the video id, used as the cache key
     * @return a Maybe that emits at most one bitmap, on the IO scheduler; never errors
     */
    @NonNull
    public Maybe<Bitmap> renderLive(final int serviceId,
                                    @NonNull final String url,
                                    @NonNull final String videoId) {
        final Bitmap cached = frames.get(videoId);
        if (cached != null) {
            return Maybe.just(cached);
        }
        return inFlight.computeIfAbsent(videoId, id -> ExtractorHelper
                .getStreamInfo(serviceId, url, false)
                .flatMapMaybe(info -> Maybe.fromCallable(() -> renderLiveBlocking(info, id)))
                .doFinally(() -> inFlight.remove(id))
                .doOnError(e -> Log.d(TAG, "could not resolve a broadcast for " + id, e))
                .onErrorComplete()
                .subscribeOn(Schedulers.io())
                .cache());
    }

    /**
     * Opens a broadcast and takes whatever frame it is showing. Blocking; expects an IO thread.
     *
     * @param info    the resolved stream
     * @param videoId the video, used as the cache key
     * @return the frame, or null if the broadcast would not open
     */
    @Nullable
    private Bitmap renderLiveBlocking(@NonNull final StreamInfo info,
                                      @NonNull final String videoId) {
        final List<String> urls = liveUrlsSmallestFirst(info);
        if (urls.isEmpty()) {
            Log.d(TAG, "no live stream URL for " + videoId);
            return null;
        }
        boolean acquired = false;
        try {
            renderSlots.acquire();
            acquired = true;
            for (final String streamUrl : urls) {
                // Time 0 with OPTION_CLOSEST_SYNC on a live playlist gives the first frame of
                // the segment currently being served, which is the live edge — there is no
                // earlier position to land on, because a live playlist only holds a short
                // trailing window.
                final Bitmap frame = grabFrame(streamUrl, 0);
                if (frame == null) {
                    continue;
                }
                final Bitmap scaled =
                        Bitmap.createScaledBitmap(frame, TARGET_WIDTH, TARGET_HEIGHT, true);
                if (scaled != frame) {
                    frame.recycle();
                }
                frames.put(videoId, scaled);
                return scaled;
            }
            Log.d(TAG, "no broadcast stream would open for " + videoId);
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (acquired) {
                renderSlots.release();
            }
        }
    }

    /**
     * The URLs worth trying for a broadcast, cheapest first.
     *
     * <p>A low rendition is preferred deliberately and costs nothing visually: the result is
     * scaled to {@link #TARGET_WIDTH}×{@link #TARGET_HEIGHT} for a list row regardless, and a
     * 240p variant opens in a fraction of the time a 1080p one does. The master playlist is the
     * last resort, because opening it makes the player pick a rendition itself, usually a large
     * one.</p>
     *
     * @param info the resolved stream
     * @return the candidate URLs
     */
    @NonNull
    private static List<String> liveUrlsSmallestFirst(@NonNull final StreamInfo info) {
        final List<VideoStream> candidates = new ArrayList<>();
        if (info.getVideoStreams() != null) {
            candidates.addAll(info.getVideoStreams());
        }
        if (info.getVideoOnlyStreams() != null) {
            candidates.addAll(info.getVideoOnlyStreams());
        }
        final List<String> urls = videoUrlsSmallestFirst(candidates);
        final String master = info.getHlsUrl();
        if (master != null && !master.isEmpty() && !urls.contains(master)) {
            urls.add(master);
        }
        return urls;
    }

    /**
     * Does the actual frame grab. Blocking, and expects to be on an IO thread.
     *
     * @param info    the resolved stream, for its URLs and type
     * @param videoId the video, used as the cache key
     * @param seconds where in the video to grab from
     * @return the frame, or null if this video cannot give one
     */
    @Nullable
    private Bitmap renderBlocking(@NonNull final StreamInfo info,
                                  @NonNull final String videoId,
                                  final double seconds) {
        if (info.getStreamType() == StreamType.LIVE_STREAM
                || info.getStreamType() == StreamType.AUDIO_LIVE_STREAM) {
            // A live stream has no fixed length, so there is no frame at a timestamp.
            return null;
        }
        // Both lists, because they hold different things: getVideoStreams() is the
        // progressive (muxed) formats, which YouTube barely serves any more, and
        // getVideoOnlyStreams() is the adaptive ones, which is where everything actually
        // is. Reading only the first left nothing to render from — the feature looked
        // switched off (2026-09-24).
        final List<VideoStream> candidates = new ArrayList<>();
        if (info.getVideoStreams() != null) {
            candidates.addAll(info.getVideoStreams());
        }
        if (info.getVideoOnlyStreams() != null) {
            candidates.addAll(info.getVideoOnlyStreams());
        }
        // Try several streams, smallest first, rather than betting on one. The smallest
        // format is often 144p AVC (itag 160) and MediaMetadataRetriever refuses to open
        // some of those outright — "setDataSource failed: status = 0x80000000" — while the
        // next format up opens fine. Giving up after the first candidate left visible rows
        // with their clickbait thumbnails for no better reason than format roulette
        // (2026-09-25).
        final List<String> urls = videoUrlsSmallestFirst(candidates);
        if (urls.isEmpty()) {
            Log.d(TAG, "no usable video stream for " + videoId);
            return null;
        }

        boolean acquired = false;
        try {
            // Queue rather than drop. Returning immediately when busy meant a screen of
            // results rendered at most two frames and every other row silently kept its
            // clickbait image. This is already an IO thread, so waiting here is free.
            renderSlots.acquire();
            acquired = true;

            for (final String streamUrl : urls) {
                final Bitmap frame = grabFrame(streamUrl, seconds);
                if (frame == null) {
                    continue;
                }
                final Bitmap scaled =
                        Bitmap.createScaledBitmap(frame, TARGET_WIDTH, TARGET_HEIGHT, true);
                if (scaled != frame) {
                    frame.recycle();
                }
                frames.put(videoId, scaled);
                return scaled;
            }
            Log.d(TAG, "no stream would open for " + videoId + " (" + urls.size()
                    + " tried)");
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (acquired) {
                renderSlots.release();
            }
        }
    }

    /**
     * Opens one stream and takes a frame from it.
     *
     * @param streamUrl the video URL
     * @param seconds   where in the video to grab from
     * @return the frame, or null if this stream would not open or had nothing there
     */
    @Nullable
    private static Bitmap grabFrame(@NonNull final String streamUrl, final double seconds) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            // YouTube refuses a request with no User-Agent, and MediaMetadataRetriever's
            // native HTTP stack sends none by default. Reuse the app's own so the stream
            // server sees the same client it would during playback.
            final Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", DownloaderImpl.USER_AGENT);
            retriever.setDataSource(streamUrl, headers);
            return retriever.getFrameAtTime((long) (seconds * 1_000_000L),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (final Exception | OutOfMemoryError e) {
            // Swallowed on purpose: the caller simply moves on to the next format, and if
            // none opens the row keeps the uploader's thumbnail, which is the correct
            // fallback. A cosmetic feature must never break browsing.
            return null;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (final Exception ignored) {
                    // release() throwing tells us nothing we can act on.
                }
            }
        }
    }

    /**
     * Picks the cheapest usable video stream.
     *
     * <p>The smallest one is wanted, not the best: the output is a list thumbnail a few
     * hundred pixels wide, and a 4K stream costs far more to range-request for no visible
     * gain.</p>
     *
     * <p><b>Video-only (adaptive) streams are explicitly included.</b> YouTube serves almost
     * nothing but those now, and a frame grab needs no audio track — excluding them left
     * nothing to render from at all, which showed up as the feature silently doing
     * nothing.</p>
     *
     * <p>Returns several, ordered, because the smallest format does not always open —
     * the caller works down the list until one does.</p>
     *
     * @param streams the resolved video streams
     * @return usable URLs, smallest resolution first; empty if none are usable
     */
    @NonNull
    @VisibleForTesting
    static List<String> videoUrlsSmallestFirst(@Nullable final List<VideoStream> streams) {
        final List<String> urls = new ArrayList<>();
        if (streams == null || streams.isEmpty()) {
            return urls;
        }
        final List<VideoStream> usable = new ArrayList<>();
        for (final VideoStream stream : streams) {
            if (stream != null && stream.getUrl() != null) {
                usable.add(stream);
            }
        }
        // An unreadable resolution sorts last rather than first: it is a guess, and a
        // guess should not displace a format whose size is actually known.
        usable.sort(Comparator.comparingInt(s -> {
            final int height = heightOf(s.getResolution());
            return height > 0 ? height : Integer.MAX_VALUE;
        }));
        for (final VideoStream stream : usable) {
            if (!urls.contains(stream.getUrl())) {
                urls.add(stream.getUrl());
            }
            if (urls.size() >= MAX_STREAMS_TRIED) {
                break;
            }
        }
        return urls;
    }

    /**
     * @param resolution a label like {@code "360p"} or {@code "1080p60"}
     * @return the vertical pixel count, or -1 if it cannot be read
     */
    @VisibleForTesting
    static int heightOf(@Nullable final String resolution) {
        if (resolution == null) {
            return -1;
        }
        final StringBuilder digits = new StringBuilder();
        for (int i = 0; i < resolution.length(); i++) {
            final char c = resolution.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (final NumberFormatException e) {
            return -1;
        }
    }
}
