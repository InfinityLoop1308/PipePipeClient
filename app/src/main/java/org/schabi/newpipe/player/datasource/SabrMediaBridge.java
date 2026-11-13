package org.schabi.newpipe.player.datasource;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrProtocolException;
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment;
import org.schabi.newpipe.player.SabrBackoffCoordinator;
import org.schabi.newpipe.youtube.SabrAttestationRetryHandler;
import org.schabi.newpipe.youtube.SabrRequestCoordinator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Bridges Media3's segment demand to serialized SABR requests. */
final class SabrMediaBridge {
    private static final int MAX_AHEAD_SEGMENTS = 64;

    private final Context appContext;
    private final SabrSourceSpec spec;
    private final YoutubeSabrSession session;
    private final boolean postLive;
    private volatile long postLiveDurationMs = -1;
    private final SabrRequestCoordinator requestCoordinator;
    private final ReentrantLock transactionLock = new ReentrantLock(true);
    private final Map<SabrSegmentKey, SabrMediaSegment> ahead = new LinkedHashMap<>();
    private final Map<YoutubeSabrInfo.Format, Integer> nextSequences =
            new ConcurrentHashMap<>();

    private volatile Selection selection;
    @Nullable private volatile YoutubeSabrFormatTimeline audioTimeline;
    @Nullable private volatile YoutubeSabrFormatTimeline videoTimeline;
    private volatile boolean stopped;

    SabrMediaBridge(@NonNull final Context context,
                    @NonNull final YoutubeSabrSession session,
                    @NonNull final SabrSourceSpec spec) {
        appContext = context.getApplicationContext();
        this.spec = spec;
        this.session = session;
        postLive = spec.isPostLive();
        requestCoordinator = new SabrRequestCoordinator(
                session,
                new SabrAttestationRetryHandler(spec.getVideoId(),
                        spec.getInfo().getPoToken()),
                this::publishBackoff);
        selection = new Selection(spec.getBootstrapAudioFormat(),
                spec.getBootstrapVideoFormat(), true, true);
    }

    void setSelection(@Nullable final YoutubeSabrInfo.Format audio,
                      @Nullable final YoutubeSabrInfo.Format video,
                      final boolean audioActive,
                      final boolean videoActive) {
        final Selection previous = selection;
        selection = new Selection(audio,
                video == null ? previous.video : video,
                audioActive, videoActive);
    }

    @NonNull
    YoutubeSabrFormatTimeline getTimeline(@NonNull final YoutubeSabrInfo.Format format) {
        final YoutubeSabrFormatTimeline timeline = format.isAudio()
                ? audioTimeline : videoTimeline;
        if (timeline == null) {
            throw new IllegalStateException("SABR timeline is not ready: itag="
                    + format.getItag());
        }
        return timeline;
    }

    boolean hasTimelines() {
        return audioTimeline != null && videoTimeline != null;
    }

    @Nullable
    YoutubeSabrFormatTimeline getAudioTimeline() {
        return audioTimeline;
    }

    @Nullable
    YoutubeSabrFormatTimeline getVideoTimeline() {
        return videoTimeline;
    }

    /**
     * Hands the timelines of a previous preparation to a freshly created bridge.
     *
     * <p>The generated DASH manifest describes exactly the segment timeline the manifest was built
     * from, so a source that is prepared again has to serve that same timeline instead of asking
     * for a new preparation response.
     */
    void restoreTimelines(@Nullable final YoutubeSabrFormatTimeline audio,
                          @Nullable final YoutubeSabrFormatTimeline video) {
        if (audioTimeline == null) {
            audioTimeline = audio;
        }
        if (videoTimeline == null) {
            videoTimeline = video;
        }
    }

    /** Prepares timelines while retaining media returned around the initial position. */
    void prepareTimelines(final long initialPositionMs) throws IOException, ExtractionException {
        final List<YoutubeSabrInfo.Format> formats = new ArrayList<>(2);
        formats.add(spec.getBootstrapAudioFormat());
        formats.add(spec.getBootstrapVideoFormat());
        final YoutubeSabrRequest request = YoutubeSabrRequest.preparation(
                Math.max(0, initialPositionMs), formats);
        // Post-live SABR has no sidx/Cues: the timeline comes from LIVE_METADATA and
        // FORMAT_INITIALIZATION_METADATA, so it can be ready before the initialization data.
        requestOnce(request, spec.getBootstrapAudioFormat(), postLive
                ? () -> (installPostLiveTimelines()
                        && spec.getInitializationData(spec.getBootstrapAudioFormat()) != null
                        && spec.getInitializationData(spec.getBootstrapVideoFormat()) != null)
                        || stopped
                : () -> hasTimelines() || stopped);
    }

