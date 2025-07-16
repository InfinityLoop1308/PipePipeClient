package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.*;
import java.util.stream.Collectors;

public final class ListHelper {
    // Video format in order of quality. 0=lowest quality, n=highest quality
    private static final List<MediaFormat> VIDEO_FORMAT_QUALITY_RANKING =
            Arrays.asList(MediaFormat.v3GPP, MediaFormat.WEBM, MediaFormat.MPEG_4);

    // Audio format in order of quality. 0=lowest quality, n=highest quality
    private static final List<MediaFormat> AUDIO_FORMAT_QUALITY_RANKING =
            Arrays.asList(MediaFormat.MP3, MediaFormat.WEBMA, MediaFormat.M4A);
    // Audio format in order of efficiency. 0=most efficient, n=least efficient
    private static final List<MediaFormat> AUDIO_FORMAT_EFFICIENCY_RANKING =
            Arrays.asList(MediaFormat.WEBMA, MediaFormat.M4A, MediaFormat.MP3);
    // Use a HashSet for better performance
    private static final Set<String> HIGH_RESOLUTION_LIST = new HashSet<>(
            Arrays.asList("1440p", "2160p"));

    private ListHelper() { }

    /**
     * @see #getDefaultResolutionIndex(String, String, MediaFormat, List)
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
     * @see #getDefaultResolutionIndex(String, String, MediaFormat, List)
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

    public static int getDefaultAudioFormat(final Context context,
                                            final List<AudioStream> audioStreams) {
        // If the user has chosen to limit resolution to conserve mobile data
        // usage then we should also limit our audio usage.
        if (isLimitingDataUsage(context)) {
            return getMostCompactAudioIndex(null, audioStreams);
        } else {
            return getHighestQualityAudioIndex(null, audioStreams);
        }
    }

    /**
     * Return a {@link Stream} list which uses the given delivery method from a {@link Stream}
     * list.
     *
     * @param streamList     the original stream list
     * @param deliveryMethod the delivery method
     * @param <S>            the item type's class that extends {@link Stream}
     * @return a stream list which uses the given delivery method
     */
    @NonNull
    public static <S extends Stream> List<S> keepStreamsWithDelivery(
            @NonNull final List<S> streamList,
            final DeliveryMethod deliveryMethod) {
        if (streamList.isEmpty()) {
            return Collections.emptyList();
        }

        final Iterator<S> streamListIterator = streamList.iterator();
        while (streamListIterator.hasNext()) {
            if (streamListIterator.next().getDeliveryMethod() != deliveryMethod) {
                streamListIterator.remove();
            }
        }

        return streamList;
    }

    /**
     * Return a {@link Stream} list which only contains URL streams and non-torrent streams.
     *
     * @param streamList the original stream list
     * @param <S>        the item type's class that extends {@link Stream}
     * @return a stream list which only contains URL streams and non-torrent streams
     */
    @NonNull
    public static <S extends Stream> List<S> removeNonUrlAndTorrentStreams(
            @NonNull final List<S> streamList) {
        if (streamList.isEmpty()) {
            return Collections.emptyList();
        }

        final Iterator<S> streamListIterator = streamList.iterator();
        while (streamListIterator.hasNext()) {
            final S stream = streamListIterator.next();
            if (stream.getDeliveryMethod() == DeliveryMethod.TORRENT) {
                streamListIterator.remove();
            }
        }

        return streamList;
    }

    /**
     * Return a {@link Stream} list which only contains non-torrent streams.
     *
     * @param streamList the original stream list
     * @param <S>        the item type's class that extends {@link Stream}
     * @return a stream list which only contains non-torrent streams
     */
    @NonNull
    public static <S extends Stream> List<S> removeTorrentStreams(
            @NonNull final List<S> streamList) {
        if (streamList.isEmpty()) {
            return Collections.emptyList();
        }

        final Iterator<S> streamListIterator = streamList.iterator();
        while (streamListIterator.hasNext()) {
            final S stream = streamListIterator.next();
            if (stream.getDeliveryMethod() == DeliveryMethod.TORRENT) {
                streamListIterator.remove();
            }
        }

        return streamList;
    }

