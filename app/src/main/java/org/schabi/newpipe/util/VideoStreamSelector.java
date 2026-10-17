package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.*;
import java.util.stream.Collectors;

public final class VideoStreamSelector {
    private VideoStreamSelector() { }

    // Video format in order of quality. 0=lowest quality, n=highest quality
    private static final List<MediaFormat> VIDEO_FORMAT_QUALITY_RANKING =
            Arrays.asList(MediaFormat.v3GPP, MediaFormat.WEBM, MediaFormat.MPEG_4);

    // Use a HashSet for better performance
    private static final Set<String> HIGH_RESOLUTION_LIST = new HashSet<>(
            Arrays.asList("1440p", "2160p"));

    /**
     * @see #getDefaultResolutionIndex(String, String, List)
     * @param context      Android app context
     * @param videoStreams list of the video streams to check
     * @return index of the video stream with the default index
     */
    public static int getDefaultResolutionIndex(final Context context,
                                                final List<VideoStream> videoStreams) {
        final String defaultResolution = computeDefaultResolution(context,
                R.string.default_resolution_key, R.string.default_resolution_value);
        return getDefaultResolutionWithDefaultFormat(context, defaultResolution, videoStreams);
    }

    /**
     * @see #getDefaultResolutionIndex(String, String, List)
     * @param context           Android app context
     * @param videoStreams      list of the video streams to check
     * @return index of the video stream with the default index
     */
    public static int getPopupDefaultResolutionIndex(final Context context,
                                                     final List<VideoStream> videoStreams) {
        final String defaultResolution = computeDefaultResolution(context,
                R.string.default_popup_resolution_key, R.string.default_popup_resolution_value);
        return getDefaultResolutionWithDefaultFormat(context, defaultResolution, videoStreams);
    }

    /**
     * Finds the stream matching a quality selected on a previous video.
     *
     * <p>Stream list positions are not stable between videos. Match the selected resolution and
     * codec family instead, preferring an exact frame-rate variant when available, then the same
     * effective resolution, and finally the closest lower resolution.</p>
     *
     * @param targetResolution resolution selected by the user
     * @param targetCodec codec selected by the user, or {@code null} if it is unknown
     * @param videoStreams streams available for the new video
     * @return a valid stream index, or {@code -1} if the stream list is empty
     */
    public static int getResolutionAndCodecIndex(
            @NonNull final String targetResolution,
            @Nullable final String targetCodec,
            @Nullable final List<VideoStream> videoStreams) {
        if (videoStreams == null || videoStreams.isEmpty()) {
            return -1;
        }

        final String normalizedTarget = normalizeResolutionKey(targetResolution);
        final String codecFamily = codecFamilyOf(targetCodec);
        int index;

        if (codecFamily != null) {
            index = findBestVideoStreamIndex(videoStreams, targetResolution, null, codecFamily);
            if (index >= 0) {
                return index;
            }
            index = findBestVideoStreamIndex(videoStreams, null, normalizedTarget, codecFamily);
            if (index >= 0) {
                return index;
            }
        }

        index = findBestVideoStreamIndex(videoStreams, targetResolution, null, null);
        if (index >= 0) {
            return index;
        }
        index = findBestVideoStreamIndex(videoStreams, null, normalizedTarget, null);
        if (index >= 0) {
            return index;
        }

        String fallbackResolution = null;
        for (final VideoStream stream : videoStreams) {
            final String resolution = normalizeResolutionKey(
                    Objects.toString(stream.getResolution(), ""));
            if (compareVideoStreamResolution(resolution, normalizedTarget) < 0
                    && (fallbackResolution == null || compareVideoStreamResolution(
                    resolution, fallbackResolution) > 0)) {
                fallbackResolution = resolution;
            }
        }
        if (fallbackResolution == null) {
            for (final VideoStream stream : videoStreams) {
                final String resolution = normalizeResolutionKey(
                        Objects.toString(stream.getResolution(), ""));
                if (fallbackResolution == null || compareVideoStreamResolution(
                        resolution, fallbackResolution) < 0) {
                    fallbackResolution = resolution;
                }
            }
        }

        if (codecFamily != null) {
            index = findBestVideoStreamIndex(
                    videoStreams, null, fallbackResolution, codecFamily);
            if (index >= 0) {
                return index;
            }
        }
        index = findBestVideoStreamIndex(videoStreams, null, fallbackResolution, null);
        return index >= 0 ? index : 0;
    }