    private boolean installPostLiveTimelines() {
        // The LIVE_METADATA post-live flag is not repeated in every response. The extractor has
        // already classified the video as POST_LIVE_STREAM, so the live window itself is enough.
        if (!session.isLive()) {
            return false;
        }
        final YoutubeSabrInfo.Format audio = spec.getBootstrapAudioFormat();
        final YoutubeSabrInfo.Format video = spec.getBootstrapVideoFormat();
        final long audioStart = session.getLiveStartSegmentNumber(audio);
        final long videoStart = session.getLiveStartSegmentNumber(video);
        final long audioEnd = session.getLiveEndSegmentNumber(audio);
        final long videoEnd = session.getLiveEndSegmentNumber(video);
        final long audioDuration = session.getLiveSegmentDurationMs(audio);
        final long videoDuration = session.getLiveSegmentDurationMs(video);
        if (audioStart < 0 || videoStart < 0 || audioEnd < audioStart || videoEnd < videoStart
                || audioDuration <= 0 || videoDuration <= 0) {
            return false;
        }
        final long windowStart = Math.max(0, session.getLiveSeekableStartTimeMs());
        final long windowEnd = Math.max(session.getLiveSeekableEndTimeMs(),
                Math.max(session.getLiveHeadTimeMs(), windowStart));
        try {
            audioTimeline = YoutubeSabrFormatTimeline.live((int) audioStart, (int) audioEnd,
                    windowStart, audioDuration);
            videoTimeline = YoutubeSabrFormatTimeline.live((int) videoStart, (int) videoEnd,
                    windowStart, videoDuration);
        } catch (final SabrProtocolException error) {
            session.addDiagnosticEvent("post_live_timeline_invalid message=" + error.getMessage());
            return false;
        }
        postLiveDurationMs = Math.max(1, windowEnd - windowStart);
        session.addDiagnosticEvent("post_live_timeline"
                + " startMs=" + windowStart
                + " endMs=" + windowEnd
                + " audio=" + audioStart + '-' + audioEnd + '/' + audioDuration
                + " video=" + videoStart + '-' + videoEnd + '/' + videoDuration);
        return true;
    }

    long getPostLiveDurationMs() {
        return postLiveDurationMs;
    }

    void seedSegments(@NonNull final List<SabrMediaSegment> segments) {
        for (final SabrMediaSegment segment : segments) {
            acceptSegment(segment, spec.getBootstrapAudioFormat());
        }
    }

    @NonNull
    SabrMediaSegment awaitSegment(@NonNull final SabrSegmentKey key)
            throws IOException, ExtractionException {
        nextSequences.put(key.getFormat(), key.getSequenceNumber());
        while (true) {
            final SabrMediaSegment cached = cachedSegment(key);
            if (cached != null) return cached;

            if (!transactionLock.tryLock()) {
                final SabrMediaSegment arrived = cachedSegment(key);
                if (arrived != null) return arrived;
                throw new SabrSegmentPendingException("SABR request in progress: itag="
                        + key.getFormat().getItag() + ", seq=" + key.getSequenceNumber());
            }
            try {
                final SabrMediaSegment delivered = cachedSegment(key);
                if (delivered != null) return delivered;
                requestFor(key);
            } finally {
                transactionLock.unlock();
            }
        }
    }

    private void requestFor(@NonNull final SabrSegmentKey key)
            throws IOException, ExtractionException {
        final Selection current = selection;
        final YoutubeSabrInfo.Format audio = key.getFormat().isAudio()
                ? key.getFormat() : current.audio == null
                ? spec.getBootstrapAudioFormat() : current.audio;
        final long segmentStartMs = getTimeline(key.getFormat())
                .getStartMs(key.getSequenceNumber());
        // A zero player time makes the request look like the initial preparation request, which
        // does not select tracks. Use 1 ms so live/DVR requests carry the selected formats.
        final long playerTimeMs = postLive ? Math.max(1, segmentStartMs)
                : Math.max(0, segmentStartMs);
        final List<YoutubeSabrRequest.Track> tracks = new ArrayList<>(2);
        if (current.audioActive || key.getFormat().isAudio()) {
            tracks.add(track(audio));
        }
        if (current.videoActive || key.getFormat().isVideo()) {
            tracks.add(track(current.video));
        }
        requestOnce(YoutubeSabrRequest.playback(playerTimeMs, 1.0f, tracks), audio);
    }