    public static List<AudioStream> filterUnsupportedFormats(@NonNull final List<AudioStream> streamList,
                                                             @NonNull final Context context) {
        final SharedPreferences sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        Set<String> advancedFormats = sharedPreferences.getStringSet(context.getString(R.string.advanced_formats_key), new HashSet<>());
        boolean useDolbyAudio = advancedFormats.contains("EC-3");
        return streamList.stream()
                .filter(stream -> {
                    if (stream.getCodec() == null) {
                        return true;
                    }
                    if (stream.getCodec().toLowerCase(Locale.ROOT).equals("flac")) {
                        return false; // flac support has issue: InsufficientCapacityException, at least for BiliBili
                    } else if (stream.getCodec().equals("ec-3")) {
                        return useDolbyAudio;
                    } else {
                        return true;
                    }
                }).collect(Collectors.toList());
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

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private static String computeDefaultResolution(final Context context, final int key,
                                                   final int value) {
        final SharedPreferences preferences
                = PreferenceManager.getDefaultSharedPreferences(context);

        // Load the preferred resolution otherwise the best available
        String resolution = preferences != null
                ? preferences.getString(context.getString(key), context.getString(value))
                : context.getString(R.string.best_resolution_key);

        final String maxResolution = getResolutionLimit(context);
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
     * Core selection logic that groups by *effective* resolution (ignoring suffixes),
     * then selects inside each bucket via bitrate / codec-rank.
     */
    static int getDefaultResolutionIndex(@NonNull final String targetRes,
                                         @NonNull final String bestResolutionKey,
                                         @Nullable final MediaFormat filterFormat,
                                         @Nullable final List<VideoStream> streams) {

        if (streams == null || streams.isEmpty()) {
            return -1;
        }

        // 1. User picked the best resolution key → simply return the highest actual stream
        if (bestResolutionKey.equals(targetRes)) {
            return streams.indexOf(
                    Collections.max(streams, ListHelper::compareVideoStreamResolution));
        }

        // 2. Strip suffixes to group variants together:   1080p HDR → 1080p
        final String normalizedTarget = normalizeResolutionKey(targetRes);

        // 3. Build the bucket of streams that share the same effective resolution
        List<VideoStream> bucket = new ArrayList<>();
        for (VideoStream s : streams) {
            if (normalizedTarget.equals(normalizeResolutionKey(s.getResolution()))) {
                if (filterFormat == null || s.getFormat() == filterFormat) {
                    bucket.add(s);
                }
            }
        }

        // 4. No exact format match? Drop the format filter and use everything.
        if (bucket.isEmpty() && filterFormat != null) {
            for (VideoStream s : streams) {
                if (normalizedTarget.equals(normalizeResolutionKey(s.getResolution()))) {
                    bucket.add(s);
                }
            }
        }

        // 5. No candidates: fall back to highest available
        if (bucket.isEmpty()) {
            return 0;
        }

        // 6. Highest quality in the bucket
        bucket.sort(ListHelper::compareVideoStreamResolution);
        final VideoStream best = bucket.get(bucket.size() - 1);
        return streams.indexOf(best);
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
    static List<VideoStream> getSortedStreamVideosList(
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
        // Add all to the hashmap
        for (final VideoStream videoStream : allInitialStreams) {
            hashMap.put(videoStream.getCodec().split("\\.")[0] + videoStream.getResolution(), videoStream);
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
        final Comparator<VideoStream> comparator = ListHelper::compareVideoStreamResolution;
        Collections.sort(videoStreams, ascendingOrder ? comparator : comparator.reversed());
        return videoStreams;
    }

    /**
     * Get the audio from the list with the highest quality.
     * Format will be ignored if it yields no results.
     *
     * @param format       The target format type or null if it doesn't matter
     * @param audioStreams List of audio streams
     * @return Index of audio stream that produces the most compact results or -1 if not found
     */
    static int getHighestQualityAudioIndex(@Nullable final MediaFormat format,
                                           @Nullable final List<AudioStream> audioStreams) {
        return getAudioIndexByHighestRank(format, audioStreams,
                // Compares descending (last = highest rank)
                (s1, s2) -> compareAudioStreamBitrate(s1, s2, AUDIO_FORMAT_QUALITY_RANKING)
        );
    }

    /**
     * Get the audio from the list with the lowest bitrate and most efficient format.
     * Format will be ignored if it yields no results.
     *
     * @param format       The target format type or null if it doesn't matter
     * @param audioStreams List of audio streams
     * @return Index of audio stream that produces the most compact results or -1 if not found
     */
    static int getMostCompactAudioIndex(@Nullable final MediaFormat format,
                                        @Nullable final List<AudioStream> audioStreams) {

        return getAudioIndexByHighestRank(format, audioStreams,
                // The "-" is important -> Compares ascending (first = highest rank)
                (s1, s2) -> -compareAudioStreamBitrate(s1, s2, AUDIO_FORMAT_EFFICIENCY_RANKING)
        );
    }

    /**
     * Get the audio-stream from the list with the highest rank, depending on the comparator.
     * Format will be ignored if it yields no results.
     *
     * @param targetedFormat The target format type or null if it doesn't matter
     * @param audioStreams   List of audio streams
     * @param comparator     The comparator used for determining the max/best/highest ranked value
     * @return Index of audio stream that produces the highest ranked result or -1 if not found
     */
    private static int getAudioIndexByHighestRank(@Nullable final MediaFormat targetedFormat,
                                                  @Nullable final List<AudioStream> audioStreams,
                                                  final Comparator<AudioStream> comparator) {
        if (audioStreams == null || audioStreams.isEmpty()) {
            return -1;
        }

        final AudioStream highestRankedAudioStream = audioStreams.stream()
                .filter(audioStream -> targetedFormat == null
                        || audioStream.getFormat() == targetedFormat)
                .max(comparator)
                .orElse(null);

        if (highestRankedAudioStream == null) {
            // Fallback: Ignore targetedFormat if not null
            if (targetedFormat != null) {
                return getAudioIndexByHighestRank(null, audioStreams, comparator);
            }
            // targetedFormat is already null -> return -1
            return -1;
        }

        return audioStreams.indexOf(highestRankedAudioStream);
    }

    /**
     * Locates a possible match for the given resolution and format in the provided list.
     *
     * <p>In this order:</p>
     *
     * <ol>
     * <li>Find a format and resolution match</li>
     * <li>Find a format and resolution match and ignore the refresh</li>
     * <li>Find a resolution match</li>
     * <li>Find a resolution match and ignore the refresh</li>
     * <li>Find a resolution just below the requested resolution and ignore the refresh</li>
     * <li>Give up</li>
     * </ol>
     *
     * @param targetResolution the resolution to look for
     * @param targetFormat     the format to look for
     * @param videoStreams     the available video streams
     * @return the index of the preferred video stream
     */
    static int getVideoStreamIndex(@NonNull final String targetResolution,
                                   final MediaFormat targetFormat,
                                   @NonNull final List<VideoStream> videoStreams) {
        int fullMatchIndex = -1;
        int fullMatchNoRefreshIndex = -1;
        int resMatchOnlyIndex = -1;
        int resMatchOnlyNoRefreshIndex = -1;
        int lowerResMatchNoRefreshIndex = -1;
        final String targetResolutionNoRefresh = targetResolution.replaceAll("p\\d+$", "p");

        for (int idx = 0; idx < videoStreams.size(); idx++) {
            final MediaFormat format
                    = targetFormat == null ? null : videoStreams.get(idx).getFormat();
            final String resolution = videoStreams.get(idx).getResolution();
            final String resolutionNoRefresh = resolution.replaceAll("p\\d+$", "p");

            if (format == targetFormat && calculateResolution(resolution) == calculateResolution(targetResolution)) {
                fullMatchIndex = idx;
            }

            if (format == targetFormat && calculateResolution(resolutionNoRefresh) == calculateResolution(targetResolutionNoRefresh)) {
                fullMatchNoRefreshIndex = idx;
            }

            if (resMatchOnlyIndex == -1 && calculateResolution(resolution) == calculateResolution(targetResolution)) {
                resMatchOnlyIndex = idx;
            }

            if (resMatchOnlyNoRefreshIndex == -1
                    && calculateResolution(resolutionNoRefresh) == calculateResolution(targetResolutionNoRefresh)) {
                resMatchOnlyNoRefreshIndex = idx;
            }

            if (lowerResMatchNoRefreshIndex == -1 && compareVideoStreamResolution(
                    resolutionNoRefresh, targetResolutionNoRefresh) < 0) {
                lowerResMatchNoRefreshIndex = idx;
            }
        }

        if (fullMatchIndex != -1) {
            return fullMatchIndex;
        }
        if (fullMatchNoRefreshIndex != -1) {
            return fullMatchNoRefreshIndex;
        }
        if (resMatchOnlyIndex != -1) {
            return resMatchOnlyIndex;
        }
        if (resMatchOnlyNoRefreshIndex != -1) {
            return resMatchOnlyNoRefreshIndex;
        }
        if (lowerResMatchNoRefreshIndex != -1) {
            return lowerResMatchNoRefreshIndex;
        }
        return videoStreams.size() - 1;
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
//        final MediaFormat defaultFormat = MediaFormat.MPEG_4;
        return getDefaultResolutionIndex(defaultResolution,
                context.getString(R.string.best_resolution_key), null, videoStreams);
    }

    private static MediaFormat getDefaultFormat(@NonNull final Context context,
                                                @StringRes final int defaultFormatKey,
                                                @StringRes final int defaultFormatValueKey) {
        final SharedPreferences preferences
                = PreferenceManager.getDefaultSharedPreferences(context);

        final String defaultFormat = context.getString(defaultFormatValueKey);
        final String defaultFormatString = preferences.getString(
                context.getString(defaultFormatKey), defaultFormat);

        MediaFormat defaultMediaFormat = getMediaFormatFromKey(context, defaultFormatString);
        if (defaultMediaFormat == null) {
            preferences.edit().putString(context.getString(defaultFormatKey), defaultFormat)
                    .apply();
            defaultMediaFormat = getMediaFormatFromKey(context, defaultFormat);
        }

        return defaultMediaFormat;
    }

    private static MediaFormat getMediaFormatFromKey(@NonNull final Context context,
                                                     @NonNull final String formatKey) {
        MediaFormat format = null;
        if (formatKey.equals(context.getString(R.string.video_webm_key))) {
            format = MediaFormat.WEBM;
        } else if (formatKey.equals(context.getString(R.string.video_mp4_key))) {
            format = MediaFormat.MPEG_4;
        } else if (formatKey.equals(context.getString(R.string.video_3gp_key))) {
            format = MediaFormat.v3GPP;
        } else if (formatKey.equals(context.getString(R.string.audio_webm_key))) {
            format = MediaFormat.WEBMA;
        } else if (formatKey.equals(context.getString(R.string.audio_m4a_key))) {
            format = MediaFormat.M4A;
        }
        return format;
    }

    // Compares the quality of two audio streams
    private static int compareAudioStreamBitrate(final AudioStream streamA,
                                                 final AudioStream streamB,
                                                 final List<MediaFormat> formatRanking) {
        if (streamA == null) {
            return -1;
        }
        if (streamB == null) {
            return 1;
        }
        if (streamA.getAverageBitrate() < streamB.getAverageBitrate()) {
            return -1;
        }
        if (streamA.getAverageBitrate() > streamB.getAverageBitrate()) {
            return 1;
        }

        // Same bitrate and format
        return formatRanking.indexOf(streamA.getFormat())
                - formatRanking.indexOf(streamB.getFormat());
    }

    public static int calculateResolution(String x){
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

        if (streamA.getBitrate() - streamB.getBitrate() != 0) {
            return - streamA.getBitrate() + streamB.getBitrate();
        }

        // Same bitrate and format
        return ListHelper.VIDEO_FORMAT_QUALITY_RANKING.indexOf(streamA.getFormat())
                - ListHelper.VIDEO_FORMAT_QUALITY_RANKING.indexOf(streamB.getFormat());
    }


    private static boolean isLimitingDataUsage(final Context context) {
        return getResolutionLimit(context) != null;
    }

    /**
     * The maximum resolution allowed.
     *
     * @param context App context
     * @return maximum resolution allowed or null if there is no maximum
     */
    private static String getResolutionLimit(@NonNull final Context context) {
        String resolutionLimit = null;
        if (isMeteredNetwork(context)) {
            final SharedPreferences preferences
                    = PreferenceManager.getDefaultSharedPreferences(context);
            final String defValue = context.getString(R.string.limit_data_usage_none_key);
            final String value = preferences.getString(
                    context.getString(R.string.limit_mobile_data_usage_key), defValue);
            resolutionLimit = defValue.equals(value) ? null : value;
        }
        return resolutionLimit;
    }

    /**
     * The current network is metered (like mobile data)?
     *
     * @param context App context
     * @return {@code true} if connected to a metered network
     */
    public static boolean isMeteredNetwork(@NonNull final Context context) {
        final ConnectivityManager manager
                = ContextCompat.getSystemService(context, ConnectivityManager.class);
        if (manager == null || manager.getActiveNetworkInfo() == null) {
            return false;
        }

        return manager.isActiveNetworkMetered();
    }
}
