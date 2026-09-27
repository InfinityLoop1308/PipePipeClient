package org.schabi.newpipe.util.dearrow;

import androidx.annotation.NonNull;

/**
 * The user's DeArrow preferences, as a plain value object.
 *
 * <p>This exists so that {@link DeArrowParser} can stay free of Android imports and therefore be
 * covered by ordinary JVM unit tests. {@link DeArrowSettings} is the adapter that builds one of
 * these from {@code SharedPreferences}.</p>
 */
public final class DeArrowConfig {

    /** The official DeArrow branding API, shared with SponsorBlock. */
    public static final String DEFAULT_API_URL = "https://sponsor.ajay.app";

    /** The official DeArrow thumbnail renderer. Separate host from the branding API. */
    public static final String DEFAULT_THUMBNAIL_API_URL =
            "https://dearrow-thumb.ajay.app/api/v1/getThumbnail";

    private final boolean enabled;
    private final boolean replaceTitles;
    private final boolean replaceThumbnails;
    private final boolean useRandomFrameFallback;
    private final boolean useLiveFrames;
    private final boolean autoFormatTitles;
    @NonNull
    private final String apiUrl;
    @NonNull
    private final String thumbnailApiUrl;

    /**
     * A config with live frames off, which is how the feature ships.
     *
     * <p>Kept as its own overload rather than folding the flag into the long constructor, so
     * that "live off" is the default at the API level too and a caller has to say so
     * deliberately to turn it on.</p>
     *
     * @param enabled                whether DeArrow does anything at all
     * @param replaceTitles          whether titles are replaced
     * @param replaceThumbnails      whether thumbnails are replaced
     * @param useRandomFrameFallback whether a frame stands in when nobody submitted a thumbnail
     * @param autoFormatTitles       whether titles are re-cased
     * @param apiUrl                 branding API base
     * @param thumbnailApiUrl        thumbnail server base
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public DeArrowConfig(final boolean enabled,
                         final boolean replaceTitles,
                         final boolean replaceThumbnails,
                         final boolean useRandomFrameFallback,
                         final boolean autoFormatTitles,
                         @NonNull final String apiUrl,
                         @NonNull final String thumbnailApiUrl) {
        this(enabled, replaceTitles, replaceThumbnails, useRandomFrameFallback, false,
                autoFormatTitles, apiUrl, thumbnailApiUrl);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DeArrowConfig(final boolean enabled,
                         final boolean replaceTitles,
                         final boolean replaceThumbnails,
                         final boolean useRandomFrameFallback,
                         final boolean useLiveFrames,
                         final boolean autoFormatTitles,
                         @NonNull final String apiUrl,
                         @NonNull final String thumbnailApiUrl) {
        this.enabled = enabled;
        this.replaceTitles = replaceTitles;
        this.replaceThumbnails = replaceThumbnails;
        this.useRandomFrameFallback = useRandomFrameFallback;
        this.useLiveFrames = useLiveFrames;
        this.autoFormatTitles = autoFormatTitles;
        this.apiUrl = stripTrailingSlash(apiUrl);
        this.thumbnailApiUrl = stripTrailingSlash(thumbnailApiUrl);
    }

    /** A config with everything on and the official endpoints — the shape most tests want. */
    public static DeArrowConfig allEnabled() {
        return new DeArrowConfig(true, true, true, true, true, true,
                DEFAULT_API_URL, DEFAULT_THUMBNAIL_API_URL);
    }

    /** The shipped default: DeArrow does nothing until the user opts in. */
    public static DeArrowConfig disabled() {
        return new DeArrowConfig(false, true, true, true, true,
                DEFAULT_API_URL, DEFAULT_THUMBNAIL_API_URL);
    }

    private static String stripTrailingSlash(@NonNull final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** @return false when no DeArrow request should be made at all. */
    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldReplaceTitles() {
        return replaceTitles;
    }

    public boolean shouldReplaceThumbnails() {
        return replaceThumbnails;
    }

    /**
     * @return true if a frame from the video may stand in when nobody has submitted a
     *         thumbnail. For an ordinary upload this costs one small image fetch from
     *         YouTube's own thumbnail host, so it is on by default.
     */
    public boolean shouldUseRandomFrameFallback() {
        return useRandomFrameFallback;
    }

    /**
     * @return true if that also applies to live broadcasts. <b>Off by default, and separate
     *         from {@link #shouldUseRandomFrameFallback()} on purpose.</b> An upload's frame
     *         is a stored image anyone can fetch; a broadcast has no stored frame at all, so
     *         the only way to get one is to resolve the stream and briefly open it — a player
     *         request plus a media decode for every live row scrolled past. That is a
     *         different order of cost and deserves its own informed opt-in.
     */
    public boolean shouldUseLiveFrames() {
        return useLiveFrames;
    }

    public boolean shouldAutoFormatTitles() {
        return autoFormatTitles;
    }

    /** @return branding API base URL, never with a trailing slash. */
    @NonNull
    public String getApiUrl() {
        return apiUrl;
    }

    /** @return full thumbnail endpoint URL, never with a trailing slash. */
    @NonNull
    public String getThumbnailApiUrl() {
        return thumbnailApiUrl;
    }
}