    @NonNull
    private YoutubeSabrRequest.Track track(@NonNull final YoutubeSabrInfo.Format format) {
        final Integer next = nextSequences.get(format);
        final YoutubeSabrFormatTimeline timeline = getTimeline(format);
        // Anything below the timeline start means nothing is buffered yet.
        final int bufferedThrough = (next == null ? timeline.getStartSequence() : next) - 1;
        return YoutubeSabrRequest.Track.of(format, timeline, bufferedThrough);
    }

    private void requestOnce(@NonNull final YoutubeSabrRequest request,
                             @Nullable final YoutubeSabrInfo.Format requestedAudio)
            throws IOException, ExtractionException {
        throwIfStopped();
        requestCoordinator.request(request,
                segment -> acceptSegment(segment, requestedAudio));
    }

    private void requestOnce(@NonNull final YoutubeSabrRequest request,
                             @Nullable final YoutubeSabrInfo.Format requestedAudio,
                             @NonNull final BooleanSupplier progressChecker)
            throws IOException, ExtractionException {
        throwIfStopped();
        requestCoordinator.request(request,
                segment -> acceptSegment(segment, requestedAudio),
                progressChecker);
    }

    void discard(@NonNull final SabrSegmentKey key) {
        final SabrMediaSegment segment;
        synchronized (ahead) {
            segment = ahead.remove(key);
        }
        if (segment != null) segment.delete();
    }

    void stop() {
        stopped = true;
        SabrBackoffCoordinator.getInstance().clear(appContext, this);
        synchronized (ahead) {
            for (final SabrMediaSegment segment : ahead.values()) segment.delete();
            ahead.clear();
        }
    }

    private void publishBackoff(final long remainingMs) {
        if (remainingMs > 0) {
            SabrBackoffCoordinator.getInstance().begin(appContext, this, remainingMs);
        } else {
            SabrBackoffCoordinator.getInstance().clear(appContext, this);
        }
    }

    private void throwIfStopped() throws IOException {
        if (stopped) throw new IOException("SABR bridge is stopped");
    }

    private void acceptSegment(@NonNull final SabrMediaSegment received,
                               @Nullable final YoutubeSabrInfo.Format requestedAudio) {
        SabrMediaSegment segment = received;
        if (stopped) {
            segment.delete();
            return;
        }
        final YoutubeSabrInfo.Format format = formatForSegment(segment, requestedAudio);
        if (format == null) {
            segment.delete();
            return;
        }
        if (segment.getHeader().isInitSegment()) {
            acceptInitialization(format, segment);
            return;
        }
        if (postLive) {
            segment = stripPostLiveInitialization(format, segment);
        }
        // Post-live sequences start at 0; VOD sequences start at 1.
        if (segment.getHeader().getSequenceNumber() < (postLive ? 0 : 1)) {
            segment.delete();
            return;
        }
        cacheMedia(format, segment);
    }

    /**
     * Post-live SABR has no separate initialization segment: each self-initializing media segment
     * starts with {@code ftyp}+{@code moov}. Keeps the first copy as the track initialization and
     * returns the segment without it, so cached media starts at its {@code emsg}/{@code moof}.
     */
    @NonNull
    private SabrMediaSegment stripPostLiveInitialization(
            @NonNull final YoutubeSabrInfo.Format format,
            @NonNull final SabrMediaSegment segment) {
        if (segment.isDiskBacked()) {
            return segment;
        }
        final byte[] data = segment.getData();
        final int initializationEnd = initializationEnd(data);
        if (initializationEnd <= 0) {
            return segment;
        }
        if (spec.getInitializationData(format) == null) {
            spec.putInitializationData(format, Arrays.copyOfRange(data, 0, initializationEnd));
        }
        final SabrMediaSegment media = SabrMediaSegment.InternalAccess.fromBytes(
                segment.getHeader(), Arrays.copyOfRange(data, initializationEnd, data.length));
        segment.delete();
        return media;
    }

