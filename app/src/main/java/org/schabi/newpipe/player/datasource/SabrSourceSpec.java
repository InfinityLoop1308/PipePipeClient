package org.schabi.newpipe.player.datasource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Collections;
import java.util.List;

/** Immutable metadata needed to construct a SABR MediaSource without owning a live session. */
public final class SabrSourceSpec {
    private static final AtomicLong NEXT_SOURCE_ID = new AtomicLong();

    private final long sourceId;
    @NonNull private final String videoId;
    @NonNull private final YoutubeSabrInfo info;
    @NonNull private final YoutubeSabrInfo.Format audioFormat;
    @NonNull private final YoutubeSabrInfo.Format videoFormat;
    @NonNull private final Localization localization;
    @NonNull private final byte[] audioInitializationData;
    @NonNull private final byte[] videoInitializationData;
    @NonNull private final YoutubeSabrFormatTimeline audioTimeline;
    @NonNull private final YoutubeSabrFormatTimeline videoTimeline;
    @NonNull private final AtomicReference<YoutubeSabrSession> preparedSession;
    @NonNull private final List<SabrMediaSegment> bootstrapMediaSegments;

    public SabrSourceSpec(@NonNull final String videoId,
                   @NonNull final YoutubeSabrInfo info,
                   @NonNull final YoutubeSabrInfo.Format audioFormat,
                   @NonNull final YoutubeSabrInfo.Format videoFormat,
                   @NonNull final Localization localization,
                   @NonNull final byte[] audioInitializationData,
                   @NonNull final byte[] videoInitializationData) {
        this(videoId, info, audioFormat, videoFormat, localization,
                audioInitializationData, videoInitializationData,
                parseTimeline(audioFormat, audioInitializationData),
                parseTimeline(videoFormat, videoInitializationData),
                null, Collections.emptyList());
    }

    SabrSourceSpec(@NonNull final String videoId,
                   @NonNull final YoutubeSabrInfo info,
                   @NonNull final YoutubeSabrInfo.Format audioFormat,
                   @NonNull final YoutubeSabrInfo.Format videoFormat,
                   @NonNull final Localization localization,
                   @NonNull final byte[] audioInitializationData,
                   @NonNull final byte[] videoInitializationData,
                   @NonNull final YoutubeSabrFormatTimeline audioTimeline,
                   @NonNull final YoutubeSabrFormatTimeline videoTimeline,
                   @Nullable final YoutubeSabrSession preparedSession,
                   @NonNull final List<SabrMediaSegment> bootstrapMediaSegments) {
        this.sourceId = NEXT_SOURCE_ID.incrementAndGet();
        this.videoId = videoId;
        this.info = info;
        this.audioFormat = audioFormat;
        this.videoFormat = videoFormat;
        this.localization = localization;
        this.audioInitializationData = audioInitializationData.clone();
        this.videoInitializationData = videoInitializationData.clone();
        this.audioTimeline = audioTimeline;
        this.videoTimeline = videoTimeline;
        this.preparedSession = new AtomicReference<>(preparedSession);
        this.bootstrapMediaSegments = bootstrapMediaSegments;
    }

    @NonNull
    public String getVideoId() {
        return videoId;
    }

    long getSourceId() {
        return sourceId;
    }

    @NonNull
    public YoutubeSabrInfo getInfo() {
        return info;
    }

    @NonNull
    public YoutubeSabrInfo.Format getAudioFormat() {
        return audioFormat;
    }

    @NonNull
    public YoutubeSabrInfo.Format getVideoFormat() {
        return videoFormat;
    }

    @NonNull
    Localization getLocalization() {
        return localization;
    }

    @Nullable
    byte[] getInitializationData(final int itag) {
        if (itag == audioFormat.getItag()) {
            return audioInitializationData.clone();
        }
        if (itag == videoFormat.getItag()) {
            return videoInitializationData.clone();
        }
        return null;
    }

    long getDurationMs() {
        return Math.max(audioFormat.getApproxDurationMs(), videoFormat.getApproxDurationMs());
    }

    @NonNull YoutubeSabrFormatTimeline getAudioTimeline() { return audioTimeline; }
    @NonNull YoutubeSabrFormatTimeline getVideoTimeline() { return videoTimeline; }

    @NonNull
    YoutubeSabrFormatTimeline getTimeline(@NonNull final YoutubeSabrInfo.Format format) {
        if (format.getItag() == audioFormat.getItag()) return audioTimeline;
        if (format.getItag() == videoFormat.getItag()) return videoTimeline;
        throw new IllegalArgumentException("Unknown SABR itag: " + format.getItag());
    }

    @Nullable
    YoutubeSabrSession takePreparedSession() {
        return preparedSession.getAndSet(null);
    }

    @NonNull
    List<SabrMediaSegment> takeBootstrapMediaSegments() {
        return bootstrapMediaSegments;
    }

    void discardPreparedSession() {
        preparedSession.set(null);
    }

    @NonNull
    private static YoutubeSabrFormatTimeline parseTimeline(
            @NonNull final YoutubeSabrInfo.Format format, @NonNull final byte[] data) {
        try {
            return YoutubeSabrFormatTimeline.parse(format, data);
        } catch (final Exception error) {
            throw new IllegalArgumentException("Invalid SABR initialization timeline: itag="
                    + format.getItag(), error);
        }
    }
}