    /**
     * Join the two lists of video streams (video_only and normal videos),
     * and sort them according with default format chosen by the user.
     *
     * @param context                the context to search for the format to give preference
     * @param videoStreams           the normal videos list
     * @param videoOnlyStreams       the video-only stream list
     * @param ascendingOrder         true -> smallest to greatest | false -> greatest to smallest
     * @param preferVideoOnlyStreams if video-only streams should preferred when both video-only
     *                               streams and normal video streams are available
     * @return the sorted list
     */
    @NonNull
    public static List<VideoStream> getSortedStreamVideosList(
            @NonNull final Context context,
            @Nullable final List<VideoStream> videoStreams,
            @Nullable final List<VideoStream> videoOnlyStreams,
            final boolean ascendingOrder,
            final boolean preferVideoOnlyStreams) {
        final SharedPreferences sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        Set<String> advancedFormats = sharedPreferences.getStringSet(context.getString(R.string.advanced_formats_key), new HashSet<>());
        return getSortedStreamVideosList(advancedFormats, videoStreams,
                videoOnlyStreams, ascendingOrder, preferVideoOnlyStreams);
    }

    private static String computeDefaultResolution(final Context context, final int key,
                                                   final int value) {
        final SharedPreferences preferences
                = PreferenceManager.getDefaultSharedPreferences(context);

        // Load the preferred resolution otherwise the best available
        String resolution = preferences != null
                ? preferences.getString(context.getString(key), context.getString(value))
                : context.getString(R.string.best_resolution_key);

        final String maxResolution = StreamDataLimit.getResolutionLimit(context);
        if (maxResolution != null
                && (resolution.equals(context.getString(R.string.best_resolution_key))
                || compareVideoStreamResolution(maxResolution, resolution) < 1)) {
            resolution = maxResolution;
        }
        return resolution;
    }

    /**
     * Convert any 720p60/HFR/HDR/… string into a pure numeric key so they compare equal:
     * 1080p60 → 1080p, 1440p HDR → 1440p, etc.
     */
    private static String normalizeResolutionKey(@NonNull final String raw) {
        return raw.replaceAll("(?i)p.*", "p");  // CASE-insensitive removal after the "p"
    }

    /**
     * Removes resolution labels while preserving the frame rate variant.
     * For example, 1080p HDR becomes 1080p while 1080p60 HDR becomes 1080p60.
     */
    private static String normalizeResolutionVariantKey(@NonNull final String raw) {
        return raw.replaceAll("(?i)(p\\d*).*", "$1");
    }