    /** End offset of a leading {@code ftyp}+{@code moov} pair, or -1 when there is none. */
    private static int initializationEnd(@NonNull final byte[] data) {
        int offset = 0;
        while (offset + 8 <= data.length) {
            final long size = (data[offset] & 0xffL) << 24 | (data[offset + 1] & 0xff) << 16
                    | (data[offset + 2] & 0xff) << 8 | data[offset + 3] & 0xff;
            final String type = new String(data, offset + 4, 4, StandardCharsets.US_ASCII);
            if (size < 8 || size > data.length - offset
                    || (offset == 0 && !"ftyp".equals(type))) {
                return -1;
            }
            offset += (int) size;
            if ("moov".equals(type)) {
                return offset;
            }
        }
        return -1;
    }

    private void acceptInitialization(@NonNull final YoutubeSabrInfo.Format format,
                                      @NonNull final SabrMediaSegment segment) {
        final byte[] data = segment.getData();
        spec.putInitializationData(format, data);
        if (postLive) {
            // A post-live initialization segment describes the live encoder state, not the DVR
            // segment index. The finite timeline is built from LIVE_METADATA instead.
            segment.delete();
            return;
        }
        try {
            final YoutubeSabrFormatTimeline timeline =
                    YoutubeSabrFormatTimeline.parse(format, data);
            if (format.isAudio()) audioTimeline = timeline;
            else videoTimeline = timeline;
        } catch (final ExtractionException error) {
            throw new IllegalStateException("Invalid SABR initialization: itag="
                    + format.getItag(), error);
        } finally {
            segment.delete();
        }
    }

    private void cacheMedia(@NonNull final YoutubeSabrInfo.Format format,
                            @NonNull final SabrMediaSegment segment) {
        final SabrSegmentKey key = SabrSegmentKey.media(
                format, segment.getHeader().getSequenceNumber());
        synchronized (ahead) {
            final SabrMediaSegment previous = ahead.get(key);
            if (previous != null) {
                if (previous != segment) segment.delete();
                return;
            }
            if (stopped) {
                segment.delete();
                return;
            }
            ahead.put(key, segment);
            if (ahead.size() > MAX_AHEAD_SEGMENTS) {
                final Iterator<SabrMediaSegment> iterator = ahead.values().iterator();
                final SabrMediaSegment oldest = iterator.next();
                iterator.remove();
                oldest.delete();
            }
        }
    }

    @Nullable
    private SabrMediaSegment cachedSegment(@NonNull final SabrSegmentKey key) {
        synchronized (ahead) {
            return ahead.get(key);
        }
    }

    @Nullable
    private YoutubeSabrInfo.Format formatForSegment(
            @NonNull final SabrMediaSegment segment,
            @Nullable final YoutubeSabrInfo.Format requestedAudio) {
        final int itag = segment.getHeader().getItag();
        final String xtags = segment.getHeader().getXtags();
        for (final YoutubeSabrInfo.Format video : spec.getVideoFormats()) {
            if (video.getItag() == itag
                    && (xtags == null || Objects.equals(video.getXtags(), xtags))) {
                return video;
            }
        }
        if (requestedAudio != null && requestedAudio.getItag() == itag
                && (xtags == null || Objects.equals(requestedAudio.getXtags(), xtags))) {
            return requestedAudio;
        }
        YoutubeSabrInfo.Format matchingAudio = null;
        int matchingItags = 0;
        for (final YoutubeSabrInfo.Format audio : spec.getAudioFormats()) {
            if (audio.getItag() == itag && Objects.equals(audio.getXtags(), xtags)) return audio;
            if (audio.getItag() == itag) {
                matchingAudio = audio;
                matchingItags++;
            }
        }
        return xtags == null && matchingItags == 1 ? matchingAudio : null;
    }

    private static final class Selection {
        @Nullable private final YoutubeSabrInfo.Format audio;
        @NonNull private final YoutubeSabrInfo.Format video;
        private final boolean audioActive;
        private final boolean videoActive;

        private Selection(@Nullable final YoutubeSabrInfo.Format audio,
                          @NonNull final YoutubeSabrInfo.Format video,
                          final boolean audioActive,
                          final boolean videoActive) {
            this.audio = audio;
            this.video = video;
            this.audioActive = audioActive;
            this.videoActive = videoActive;
        }
    }
}
