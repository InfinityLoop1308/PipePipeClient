package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.*;
import java.util.stream.Collectors;

public final class StreamFilters {
    private StreamFilters() { }

    public static List<AudioStream> filterAudioStreamsByLanguage(
            final List<AudioStream> audioStreams, final String preferredLanguage) {
        if (audioStreams == null || audioStreams.isEmpty()) {
            return Collections.emptyList();
        }

        if ("original".equals(preferredLanguage)) {
            final List<AudioStream> originalStreams = audioStreams.stream()
                    .filter(stream -> {
                        final String trackName = stream.getAudioTrackName();
                        if (trackName == null) return true;
                        final String nameLower = trackName.toLowerCase();
                        return nameLower.contains("original") || nameLower.contains("default");
                    })
                    .collect(Collectors.toList());
            if (!originalStreams.isEmpty()) {
                return originalStreams;
            }
            final List<AudioStream> noTrackInfoStreams = audioStreams.stream()
                    .filter(stream -> stream.getAudioTrackId() == null)
                    .collect(Collectors.toList());
            if (!noTrackInfoStreams.isEmpty()) {
                return noTrackInfoStreams;
            }
            return audioStreams;
        }

        final List<AudioStream> matchedStreams = audioStreams.stream()
                .filter(stream -> {
                    final String locale = stream.getAudioLocale();
                    if (locale != null && locale.equals(preferredLanguage)) {
                        return true;
                    }
                    final String trackId = stream.getAudioTrackId();
                    if (trackId != null) {
                        final String langCode = trackId.split("\\.")[0].split("-")[0];
                        return langCode.equals(preferredLanguage);
                    }
                    return false;
                })
                .collect(Collectors.toList());

        return matchedStreams.isEmpty() ? audioStreams : matchedStreams;
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

    public static List<VideoStream> filterVideoStreamsByPreferredLanguage(
            final Context context,
            final List<VideoStream> videoStreams,
            final List<AudioStream> audioStreams) {
        final boolean hasAudioTracks = videoStreams.stream()
                .anyMatch(s -> s.getAudioTrackId() != null);
        if (!hasAudioTracks) {
            return videoStreams;
        }
        final List<AudioStream> filtered = AudioStreamSelector.getFilteredAudioStreams(context, audioStreams);
        final int defaultIdx = AudioStreamSelector.getDefaultAudioFormat(context, filtered);
        String defaultTrackId = null;
        if (defaultIdx >= 0 && defaultIdx < filtered.size()) {
            defaultTrackId = filtered.get(defaultIdx).getAudioTrackId();
        }
        if (defaultTrackId == null) {
            return collapseAudioTrackVariants(videoStreams);
        }
        final String trackId = defaultTrackId;
        final List<VideoStream> result = new ArrayList<>();
        for (final VideoStream s : videoStreams) {
            if (trackId.equals(s.getAudioTrackId()) || s.getAudioTrackId() == null) {
                result.add(s);
            }
        }
        return result.isEmpty()
                ? collapseAudioTrackVariants(videoStreams)
                : collapseAudioTrackVariants(result);
    }

    private static List<VideoStream> collapseAudioTrackVariants(final List<VideoStream> videoStreams) {
        final Map<String, VideoStream> result = new LinkedHashMap<>();
        for (final VideoStream stream : videoStreams) {
            final String key = stream.getFormat() + "|"
                    + getCodecPrefix(stream) + "|" + stream.getResolution() + "|"
                    + stream.isVideoOnly();
            final VideoStream existing = result.get(key);
            if (existing == null || shouldPreferCollapsedVariant(existing, stream)) {
                result.put(key, stream);
            }
        }
        return new ArrayList<>(result.values());
    }

    private static boolean shouldPreferCollapsedVariant(final VideoStream existing,
                                                        final VideoStream candidate) {
        if (existing.getDeliveryMethod() == DeliveryMethod.HLS
                && candidate.getDeliveryMethod() != DeliveryMethod.HLS) {
            return true;
        }
        return isOriginalAudioTrack(candidate) && !isOriginalAudioTrack(existing);
    }

    private static String getCodecPrefix(final VideoStream stream) {
        final String codec = stream.getCodec();
        if (codec == null || codec.isEmpty()) {
            return "";
        }
        return codec.split("\\.")[0];
    }

    private static boolean isOriginalAudioTrack(final VideoStream stream) {
        final String trackName = stream.getAudioTrackName();
        return trackName != null && trackName.toLowerCase(Locale.ROOT).contains("original");
    }
}