    @Nullable
    private static String codecFamilyOf(@Nullable final String codec) {
        if (codec == null || codec.isEmpty()) {
            return null;
        }
        final String normalized = codec.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("av01")) {
            return "av1";
        }
        if (normalized.startsWith("vp09") || normalized.startsWith("vp9")) {
            return "vp9";
        }
        if (normalized.startsWith("hev1") || normalized.startsWith("hvc1")) {
            return "hevc";
        }
        if (normalized.startsWith("avc")) {
            return "avc";
        }
        final int separator = normalized.indexOf('.');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    private static int findBestVideoStreamIndex(
            @NonNull final List<VideoStream> streams,
            @Nullable final String exactResolution,
            @Nullable final String normalizedResolution,
            @Nullable final String codecFamily) {
        int bestIndex = -1;
        for (int i = 0; i < streams.size(); i++) {
            final VideoStream stream = streams.get(i);
            final String resolution = Objects.toString(stream.getResolution(), "");
            final boolean resolutionMatches = exactResolution != null
                    ? exactResolution.equals(resolution)
                    : normalizedResolution != null && normalizedResolution.equals(
                    normalizeResolutionKey(resolution));
            if (!resolutionMatches || (codecFamily != null
                    && !codecFamily.equals(codecFamilyOf(stream.getCodec())))) {
                continue;
            }
            if (bestIndex < 0 || compareVideoStreamResolution(
                    stream, streams.get(bestIndex)) > 0) {
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /**
     * Core selection logic that prefers an exact frame rate variant, then falls back to another
     * variant at the same effective resolution and finally to the closest lower resolution.
     */
    private static int getDefaultResolutionIndex(@NonNull final String targetRes,
                                                 @NonNull final String bestResolutionKey,
                                                 @Nullable final List<VideoStream> streams) {
        if (streams == null || streams.isEmpty()) {
            return -1;
        }

        // User picked the best resolution key -> simply return the highest actual stream
        if (bestResolutionKey.equals(targetRes)) {
            return streams.indexOf(
                    Collections.max(streams, VideoStreamSelector::compareVideoStreamResolution));
        }

        final String normalizedTarget = normalizeResolutionKey(targetRes);
        List<VideoStream> bucket = findMatchingStreams(
                streams, normalizeResolutionVariantKey(targetRes), normalizedTarget);

        if (bucket.isEmpty()) {
            // No match: use the closest lower resolution, or the lowest one available.
            String fallbackResolution = null;
            for (final VideoStream stream : streams) {
                final String resolution = normalizeResolutionKey(stream.getResolution());
                if (compareVideoStreamResolution(resolution, normalizedTarget) < 0
                        && (fallbackResolution == null || compareVideoStreamResolution(
                        resolution, fallbackResolution) > 0)) {
                    fallbackResolution = resolution;
                }
            }
            if (fallbackResolution == null) {
                for (final VideoStream stream : streams) {
                    final String resolution = normalizeResolutionKey(stream.getResolution());
                    if (fallbackResolution == null || compareVideoStreamResolution(
                            resolution, fallbackResolution) < 0) {
                        fallbackResolution = resolution;
                    }
                }
            }
            bucket = findMatchingStreams(streams, fallbackResolution, fallbackResolution);
        }

        bucket.sort(VideoStreamSelector::compareVideoStreamResolution);
        return streams.indexOf(bucket.get(bucket.size() - 1));
    }

    /**
     * Streams matching the exact frame rate variant, or else the same resolution ignoring
     * the frame rate suffix. Empty if neither matches.
     */
    private static List<VideoStream> findMatchingStreams(final List<VideoStream> streams,
                                                         final String variantKey,
                                                         final String resolutionKey) {
        final List<VideoStream> result = new ArrayList<>();
        for (final VideoStream stream : streams) {
            if (variantKey.equals(normalizeResolutionVariantKey(stream.getResolution()))) {
                result.add(stream);
            }
        }
        if (result.isEmpty()) {
            for (final VideoStream stream : streams) {
                if (resolutionKey.equals(normalizeResolutionKey(stream.getResolution()))) {
                    result.add(stream);
                }
            }
        }
        return result;
    }

    /**
     * Join the two lists of video streams (video_only and normal videos),
     * and sort them according with default format chosen by the user.
     *
     * @param defaultFormat          format to give preference
     * @param showHigherResolutions  show >1080p resolutions
     * @param videoStreams           normal videos list
     * @param videoOnlyStreams       video only stream list
     * @param ascendingOrder         true -> smallest to greatest | false -> greatest to smallest
     * @param preferVideoOnlyStreams if video-only streams should preferred when both video-only
     *                               streams and normal video streams are available
     * @return the sorted list
     */
    @NonNull
    private static List<VideoStream> getSortedStreamVideosList(
            final Set<String> advancedFormats,
            @Nullable final List<VideoStream> videoStreams,
            @Nullable final List<VideoStream> videoOnlyStreams,
            final boolean ascendingOrder,
            final boolean preferVideoOnlyStreams
    ) {
        boolean useWebM = advancedFormats.contains("VP9");
        boolean useAV1 = advancedFormats.contains("AV01");
        boolean useH265 = advancedFormats.contains("HEVC");
        // Determine order of streams
        // The last added list is preferred
        final List<List<VideoStream>> videoStreamsOrdered =
                preferVideoOnlyStreams
                        ? Arrays.asList(videoStreams, videoOnlyStreams)
                        : Arrays.asList(videoOnlyStreams, videoStreams);

        final List<VideoStream> allInitialStreams = videoStreamsOrdered.stream()
                // Ignore lists that are null
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                // Filter out higher resolutions (or not if high resolutions should always be shown)
                .filter(stream -> {
                    try {
                        if (stream.getFormat() == MediaFormat.WEBM) {
                            return useWebM;
                        } else if (stream.getCodec().startsWith("av01")) {
                            return useAV1;
                        } else if (stream.getCodec().startsWith("hev1") || stream.getCodec().startsWith("hvc1")) {
                            return useH265;
                        } else {
                            return true;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return true;
                })
                .collect(Collectors.toList());

        // Return the sorted list
        final HashMap<String, VideoStream> hashMap = new HashMap<>();
        for (final VideoStream videoStream : allInitialStreams) {
            final String trackSuffix = videoStream.getAudioTrackId() != null
                    ? "|" + videoStream.getAudioTrackId() : "";
            hashMap.put(videoStream.getCodec().split("\\.")[0]
                    + videoStream.getResolution() + trackSuffix, videoStream);
        }

        return sortStreamList(new ArrayList<>(hashMap.values()), ascendingOrder);
    }

    /**
     * Sort the streams list depending on the parameter ascendingOrder;
     * <p>
     * It works like that:<br>
     * - Take a string resolution, remove the letters, replace "0p60" (for 60fps videos) with "1"
     * and sort by the greatest:<br>
     * <blockquote><pre>
     *      720p     ->  720
     *      720p60   ->  721
     *      360p     ->  360
     *      1080p    ->  1080
     *      1080p60  ->  1081
     * <br>
     * ascendingOrder  ? 360 < 720 < 721 < 1080 < 1081
     * !ascendingOrder ? 1081 < 1080 < 721 < 720 < 360</pre></blockquote>
     *
     * @param videoStreams   list that the sorting will be applied
     * @param ascendingOrder true -> smallest to greatest | false -> greatest to smallest
     * @return The sorted list (same reference as parameter videoStreams)
     */
    private static List<VideoStream> sortStreamList(final List<VideoStream> videoStreams,
                                                    final boolean ascendingOrder) {
        final Comparator<VideoStream> comparator = VideoStreamSelector::compareVideoStreamResolution;
        Collections.sort(videoStreams, ascendingOrder ? comparator : comparator.reversed());
        return videoStreams;
    }

    /**
     * Fetches the desired resolution or returns the default if it is not found.
     * The resolution will be reduced if video chocking is active.
     *
     * @param context           Android app context
     * @param defaultResolution the default resolution
     * @param videoStreams      the list of video streams to check
     * @return the index of the preferred video stream
     */
    private static int getDefaultResolutionWithDefaultFormat(@NonNull final Context context,
                                                             final String defaultResolution,
                                                             final List<VideoStream> videoStreams) {
        return getDefaultResolutionIndex(defaultResolution,
                context.getString(R.string.best_resolution_key), videoStreams);
    }

    private static int calculateResolution(String x){
        int res = 0;
        if(x.contains("8K")) {
            res = 4320;
        } else if(x.contains("4K")) {
            res = 2160;
        } else if(x.contains("高帧率")) {
            res = 1083;
        } else if(x.contains("高码率")) {
            res = 1082;
        } else if (x.contains("HDR")) {
            res = 2162;
        } else if (x.contains("杜比")){
            res = 2163;
        } else if (x.contains("低画質")) {
            res = 240;
        } else{
            res = Integer.parseInt(x.replaceAll("0p\\d+$", "1")
                    .replaceAll("[^\\d.]", ""));
        }
        return res;
    }

    private static int compareVideoStreamResolution(@NonNull final String r1,
                                                    @NonNull final String r2) {

        try {
            return calculateResolution(r1) - calculateResolution(r2);
        } catch (final NumberFormatException e) {
            // Consider the first one greater because we don't know if the two streams are
            // different or not (a NumberFormatException was thrown so we don't know the resolution
            // of one stream or of all streams)
            return 1;
        }
    }

    private static int getCodecPriority(final VideoStream stream) {
        final String codecFamily = codecFamilyOf(stream.getCodec());
        if (codecFamily == null) {
            return 0;
        }
        switch (codecFamily) {
            case "av1":
                return 4;
            case "vp9":
                return 3;
            case "hevc":
                return 2;
            case "avc":
                return 1;
            default:
                return 0;
        }
    }

    // Compares the quality of two video streams.
    private static int compareVideoStreamResolution(final VideoStream streamA,
                                                    final VideoStream streamB) {
        if (streamA == null) {
            return -1;
        }
        if (streamB == null) {
            return 1;
        }

        final int resComp = compareVideoStreamResolution(streamA.getResolution(),
                streamB.getResolution());
        if (resComp != 0) {
            return resComp;
        }

        final int codecComp = getCodecPriority(streamA) - getCodecPriority(streamB);
        if (codecComp != 0) {
            return codecComp;
        }

        if (streamA.getBitrate() - streamB.getBitrate() != 0) {
            return - streamA.getBitrate() + streamB.getBitrate();
        }

        // Same bitrate and format
        return VideoStreamSelector.VIDEO_FORMAT_QUALITY_RANKING.indexOf(streamA.getFormat())
                - VideoStreamSelector.VIDEO_FORMAT_QUALITY_RANKING.indexOf(streamB.getFormat());
    }
}
