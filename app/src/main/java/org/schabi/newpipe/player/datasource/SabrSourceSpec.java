package org.schabi.newpipe.player.datasource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Source metadata for one video format and one Media3-selectable audio codec group. */
public final class SabrSourceSpec {
    @NonNull private final String videoId;
    @NonNull private final YoutubeSabrInfo info;
    @NonNull private final YoutubeSabrInfo.Format bootstrapAudioFormat;
    @NonNull private final List<YoutubeSabrInfo.Format> audioFormats;
    @NonNull private final YoutubeSabrInfo.Format videoFormat;
    @NonNull private final Map<String, YoutubeSabrInfo.Format> formatsByKey;
    @NonNull private final Map<YoutubeSabrInfo.Format, String> keysByFormat;
    @NonNull private final Map<YoutubeSabrInfo.Format, byte[]> initializationData =
            new ConcurrentHashMap<>();
    @NonNull private final YoutubeSabrFormatTimeline audioTimeline;
    @NonNull private final YoutubeSabrFormatTimeline videoTimeline;
    @NonNull private final AtomicReference<List<SabrMediaSegment>> bootstrapMediaSegments;

    SabrSourceSpec(@NonNull final String videoId,
                   @NonNull final YoutubeSabrInfo info,
                   @NonNull final YoutubeSabrInfo.Format bootstrapAudioFormat,
                   @NonNull final List<YoutubeSabrInfo.Format> audioFormats,
                   @NonNull final YoutubeSabrInfo.Format videoFormat,
                   @NonNull final byte[] audioInitializationData,
                   @NonNull final byte[] videoInitializationData,
                   @NonNull final YoutubeSabrFormatTimeline audioTimeline,
                   @NonNull final YoutubeSabrFormatTimeline videoTimeline,
                   @NonNull final List<SabrMediaSegment> bootstrapMediaSegments) {
        if (audioFormats.isEmpty() || !audioFormats.contains(bootstrapAudioFormat)) {
            throw new IllegalArgumentException("SABR audio codec group is empty");
        }
        this.videoId = videoId;
        this.info = info;
        this.bootstrapAudioFormat = bootstrapAudioFormat;
        this.audioFormats = Collections.unmodifiableList(new ArrayList<>(audioFormats));
        this.videoFormat = videoFormat;
        final Map<String, YoutubeSabrInfo.Format> byKey = new LinkedHashMap<>();
        final Map<YoutubeSabrInfo.Format, String> byFormat = new ConcurrentHashMap<>();
        byKey.put("v", videoFormat);
        byFormat.put(videoFormat, "v");
        for (int i = 0; i < audioFormats.size(); i++) {
            final String key = "a" + i;
            byKey.put(key, audioFormats.get(i));
            byFormat.put(audioFormats.get(i), key);
        }
        formatsByKey = Collections.unmodifiableMap(byKey);
        keysByFormat = Collections.unmodifiableMap(byFormat);
        this.audioTimeline = audioTimeline;
        this.videoTimeline = videoTimeline;
        this.bootstrapMediaSegments = new AtomicReference<>(bootstrapMediaSegments);
        putInitializationData(bootstrapAudioFormat, audioInitializationData);
        putInitializationData(videoFormat, videoInitializationData);
    }

    @NonNull public String getVideoId() { return videoId; }
    @NonNull public YoutubeSabrInfo getInfo() { return info; }
    @NonNull
    public YoutubeSabrInfo.Format getBootstrapAudioFormat() {
        return bootstrapAudioFormat;
    }
    @NonNull public List<YoutubeSabrInfo.Format> getAudioFormats() { return audioFormats; }
    @NonNull public YoutubeSabrInfo.Format getVideoFormat() { return videoFormat; }

    @Nullable YoutubeSabrInfo.Format getFormat(@NonNull final String key) {
        return formatsByKey.get(key);
    }

    @NonNull String getFormatKey(@NonNull final YoutubeSabrInfo.Format format) {
        final String key = keysByFormat.get(format);
        if (key == null) throw new IllegalArgumentException("Unknown SABR format");
        return key;
    }

    @Nullable
    byte[] getInitializationData(@NonNull final YoutubeSabrInfo.Format format) {
        final byte[] data = initializationData.get(format);
        return data == null ? null : data.clone();
    }

    void putInitializationData(@NonNull final YoutubeSabrInfo.Format format,
                               @NonNull final byte[] data) {
        initializationData.putIfAbsent(format, data.clone());
    }

    long getDurationMs() {
        return Math.max(bootstrapAudioFormat.getApproxDurationMs(),
                videoFormat.getApproxDurationMs());
    }

    @NonNull YoutubeSabrFormatTimeline getAudioTimeline() { return audioTimeline; }
    @NonNull YoutubeSabrFormatTimeline getVideoTimeline() { return videoTimeline; }

    @NonNull
    YoutubeSabrFormatTimeline getTimeline(@NonNull final YoutubeSabrInfo.Format format) {
        if (format.isAudio() && audioFormats.contains(format)) return audioTimeline;
        if (format.getItag() == videoFormat.getItag()) return videoTimeline;
        throw new IllegalArgumentException("Unknown SABR itag: " + format.getItag());
    }

    @NonNull
    List<SabrMediaSegment> takeBootstrapMediaSegments() {
        return bootstrapMediaSegments.getAndSet(Collections.emptyList());
    }
}
