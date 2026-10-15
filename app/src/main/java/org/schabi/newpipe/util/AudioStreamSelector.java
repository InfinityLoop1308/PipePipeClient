package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;

import java.util.*;
import java.util.stream.Collectors;

public final class AudioStreamSelector {
    private AudioStreamSelector() { }

    // Audio format in order of quality. 0=lowest quality, n=highest quality
    private static final List<MediaFormat> AUDIO_FORMAT_QUALITY_RANKING =
            Arrays.asList(MediaFormat.MP3, MediaFormat.WEBMA, MediaFormat.M4A);

    // Audio format in order of efficiency. 0=most efficient, n=least efficient
    private static final List<MediaFormat> AUDIO_FORMAT_EFFICIENCY_RANKING =
            Arrays.asList(MediaFormat.WEBMA, MediaFormat.M4A, MediaFormat.MP3);

    public static int getDefaultAudioFormat(final Context context,
                                            final List<AudioStream> audioStreams) {
        if (audioStreams == null || audioStreams.isEmpty()) {
            return -1;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String preferredAudioLanguage = prefs.getString(
                context.getString(R.string.preferred_audio_language_key), "original");

        List<AudioStream> filteredStreams = StreamFilters.filterAudioStreamsByLanguage(
                audioStreams, preferredAudioLanguage);

        if (filteredStreams.isEmpty()) {
            filteredStreams = audioStreams;
        }

        final AudioStream selectedStream;
        if (StreamDataLimit.isLimitingDataUsage(context)) {
            selectedStream = getMostCompactAudioStream(null, filteredStreams);
        } else {
            selectedStream = getHighestQualityAudioStream(null, filteredStreams);
        }

        if (selectedStream == null) {
            return -1;
        }

        for (int i = 0; i < audioStreams.size(); i++) {
            if (audioStreams.get(i) == selectedStream) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private static AudioStream getHighestQualityAudioStream(@Nullable final MediaFormat format,
                                                            @Nullable final List<AudioStream> audioStreams) {
        if (audioStreams == null || audioStreams.isEmpty()) {
            return null;
        }
        return audioStreams.stream()
                .filter(audioStream -> format == null || audioStream.getFormat() == format)
                .max((s1, s2) -> compareAudioStreamBitrate(s1, s2, AUDIO_FORMAT_QUALITY_RANKING))
                .orElse(null);
    }

    @Nullable
    private static AudioStream getMostCompactAudioStream(@Nullable final MediaFormat format,
                                                         @Nullable final List<AudioStream> audioStreams) {
        if (audioStreams == null || audioStreams.isEmpty()) {
            return null;
        }
        return audioStreams.stream()
                .filter(audioStream -> format == null || audioStream.getFormat() == format)
                .min((s1, s2) -> compareAudioStreamBitrate(s1, s2, AUDIO_FORMAT_EFFICIENCY_RANKING))
                .orElse(null);
    }

    public static List<AudioStream> getFilteredAudioStreams(
            @NonNull final Context context,
            @Nullable final List<AudioStream> audioStreams) {
        if (audioStreams == null) {
            return Collections.emptyList();
        }

        final Map<String, AudioStream> collectedStreams = new LinkedHashMap<>();

        final Comparator<AudioStream> cmp = (s1, s2) ->
                compareAudioStreamBitrate(s1, s2, AUDIO_FORMAT_QUALITY_RANKING);

        for (final AudioStream stream : audioStreams) {
            if (stream.getDeliveryMethod() == DeliveryMethod.TORRENT
                    || (stream.getDeliveryMethod() == DeliveryMethod.HLS
                    && stream.getFormat() == MediaFormat.OPUS)) {
                continue;
            }

            final String trackId = Objects.toString(stream.getAudioTrackId(), "");

            final AudioStream presentStream = collectedStreams.get(trackId);
            if (presentStream == null || cmp.compare(stream, presentStream) > 0) {
                collectedStreams.put(trackId, stream);
            }
        }

        if (collectedStreams.size() > 1) {
            collectedStreams.remove("");
        }

        return collectedStreams.values().stream()
                .sorted(getAudioTrackNameComparator())
                .collect(Collectors.toList());
    }

    public static int getAudioFormatIndex(final Context context,
                                          final List<AudioStream> audioStreams,
                                          @Nullable final String trackId) {
        if (trackId != null) {
            for (int i = 0; i < audioStreams.size(); i++) {
                final AudioStream s = audioStreams.get(i);
                if (s.getAudioTrackId() != null
                        && s.getAudioTrackId().equals(trackId)) {
                    return i;
                }
            }
        }
        return getDefaultAudioFormat(context, audioStreams);
    }

    private static Comparator<AudioStream> getAudioTrackNameComparator() {
        return (s1, s2) -> {
            final String name1 = s1.getAudioTrackName() != null
                    ? s1.getAudioTrackName() : "";
            final String name2 = s2.getAudioTrackName() != null
                    ? s2.getAudioTrackName() : "";
            return name1.compareToIgnoreCase(name2);
        };
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
}
