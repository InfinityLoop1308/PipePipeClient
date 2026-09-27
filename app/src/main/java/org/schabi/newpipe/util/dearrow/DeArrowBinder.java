package org.schabi.newpipe.util.dearrow;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.util.PicassoHelper;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Applies DeArrow branding to an already-bound list row.
 *
 * <p>This is the class that answers the long-standing objection to DeArrow on Android — that
 * swapping titles and thumbnails after the fact cannot be done seamlessly. It can, provided three
 * rules are never broken, and they are enforced here rather than left to each call site:</p>
 *
 * <ol>
 *   <li><b>The original is bound first, synchronously, always.</b> {@link #apply} is called
 *       <em>after</em> the row has already been populated with the uploader's title and
 *       thumbnail. A DeArrow lookup can therefore never delay a bind or leave a row blank.</li>
 *   <li><b>A replacement is applied only if the row is still showing the same video.</b>
 *       RecyclerView reuses holders aggressively; the video id is stored on the view and
 *       re-checked on the main thread before anything is written, so a slow response for a
 *       scrolled-away row is discarded instead of corrupting the row that replaced it.</li>
 *   <li><b>A cached result is applied synchronously, with no asynchronous step at all.</b> After
 *       the first pass over a list, scrolling back and forth shows honest titles immediately —
 *       the visible flip only ever happens once per video, on the very first fetch.</li>
 * </ol>
 *
 * <p>A failure at any point is a no-op: the row keeps what YouTube gave it.</p>
 */
public final class DeArrowBinder {

    private DeArrowBinder() {
    }

    /**
     * Replaces the title and thumbnail of a row with DeArrow's, if the user has opted in and
     * DeArrow has anything to say about this video.
     *
     * @param infoItem     the item the row was just bound to; ignored unless it is a YouTube
     *                     stream, since DeArrow only covers YouTube
     * @param titleView    the row's title view, already showing the uploader's title
     * @param thumbnailView the row's thumbnail view, already loading the uploader's thumbnail;
     *                      may be null for rows that show no image
     */
    public static void apply(@Nullable final InfoItem infoItem,
                             @NonNull final TextView titleView,
                             @Nullable final ImageView thumbnailView) {
        final StreamInfoItem item = infoItem instanceof StreamInfoItem
                ? (StreamInfoItem) infoItem
                : null;
        // Whether a row is live comes from its stream TYPE, never its duration. Duration
        // is -1 on every row of a YouTube search result — the extractor does not populate
        // it there — so treating "no duration" as "live" sent every single video down the
        // live path and stopped frame rendering completely (2026-09-25).
        final boolean live = item != null
                && (item.getStreamType() == StreamType.LIVE_STREAM
                    || item.getStreamType() == StreamType.AUDIO_LIVE_STREAM);
        applyToVideo(videoIdOf(infoItem),
                item == null ? -1 : item.getServiceId(),
                item == null ? null : item.getUrl(),
                item == null ? 0 : item.getDuration(),
                live, titleView, thumbnailView);
    }

    /**
     * The same, for rows built from a stored stream rather than an extractor item — the
     * subscription feed and the watch history, which hold a {@code StreamEntity} instead.
     *
     * @param serviceId     the service the stream came from; anything but YouTube is ignored
     * @param url           the stream URL the video id is read out of
     * @param duration      the video's length in seconds, needed to pick a frame to render
     *                      when nobody has submitted a thumbnail; 0 disables that fallback
     * @param live          whether this is a live broadcast. Passed explicitly because a
     *                      duration of 0 or -1 does NOT mean live — YouTube search results
     *                      report -1 for every row, live or not.
     * @param titleView     the row's title view, already showing the stored title
     * @param thumbnailView the row's thumbnail view, or null
     */
    public static void apply(final int serviceId,
                             @Nullable final String url,
                             final long duration,
                             final boolean live,
                             @NonNull final TextView titleView,
                             @Nullable final ImageView thumbnailView) {
        applyToVideo(serviceId == ServiceList.YouTube.getServiceId()
                ? DeArrowVideoId.fromUrl(url)
                : null, serviceId, url, duration, live, titleView, thumbnailView);
    }

    private static void applyToVideo(@Nullable final String videoId,
                                     final int serviceId,
                                     @Nullable final String url,
                                     final long duration,
                                     final boolean live,
                                     @NonNull final TextView titleView,
                                     @Nullable final ImageView thumbnailView) {
        if (videoId == null) {
            clearPending(titleView);
            return;
        }
        final DeArrowConfig config = DeArrowSettings.read(titleView.getContext());
        if (!config.isEnabled()) {
            clearPending(titleView);
            return;
        }

        // Rule 2: remember which video this row is showing, so a late response can be discarded.
        titleView.setTag(R.id.dearrow_video_id, videoId);

        // Rule 3: a result we already have is applied with no asynchronous hop, so a row that
        // scrolls back into view never visibly flips a second time.
        final DeArrowBranding cached = DeArrowCache.getInstance().getCached(videoId);
        if (!cached.isEmpty()) {
            clearPending(titleView);
            write(cached, titleView, thumbnailView);
            if (cached.getThumbnailUrl() == null) {
                renderFallbackFrame(videoId, serviceId, url, duration, live, titleView,
                        thumbnailView, config);
            }
            return;
        }

        clearPending(titleView);
        final Disposable disposable = DeArrowCache.getInstance()
                .lookup(videoId, config)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(branding -> {
                    if (!videoId.equals(titleView.getTag(R.id.dearrow_video_id))) {
                        // The holder was recycled onto a different video while we were waiting.
                        return;
                    }
                    if (!branding.isEmpty()) {
                        write(branding, titleView, thumbnailView);
                    }
                    // Most videos have no submission at all, which is exactly when the
                    // uploader's thumbnail is least trustworthy. Fall back to a frame from
                    // the video itself, as the browser extension does by default.
                    if (branding.getThumbnailUrl() == null) {
                        renderFallbackFrame(videoId, serviceId, url, duration, live, titleView,
                                thumbnailView, config);
                    }
                }, error -> {
                    // lookup() is documented never to error; this arm exists so that a future
                    // change to it cannot crash the app from a background thread.
                });
        titleView.setTag(R.id.dearrow_disposable, disposable);
    }

    /**
     * Writes the replacement into the views.
     *
     * @param branding      what to show; null fields mean "leave this view alone"
     * @param titleView     the row's title view
     * @param thumbnailView the row's thumbnail view, or null
     */
    private static void write(@NonNull final DeArrowBranding branding,
                              @NonNull final TextView titleView,
                              @Nullable final ImageView thumbnailView) {
        if (branding.getTitle() != null) {
            titleView.setText(branding.getTitle());
        }
        if (branding.getThumbnailUrl() != null && thumbnailView != null) {
            final String videoId = (String) titleView.getTag(R.id.dearrow_video_id);
            // NOT handed to the image loader directly. That URL points at the DeArrow
            // thumbnail server, which answers HTTP 204 for any frame it does not already
            // hold — and an image loader treats a 204 as a successful empty response, so it
            // paints its placeholder over a row that had a perfectly good thumbnail
            // (reported on a real device, 2026-09-27). DeArrowImageFetch only ever returns
            // bytes that really decoded; anything else leaves the row alone.
            final Disposable disposable = DeArrowImageFetch.fetch(branding.getThumbnailUrl())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(image -> {
                        if (videoId == null
                                || videoId.equals(titleView.getTag(R.id.dearrow_video_id))) {
                            thumbnailView.setImageBitmap(image);
                        }
                    }, error -> {
                        // fetch() is documented never to error; this arm only keeps a future
                        // change to it from crashing the app off a background thread.
                    }, () -> {
                        // Nothing usable at that URL — the uploader's thumbnail stays, and
                        // the frame fallback still runs, so the row is not left as-is.
                    });
            titleView.setTag(R.id.dearrow_thumbnail_disposable, disposable);
        }
    }

    /**
     * Reports whether some text on screen is the DeArrow title this stream is already showing.
     *
     * <p>Exists so that a caller which compares the displayed title against the original one to
     * decide "is this already drawn?" does not mistake a successful DeArrow replacement for a
     * stale view and redraw the page on every check.</p>
     *
     * @param info        the stream the view is showing
     * @param displayed   the text currently in the title view
     * @return true if {@code displayed} is the cached DeArrow title for this stream
     */
    public static boolean isShowing(@Nullable final StreamInfo info,
                                    @Nullable final String displayed) {
        if (info == null || displayed == null
                || info.getServiceId() != ServiceList.YouTube.getServiceId()) {
            return false;
        }
        final String videoId = DeArrowVideoId.fromUrl(info.getUrl());
        if (videoId == null) {
            return false;
        }
        return displayed.equals(DeArrowCache.getInstance().getCached(videoId).getTitle());
    }

    /**
     * Shows a frame from the video itself, for a video nobody has submitted a thumbnail for.
     *
     * <p>This is the case that covers most of YouTube. The branding API returns nothing at
     * all for an unsubmitted video, and the thumbnail server will not render one on demand,
     * so the frame has to be produced here — see {@link DeArrowFrameRenderer}.</p>
     *
     * @param videoId       the video
     * @param serviceId     its service
     * @param url           its page URL, which the renderer resolves a stream from
     * @param duration      its length in seconds
     * @param titleView     the row's title view, which carries the recycle guard
     * @param thumbnailView the view to write into; nothing happens if it is null
     * @param config        the user's settings; the fallback is skipped unless it is on
     */
    private static void renderFallbackFrame(@NonNull final String videoId,
                                            final int serviceId,
                                            @Nullable final String url,
                                            final long duration,
                                            final boolean live,
                                            @NonNull final TextView titleView,
                                            @Nullable final ImageView thumbnailView,
                                            @NonNull final DeArrowConfig config) {
        if (thumbnailView == null || url == null
                || !config.shouldReplaceThumbnails()
                || !config.shouldUseRandomFrameFallback()) {
            return;
        }
        // Either path may already have produced this frame; a cached one is painted with no
        // asynchronous hop so a row scrolling back into view never flips a second time.
        final Bitmap alreadyRendered = cachedFrame(videoId);
        if (alreadyRendered != null) {
            thumbnailView.setImageBitmap(alreadyRendered);
            return;
        }

        // THE CHEAP PATH RUNS FOR EVERY ROW, including ones the extractor calls live.
        //
        // That is deliberate and it is what makes finished broadcasts work. A stream that
        // ended is still typed LIVE_STREAM — it keeps its LIVE badge in the results list —
        // but it has been processed like any other upload, so it does have stored frames.
        // Branching on the stream type before trying them meant every archived stream was
        // sent down the expensive path and then, once that path became opt-in, skipped
        // entirely: rows that could have been replaced for one 7 KB fetch showed clickbait
        // instead (2026-09-27).
        //
        // Asking for the frames is also a more reliable test of "is this actually live"
        // than the type is: a broadcast in progress has no stored frames and answers 404.
        final Disposable disposable = DeArrowAutoThumbnail.getInstance()
                .fetch(videoId)
                // Nothing stored means a genuine broadcast, or an upload too fresh to have
                // been processed. Only the first of those needs the expensive path, and
                // only if the user asked for it.
                .switchIfEmpty(live
                        ? liveFallback(videoId, serviceId, url, config)
                        : DeArrowFrameRenderer.getInstance()
                                .render(serviceId, url, videoId, duration))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(frame -> {
                    if (videoId.equals(titleView.getTag(R.id.dearrow_video_id))) {
                        thumbnailView.setImageBitmap(frame);
                    }
                    // Otherwise the holder was recycled onto a different video while the
                    // frame was arriving, and writing it would corrupt the wrong row.
                }, error -> {
                    // Neither source is documented to error; this arm only keeps a future
                    // change to one of them from crashing the app off a background thread.
                }, () -> {
                    // Completed with no frame: the uploader's thumbnail stays, as intended.
                });
        titleView.setTag(R.id.dearrow_frame_disposable, disposable);
    }

    /**
     * A frame either thumbnail source has already produced for this video.
     *
     * @param videoId the video
     * @return the frame, or null if neither source has one
     */
    @Nullable
    private static Bitmap cachedFrame(@NonNull final String videoId) {
        final Bitmap stored = DeArrowAutoThumbnail.getInstance().getCached(videoId);
        return stored != null ? stored : DeArrowFrameRenderer.getInstance().getCached(videoId);
    }

    /**
     * The expensive live path, as a Maybe the cheap path can fall through to.
     *
     * <p>Returns an empty Maybe — doing nothing at all — unless the user has turned live
     * frames on. Every other thumbnail source here costs one small image fetch; this one
     * resolves the stream and briefly opens it, so it is its own switch and off by default.
     * See {@link DeArrowConfig#shouldUseLiveFrames()}.</p>
     *
     * @param videoId   the broadcast
     * @param serviceId its service
     * @param url       its page URL, which the renderer resolves a playable stream from
     * @param config    the user's settings
     * @return a Maybe emitting at most one frame; never errors
     */
    @NonNull
    private static Maybe<Bitmap> liveFallback(@NonNull final String videoId,
                                              final int serviceId,
                                              @NonNull final String url,
                                              @NonNull final DeArrowConfig config) {
        if (!config.shouldUseLiveFrames()) {
            return Maybe.empty();
        }
        // The thumbnail server is tried last and almost never answers — it has returned HTTP
        // 204 for every live broadcast tried across three days — but it costs one request and
        // is the only source that could serve a community-curated live frame.
        return DeArrowFrameRenderer.getInstance()
                .renderLive(serviceId, url, videoId)
                .switchIfEmpty(DeArrowLiveFrame.getInstance().fetch(videoId, config));
    }


    /** Cancels any lookup still running for a row that is being rebound. */
    private static void clearPending(@NonNull final TextView titleView) {
        dispose(titleView, R.id.dearrow_disposable);
        dispose(titleView, R.id.dearrow_frame_disposable);
        dispose(titleView, R.id.dearrow_thumbnail_disposable);
    }

    private static void dispose(@NonNull final TextView titleView, final int tagId) {
        final Object pending = titleView.getTag(tagId);
        if (pending instanceof Disposable) {
            ((Disposable) pending).dispose();
            titleView.setTag(tagId, null);
        }
    }

    /**
     * Extracts the YouTube video id a row is showing.
     *
     * @param infoItem the bound item
     * @return the 11-character video id, or null if this is not a YouTube stream (DeArrow has no
     *         data for Bilibili, NicoNico, SoundCloud or any of the other supported services)
     */
    @Nullable
    private static String videoIdOf(@Nullable final InfoItem infoItem) {
        if (!(infoItem instanceof StreamInfoItem)) {
            return null;
        }
        final StreamInfoItem item = (StreamInfoItem) infoItem;
        if (item.getServiceId() != ServiceList.YouTube.getServiceId()) {
            return null;
        }
        return DeArrowVideoId.fromUrl(item.getUrl());
    }
}
