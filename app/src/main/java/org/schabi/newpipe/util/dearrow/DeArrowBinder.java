package org.schabi.newpipe.util.dearrow;

import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.util.PicassoHelper;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Applies DeArrow branding to a list row.
 *
 * <p>This is the class that answers the long-standing objection to DeArrow on Android — that
 * swapping titles and thumbnails after the fact cannot be done seamlessly. It can, provided
 * these rules are never broken, and they are enforced here rather than left to each call
 * site:</p>
 *
 * <ol>
 *   <li><b>The original TITLE is bound first, synchronously, always.</b> {@link #bind} is
 *       called <em>after</em> the row has already been populated with the uploader's title, so
 *       a DeArrow lookup can never delay a bind or leave a row's text blank.</li>
 *   <li><b>The original THUMBNAIL is this class's decision, not the call site's.</b> See
 *       below — this is the one place the original design was wrong in practice.</li>
 *   <li><b>A replacement is applied only if the row is still showing the same video.</b>
 *       RecyclerView reuses holders aggressively; the video id is stored on the view and
 *       re-checked on the main thread before anything is written, so a slow response for a
 *       scrolled-away row is discarded instead of corrupting the row that replaced it.</li>
 *   <li><b>A cached result is applied synchronously, with no asynchronous step at all.</b>
 *       After the first pass over a list, scrolling back and forth shows honest titles
 *       immediately — the visible flip only ever happens once per video.</li>
 *   <li><b>An excluded video or channel is left completely alone</b> — no lookup, no frame,
 *       no request. See {@link DeArrowExclusions}.</li>
 * </ol>
 *
 * <h2>Why the thumbnail load moved in here</h2>
 *
 * <p>Originally every call site loaded the uploader's thumbnail itself and then asked this
 * class for a replacement, so a row was never blank. That is correct and it reads badly: on a
 * first pass through a list, every row shows the clickbait image, and then flips. The user
 * sees precisely the thing the feature exists to hide, on every row, every time.</p>
 *
 * <p>So the call sites now hand over the uploader's URL instead of loading it, and this class
 * loads <em>one</em> image per row — the right one. When DeArrow turns out to have nothing
 * (a broadcast in progress, an upload too fresh to have been processed) the uploader's image
 * is loaded at that point, which costs a placeholder for the length of one 7 KB fetch. That
 * miss is remembered by {@link DeArrowFrameCache}, so it is paid once per video rather than on
 * every rebind. A user who prefers the old behaviour turns
 * {@link DeArrowConfig#shouldSkipOriginalThumbnail()} off.</p>
 *
 * <p>A failure at any point is a no-op: the row keeps what YouTube gave it.</p>
 */
public final class DeArrowBinder {

    private DeArrowBinder() {
    }

    /**
     * Binds a row's title and thumbnail, replacing either with DeArrow's where it can.
     *
     * @param row           what the row is showing; build it with {@link DeArrowRow#of} or
     *                      {@link DeArrowRow#ofStored}
     * @param titleView     the row's title view, already showing the uploader's title
     * @param thumbnailView the row's thumbnail view, or null for rows that show no image
     */
    public static void bind(@NonNull final DeArrowRow row,
                            @NonNull final TextView titleView,
                            @Nullable final ImageView thumbnailView) {
        clearPending(titleView);

        final DeArrowConfig config = DeArrowSettings.read(titleView.getContext());
        final boolean active = row.videoId != null
                && config.isEnabled()
                && !DeArrowExclusions.getInstance(titleView.getContext())
                        .isExcluded(row.videoId, row.uploaderUrl, row.uploaderName);

        if (!active) {
            titleView.setTag(R.id.dearrow_video_id, null);
            loadOriginalThumbnail(row, titleView, thumbnailView);
            return;
        }

        // Rule 3: remember which video this row is showing, so a late response can be discarded.
        titleView.setTag(R.id.dearrow_video_id, row.videoId);
        bindThumbnail(row, titleView, thumbnailView, config);
        bindTitle(row, titleView, thumbnailView, config);
    }

    /**
     * Decides which image the row loads, and loads it.
     *
     * <p>Four outcomes, in order of preference: a DeArrow frame we already hold, painted with
     * no asynchronous hop at all; a frame we are about to fetch, with nothing loaded in the
     * meantime; the uploader's image, when the user has asked to keep the old behaviour or
     * thumbnails are not being replaced; and the uploader's image again, later, if the frame
     * never arrives.</p>
     */
    private static void bindThumbnail(@NonNull final DeArrowRow row,
                                      @NonNull final TextView titleView,
                                      @Nullable final ImageView thumbnailView,
                                      @NonNull final DeArrowConfig config) {
        titleView.setTag(R.id.dearrow_original_pending, Boolean.FALSE);
        if (thumbnailView == null || row.videoId == null) {
            return;
        }
        if (!config.shouldReplaceThumbnails()) {
            loadOriginalThumbnail(row, titleView, thumbnailView);
            return;
        }

        final Bitmap alreadyRendered = cachedFrame(row.videoId);
        if (alreadyRendered != null) {
            paint(thumbnailView, alreadyRendered);
            return;
        }

        // Nothing cached. Either the row holds the placeholder and waits for the real image,
        // or it falls back to the old load-then-flip behaviour — never both, because loading
        // the original here is exactly what produces the flip.
        //
        // A video already known to have no stored frame does not qualify: holding an empty
        // row for a fetch that is going to 404 again is strictly worse than showing the
        // uploader's image immediately.
        //
        // A row with no original URL is one whose caller loads the image itself — the video
        // detail page. Blanking that to a placeholder would be a one-way trip, because there
        // is nothing here to restore it with if the frame never arrives.
        final boolean canProduceFrame = config.shouldUseRandomFrameFallback()
                && !DeArrowAutoThumbnail.getInstance().isKnownMiss(row.videoId);
        if (config.shouldSkipOriginalThumbnail() && canProduceFrame
                && row.originalThumbnailUrl != null) {
            // Held deliberately: the placeholder, not the clickbait, until the frame lands.
            PicassoHelper.cancelRequest(thumbnailView);
            thumbnailView.setImageResource(R.drawable.dummy_thumbnail);
            titleView.setTag(R.id.dearrow_original_pending, Boolean.TRUE);
        } else {
            loadOriginalThumbnail(row, titleView, thumbnailView);
        }
        renderFallbackFrame(row, titleView, thumbnailView, config);
    }

    /** Looks up the honest title, and the community thumbnail if there is one. */
    private static void bindTitle(@NonNull final DeArrowRow row,
                                  @NonNull final TextView titleView,
                                  @Nullable final ImageView thumbnailView,
                                  @NonNull final DeArrowConfig config) {
        final String videoId = row.videoId;
        if (videoId == null) {
            return;
        }

        // Rule 4: a result we already have is applied with no asynchronous hop, so a row that
        // scrolls back into view never visibly flips a second time.
        final DeArrowBranding cached = DeArrowCache.getInstance().getCached(videoId);
        if (!cached.isEmpty()) {
            write(cached, row, titleView, thumbnailView);
            return;
        }

        final Disposable disposable = DeArrowCache.getInstance()
                .lookup(videoId, config)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(branding -> {
                    if (!videoId.equals(titleView.getTag(R.id.dearrow_video_id))) {
                        // The holder was recycled onto a different video while we were waiting.
                        return;
                    }
                    write(branding, row, titleView, thumbnailView);
                }, error -> {
                    // lookup() is documented never to error; this arm exists so that a future
                    // change to it cannot crash the app from a background thread.
                });
        titleView.setTag(R.id.dearrow_disposable, disposable);
    }

    /**
     * Writes a branding result into the views.
     *
     * @param branding      what to show; null fields mean "this source had nothing"
     * @param row           the row being bound
     * @param titleView     the row's title view
     * @param thumbnailView the row's thumbnail view, or null
     */
    private static void write(@NonNull final DeArrowBranding branding,
                              @NonNull final DeArrowRow row,
                              @NonNull final TextView titleView,
                              @Nullable final ImageView thumbnailView) {
        if (branding.getTitle() != null) {
            titleView.setText(branding.getTitle());
        }
        if (branding.getThumbnailUrl() == null || thumbnailView == null) {
            return;
        }
        final String videoId = row.videoId;
        // NOT handed to the image loader directly. That URL points at the DeArrow thumbnail
        // server, which answers HTTP 204 for any frame it does not already hold — and an
        // image loader treats a 204 as a successful empty response, so it paints its
        // placeholder over a row that had a perfectly good thumbnail (reported on a real
        // device, 2026-09-27). DeArrowImageFetch only ever returns bytes that really decoded.
        final Disposable disposable = DeArrowImageFetch.fetch(branding.getThumbnailUrl())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(image -> {
                    if (videoId == null
                            || videoId.equals(titleView.getTag(R.id.dearrow_video_id))) {
                        paint(thumbnailView, image);
                    }
                }, error -> {
                    // fetch() is documented never to error; this arm only keeps a future
                    // change to it from crashing the app off a background thread.
                }, () -> {
                    // Nothing usable at that URL — and nothing to do about it here, because
                    // bindThumbnail has already started the frame path for this row. Its
                    // terminal arm is what loads the uploader's image if that comes up empty
                    // too, so the row cannot be left holding the placeholder.
                });
        titleView.setTag(R.id.dearrow_thumbnail_disposable, disposable);
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
     */
    private static void renderFallbackFrame(@NonNull final DeArrowRow row,
                                            @NonNull final TextView titleView,
                                            @Nullable final ImageView thumbnailView,
                                            @NonNull final DeArrowConfig config) {
        final String videoId = row.videoId;
        if (thumbnailView == null || videoId == null || row.url == null
                || !config.shouldReplaceThumbnails()
                || !config.shouldUseRandomFrameFallback()) {
            if (originalStillOwed(titleView)) {
                loadOriginalThumbnail(row, titleView, thumbnailView);
            }
            return;
        }
        // Either path may already have produced this frame; a cached one is painted with no
        // asynchronous hop so a row scrolling back into view never flips a second time.
        final Bitmap alreadyRendered = cachedFrame(videoId);
        if (alreadyRendered != null) {
            paint(thumbnailView, alreadyRendered);
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
                //
                // Maybe.defer is load-bearing, not tidiness. switchIfEmpty takes a VALUE, so
                // without it the expensive chain is built on every single bind even when the
                // cheap path is about to succeed — and building it is not free: render() and
                // renderLive() register themselves in an inFlight map as a side effect of
                // being constructed. A chain that is never subscribed never runs its
                // doFinally, so that entry is never removed. Browsing a few thousand feed
                // items left a few thousand cold Rx chains pinned in an unbounded map that
                // nothing ever pruned.
                .switchIfEmpty(Maybe.defer(() -> {
                    // BOTH branches below decode video, and both are behind the same opt-in.
                    //
                    // Gating only the live one was a real hole: a non-live row whose stored
                    // frames 404 is an upload too fresh to have been processed — which is
                    // exactly what a subscription feed is made of — and it fell through to
                    // the renderer with nothing but the default-ON frame-fallback switch in
                    // front of it. Enabling DeArrow with defaults and opening the feed then
                    // cost a full extraction plus partial video download per fresh upload,
                    // while the setting that was supposed to prevent that only covered live
                    // (2026-09-27). With this, the default really is "one small image fetch,
                    // never more".
                    if (!config.shouldUseLiveFrames()) {
                        return Maybe.empty();
                    }
                    return row.live
                            ? liveFallback(row, config)
                            : DeArrowFrameRenderer.getInstance()
                                    .render(row.serviceId, row.url, videoId, row.duration);
                }))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(frame -> {
                    if (bindingStillCurrent(row, titleView)) {
                        paint(thumbnailView, frame);
                    }
                    // Otherwise the holder was recycled onto a different video while the
                    // frame was arriving, and writing it would corrupt the wrong row.
                }, error -> {
                    // Neither source is documented to error; this arm only keeps a future
                    // change to one of them from crashing the app off a background thread.
                    if (bindingStillCurrent(row, titleView) && originalStillOwed(titleView)) {
                        loadOriginalThumbnail(row, titleView, thumbnailView);
                    }
                }, () -> {
                    // No frame for this video. If the original was never loaded, this is the
                    // moment it has to be — the row is still showing a placeholder.
                    if (bindingStillCurrent(row, titleView) && originalStillOwed(titleView)) {
                        loadOriginalThumbnail(row, titleView, thumbnailView);
                    }
                });
        titleView.setTag(R.id.dearrow_frame_disposable, disposable);
    }

    /**
     * @param row       the row a pending result belongs to
     * @param titleView the view carrying the recycle guard
     * @return whether that view is still showing this row's video
     */
    private static boolean bindingStillCurrent(@NonNull final DeArrowRow row,
                                               @NonNull final TextView titleView) {
        return row.videoId != null
                && row.videoId.equals(titleView.getTag(R.id.dearrow_video_id));
    }

    /**
     * Loads the uploader's own thumbnail.
     *
     * <p>A null {@code originalThumbnailUrl} means the call site loaded the image itself and
     * this class must not touch it — the video detail page, whose thumbnail is not a list
     * row. Everywhere else this is the fallback, and it is deliberately the only place the
     * uploader's image is ever requested.</p>
     */
    private static void loadOriginalThumbnail(@NonNull final DeArrowRow row,
                                              @NonNull final TextView titleView,
                                              @Nullable final ImageView thumbnailView) {
        titleView.setTag(R.id.dearrow_original_pending, Boolean.FALSE);
        if (thumbnailView == null || row.originalThumbnailUrl == null) {
            return;
        }
        PicassoHelper.loadScaledDownThumbnail(thumbnailView.getContext(),
                row.originalThumbnailUrl).into(thumbnailView);
    }

    /**
     * @param titleView the view carrying the recycle guard
     * @return whether this row is still holding the placeholder waiting for a DeArrow image,
     *         and therefore still owes itself the uploader's one if nothing arrives
     */
    private static boolean originalStillOwed(@NonNull final TextView titleView) {
        return Boolean.TRUE.equals(titleView.getTag(R.id.dearrow_original_pending));
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
     * @param row    the broadcast's row
     * @param config the user's settings
     * @return a Maybe emitting at most one frame; never errors
     */
    @NonNull
    private static Maybe<Bitmap> liveFallback(@NonNull final DeArrowRow row,
                                              @NonNull final DeArrowConfig config) {
        if (!config.shouldUseLiveFrames() || row.url == null || row.videoId == null) {
            return Maybe.empty();
        }
        final String videoId = row.videoId;
        // The thumbnail server is tried last and almost never answers — it has returned HTTP
        // 204 for every live broadcast tried across three days — but it costs one request and
        // is the only source that could serve a community-curated live frame.
        return DeArrowFrameRenderer.getInstance()
                .renderLive(row.serviceId, row.url, videoId)
                .switchIfEmpty(Maybe.defer(
                        () -> DeArrowLiveFrame.getInstance().fetch(videoId, config)));
    }


    /**
     * Writes a frame into a row, and makes it stick.
     *
     * <p><b>Cancelling the pending image request is the whole point.</b> A row may still have
     * an image load in flight — from a previous bind of the recycled holder, or from the
     * fallback path — and writing a bitmap directly does not cancel it, so the row would show
     * the honest frame and then visibly flip <em>back</em> as the other load finished and
     * painted over it.</p>
     *
     * @param thumbnailView the row's thumbnail view
     * @param frame         the frame to show
     */
    private static void paint(@NonNull final ImageView thumbnailView,
                              @NonNull final Bitmap frame) {
        PicassoHelper.cancelRequest(thumbnailView);
        thumbnailView.setImageBitmap(frame);
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
     * The pre-{@link DeArrowRow} entry point, for call sites that load the thumbnail
     * themselves.
     *
     * @param infoItem      the bound item
     * @param titleView     the row's title view
     * @param thumbnailView the row's thumbnail view, or null
     */
    public static void apply(@Nullable final InfoItem infoItem,
                             @NonNull final TextView titleView,
                             @Nullable final ImageView thumbnailView) {
        bind(DeArrowRow.of(infoItem, null), titleView, thumbnailView);
    }

    /**
     * The same, for rows built from a stored stream rather than an extractor item.
     *
     * @param serviceId     the service the stream came from
     * @param url           the stream URL the video id is read out of
     * @param duration      the video's length in seconds
     * @param live          whether this is a live broadcast; see {@link DeArrowRow#live}
     * @param titleView     the row's title view
     * @param thumbnailView the row's thumbnail view, or null
     */
    public static void apply(final int serviceId,
                             @Nullable final String url,
                             final long duration,
                             final boolean live,
                             @NonNull final TextView titleView,
                             @Nullable final ImageView thumbnailView) {
        bind(DeArrowRow.ofStored(serviceId, url, duration, live, null, null, null),
                titleView, thumbnailView);
    }
}
