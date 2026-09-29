package org.schabi.newpipe.util.dearrow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

/**
 * Everything {@link DeArrowBinder} needs to know about one row, in one object.
 *
 * <p>It exists because the binder now decides whether the <em>uploader's</em> thumbnail is
 * loaded at all, which means it needs the original URL and the channel identity as well as the
 * video — and a static method taking nine positional booleans and strings is how call sites end
 * up passing them in the wrong order. Every field is read-only and the factories below are the
 * only sanctioned way to build one, so a caller cannot forget the live-vs-duration trap
 * described on {@link #live}.</p>
 */
public final class DeArrowRow {

    /** The 11-character YouTube video id, or null when DeArrow has nothing to say about it. */
    @Nullable
    public final String videoId;

    /** The service the row came from; anything but YouTube disables the feature for the row. */
    public final int serviceId;

    /** The stream's page URL, which the frame renderer resolves a playable stream from. */
    @Nullable
    public final String url;

    /** Length in seconds; 0 or -1 means the frame renderer cannot pick a timestamp. */
    public final long duration;

    /**
     * Whether this is a broadcast.
     *
     * <p><b>Never inferred from the duration.</b> YouTube search results report a duration of
     * -1 for every row, live or not, so "no duration" as a liveness test sent every single
     * video down the live path and stopped frame rendering completely (2026-09-25). It comes
     * from the stream type, or from the caller who already knows.</p>
     */
    public final boolean live;

    /** The channel's URL, used to match a channel-wide exclusion. May be null on some rows. */
    @Nullable
    public final String uploaderUrl;

    /** The channel's display name — the exclusion fallback for rows with no uploader URL. */
    @Nullable
    public final String uploaderName;

    /**
     * The uploader's own thumbnail.
     *
     * <p>Present so the binder can decide <em>not</em> to load it, and still be able to fall
     * back to it when DeArrow turns out to have nothing. A null here means the caller has
     * already loaded the image itself and the binder must not touch it — which is what the
     * video detail page wants, since its thumbnail is not a list row.</p>
     */
    @Nullable
    public final String originalThumbnailUrl;

    @SuppressWarnings("checkstyle:ParameterNumber")
    private DeArrowRow(@Nullable final String videoId,
                       final int serviceId,
                       @Nullable final String url,
                       final long duration,
                       final boolean live,
                       @Nullable final String uploaderUrl,
                       @Nullable final String uploaderName,
                       @Nullable final String originalThumbnailUrl) {
        this.videoId = videoId;
        this.serviceId = serviceId;
        this.url = url;
        this.duration = duration;
        this.live = live;
        this.uploaderUrl = uploaderUrl;
        this.uploaderName = uploaderName;
        this.originalThumbnailUrl = originalThumbnailUrl;
    }

    /**
     * Builds a row from an extractor item — search results, related streams, a channel page.
     *
     * @param infoItem             the bound item; anything that is not a YouTube stream
     *                             produces a row the binder ignores
     * @param originalThumbnailUrl the uploader's thumbnail, or null if the caller has already
     *                             loaded it and the binder should leave the image alone
     * @return the row
     */
    @NonNull
    public static DeArrowRow of(@Nullable final InfoItem infoItem,
                                @Nullable final String originalThumbnailUrl) {
        if (!(infoItem instanceof StreamInfoItem)) {
            return new DeArrowRow(null, -1, null, 0, false, null, null, originalThumbnailUrl);
        }
        final StreamInfoItem item = (StreamInfoItem) infoItem;
        final boolean isYouTube = item.getServiceId() == ServiceList.YouTube.getServiceId();
        return new DeArrowRow(
                isYouTube ? DeArrowVideoId.fromUrl(item.getUrl()) : null,
                item.getServiceId(),
                item.getUrl(),
                item.getDuration(),
                item.getStreamType() == StreamType.LIVE_STREAM
                        || item.getStreamType() == StreamType.AUDIO_LIVE_STREAM,
                item.getUploaderUrl(),
                item.getUploaderName(),
                originalThumbnailUrl);
    }

    /**
     * Builds a row from a stored stream — the subscription feed and the watch history, which
     * hold a {@code StreamEntity} rather than an extractor item.
     *
     * @param serviceId            the service the stream came from
     * @param url                  the stream URL the video id is read out of
     * @param duration             length in seconds
     * @param live                 whether this is a broadcast; see {@link #live}
     * @param uploaderUrl          the channel URL, or null
     * @param uploaderName         the channel name, or null
     * @param originalThumbnailUrl the uploader's thumbnail, or null to leave the image alone
     * @return the row
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    @NonNull
    public static DeArrowRow ofStored(final int serviceId,
                                      @Nullable final String url,
                                      final long duration,
                                      final boolean live,
                                      @Nullable final String uploaderUrl,
                                      @Nullable final String uploaderName,
                                      @Nullable final String originalThumbnailUrl) {
        return new DeArrowRow(
                serviceId == ServiceList.YouTube.getServiceId()
                        ? DeArrowVideoId.fromUrl(url)
                        : null,
                serviceId, url, duration, live, uploaderUrl, uploaderName, originalThumbnailUrl);
    }
}
