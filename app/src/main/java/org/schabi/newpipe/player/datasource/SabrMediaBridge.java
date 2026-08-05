package org.schabi.newpipe.player.datasource;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment;
import org.schabi.newpipe.player.SabrBackoffCoordinator;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Synchronously bridges one Media3 segment read to serialized SABR transactions. */
final class SabrMediaBridge {
    private static final int MAX_AHEAD_SEGMENTS = 64;
    private static final long COOKIE_RECOVERY_AFTER_MS = 10_000;
    private static final long EMPTY_RESPONSE_RETRY_MS = 250;

    private final YoutubeSabrSession session;
    private final SabrSourceSpec spec;
    private final Context appContext;
    private volatile YoutubeSabrInfo.Format videoFormat;
    @Nullable private volatile YoutubeSabrInfo.Format currentAudioFormat;
    private volatile boolean audioActive;
    private volatile boolean videoActive;
    @Nullable private volatile YoutubeSabrFormatTimeline audioTimeline;
    @Nullable private volatile YoutubeSabrFormatTimeline videoTimeline;
    private final Map<SabrSegmentKey, SabrMediaSegment> ahead = new ConcurrentHashMap<>();
    private final Map<YoutubeSabrInfo.Format, Integer> nextSequences =
            new ConcurrentHashMap<>();
    private final Deque<SabrSegmentKey> aheadOrder = new ArrayDeque<>();
    private final Object requestLock = new Object();

    private volatile boolean stopped;
    @Nullable private volatile Thread requestThread;

    SabrMediaBridge(@NonNull final Context context,
                    @NonNull final YoutubeSabrSession session,
                    @NonNull final SabrSourceSpec spec) {
        appContext = context.getApplicationContext();
        this.session = session;
        this.spec = spec;
        videoFormat = spec.getBootstrapVideoFormat();
        audioActive = true;
        videoActive = true;
    }

    void setActiveTracks(final boolean audioActive, final boolean videoActive) {
        this.audioActive = audioActive;
        this.videoActive = videoActive;
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

    void setSelectedFormats(@Nullable final YoutubeSabrInfo.Format audio,
                            @Nullable final YoutubeSabrInfo.Format video) {
        currentAudioFormat = audio;
        if (video != null) videoFormat = video;
    }

    /** Sends and consumes one ordinary SABR response. */
    YoutubeSabrSession.RequestResult fetchSegments(
            final long playerTimeMs,
            @NonNull final YoutubeSabrInfo.Format activeAudio,
            final boolean audioActive,
            final boolean videoActive) throws IOException, ExtractionException {
        synchronized (requestLock) {
            final YoutubeSabrSession.RequestResult result = session.requestOnce(
                    activeAudio,
                    videoFormat, playerTimeMs,
                    audioTimeline, bufferedThrough(activeAudio),
                    videoTimeline, bufferedThrough(videoFormat),
                    audioActive, videoActive, videoActive && !audioActive,
                    1.0f, segment -> acceptSegment(segment, activeAudio));
            publishBackoff(result.getBackoffMs());
            return result;
        }
    }

    @NonNull
    byte[] getInitializationData(@NonNull final YoutubeSabrInfo.Format format)
            throws IOException {
        final byte[] data = spec.getInitializationData(format);
        if (data == null) {
            throw new IOException("SABR initialization is unavailable: itag="
                    + format.getItag());
        }
        return data;
    }

    void seedSegments(@NonNull final List<SabrMediaSegment> segments) {
        for (final SabrMediaSegment segment : segments) {
            acceptSegment(segment, spec.getBootstrapAudioFormat());
        }
    }

    @NonNull
    SabrMediaSegment awaitSegment(@NonNull final SabrSegmentKey request,
                                  final long timeoutMs)
            throws IOException, ExtractionException {
        return awaitSegment(request, timeoutMs, Long.MIN_VALUE);
    }

    @NonNull
    SabrMediaSegment awaitSegment(@NonNull final SabrSegmentKey request,
                                  final long timeoutMs,
                                  final long explicitPlayerTimeMs)
            throws IOException, ExtractionException {
        final long deadlineNs = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
        SabrMediaSegment segment = ahead.get(request);
            if (segment != null) return segment;
            nextSequences.put(request.getFormat(), request.getSequenceNumber());
            synchronized (requestLock) {
                requestThread = Thread.currentThread();
                try {
                    long recoveryAtNs = System.nanoTime()
                            + TimeUnit.MILLISECONDS.toNanos(COOKIE_RECOVERY_AFTER_MS);
                    while (!stopped) {
                        segment = ahead.get(request);
                        if (segment != null) return segment;
                        awaitBackoffWithinBudget(request, deadlineNs);
                        if (stopped) throw new IOException("SABR bridge is stopped");
                        final YoutubeSabrInfo.Format activeAudio = request.getFormat().isAudio()
                                ? request.getFormat() : (currentAudioFormat == null
                                ? spec.getBootstrapAudioFormat() : currentAudioFormat);
                        final YoutubeSabrFormatTimeline requestTimeline =
                                timelineFor(request.getFormat());
                        final long playerTimeMs = explicitPlayerTimeMs != Long.MIN_VALUE
                                ? Math.max(0, explicitPlayerTimeMs)
                                : requestTimeline == null ? 0
                                : Math.max(0, requestTimeline.getStartMs(
                                request.getSequenceNumber()));
                        final YoutubeSabrSession.RequestResult result = fetchSegments(
                                playerTimeMs, activeAudio, audioActive, videoActive);
                        if (result.isDeferred()) continue;
                        segment = ahead.get(request);
                        if (segment != null) return segment;
                        ensureBudget(request, deadlineNs);
                        if (System.nanoTime() >= recoveryAtNs) {
                            session.clearPlaybackCookie();
                            recoveryAtNs = System.nanoTime()
                                    + TimeUnit.MILLISECONDS.toNanos(COOKIE_RECOVERY_AFTER_MS);
                        }
                        if (result.getSegmentCount() == 0
                                && session.getBackoffRemainingMs() == 0) {
                            sleepWithinBudget(request, deadlineNs, EMPTY_RESPONSE_RETRY_MS);
                        }
                    }
                    throw new IOException("SABR bridge is stopped");
                } finally {
                    requestThread = null;
                }
        }
    }


    void discard(@NonNull final SabrSegmentKey request) {
        final SabrMediaSegment segment = ahead.remove(request);
        synchronized (aheadOrder) {
            aheadOrder.remove(request);
        }
        if (segment != null) segment.delete();
    }

    void stop() {
        stopped = true;
        SabrBackoffCoordinator.getInstance().clear(appContext, this);
        final Thread current = requestThread;
        if (current != null) current.interrupt();
        for (final SabrMediaSegment segment : ahead.values()) segment.delete();
        ahead.clear();
        synchronized (aheadOrder) {
            aheadOrder.clear();
        }
    }

    private void awaitBackoffWithinBudget(@NonNull final SabrSegmentKey request,
                                          final long deadlineNs) throws IOException {
        while (true) {
            final long backoffMs = session.getBackoffRemainingMs();
            publishBackoff(backoffMs);
            if (backoffMs <= 0) return;
            final long remainingNs = ensureBudget(request, deadlineNs);
            if (TimeUnit.MILLISECONDS.toNanos(backoffMs) >= remainingNs) {
                throw timeout(request, "SABR backoff cannot fit within the fetch budget");
            }
            sleep(backoffMs);
        }
    }

    private void publishBackoff(final long remainingMs) {
        if (remainingMs > 0L) {
            SabrBackoffCoordinator.getInstance().begin(appContext, this, remainingMs);
        } else {
            SabrBackoffCoordinator.getInstance().clear(appContext, this);
        }
    }

    private void sleepWithinBudget(@NonNull final SabrSegmentKey request,
                                   final long deadlineNs,
                                   final long requestedMs) throws IOException {
        final long remainingNs = ensureBudget(request, deadlineNs);
        sleep(Math.min(requestedMs, Math.max(1,
                TimeUnit.NANOSECONDS.toMillis(remainingNs))));
    }

    private static void sleep(final long milliseconds) throws InterruptedIOException {
        try {
            Thread.sleep(milliseconds);
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            final InterruptedIOException interrupted =
                    new InterruptedIOException("Interrupted during SABR fetch");
            interrupted.initCause(error);
            throw interrupted;
        }
    }

    private long ensureBudget(@NonNull final SabrSegmentKey request,
                              final long deadlineNs) throws SabrLogicException {
        final long remainingNs = deadlineNs - System.nanoTime();
        if (remainingNs <= 0) throw timeout(request, "SABR fetch exceeded its budget");
        return remainingNs;
    }

    @NonNull
    private SabrLogicException timeout(@NonNull final SabrSegmentKey request,
                                       @NonNull final String reason) {
        return new SabrLogicException(reason + ": itag=" + request.getFormat().getItag()
                + ", seq=" + request.getSequenceNumber() + ", trace="
                + session.getDiagnosticTrace());
    }

    private void acceptSegment(@NonNull final SabrMediaSegment segment,
                               @Nullable final YoutubeSabrInfo.Format requestedAudio) {
        if (stopped) {
            segment.delete();
            return;
        }
        if (segment.getHeader().isInitSegment()) {
            final YoutubeSabrInfo.Format format = formatForSegment(segment, requestedAudio);
            if (format != null) {
                final byte[] data = segment.getData();
                spec.putInitializationData(format, data);
                try {
                    final YoutubeSabrFormatTimeline timeline =
                            YoutubeSabrFormatTimeline.parse(format, data);
                    if (format.isAudio()) audioTimeline = timeline;
                    else videoTimeline = timeline;
                } catch (final ExtractionException error) {
                    segment.delete();
                    throw new IllegalStateException("Invalid SABR initialization: itag="
                            + format.getItag(), error);
                }
            }
            segment.delete();
            return;
        }
        final YoutubeSabrInfo.Format format = formatForSegment(segment, requestedAudio);
        if (format == null) {
            segment.delete();
            return;
        }
        final SabrSegmentKey key = SabrSegmentKey.media(
                format, segment.getHeader().getSequenceNumber());
        final SabrMediaSegment previous = ahead.putIfAbsent(key, segment);
        if (previous != null) {
            if (previous != segment) segment.delete();
            return;
        }
        synchronized (aheadOrder) {
            aheadOrder.addLast(key);
            trimAhead();
        }
    }

    private void trimAhead() {
        while (aheadOrder.size() > MAX_AHEAD_SEGMENTS) {
            final SabrSegmentKey oldest = aheadOrder.removeFirst();
            final SabrMediaSegment removed = ahead.remove(oldest);
            if (removed != null) removed.delete();
        }
    }

    private int bufferedThrough(@NonNull final YoutubeSabrInfo.Format format) {
        final Integer next = nextSequences.get(format);
        return next == null ? 0 : Math.max(0, next - 1);
    }

    @Nullable
    private YoutubeSabrFormatTimeline timelineFor(
            @NonNull final YoutubeSabrInfo.Format format) {
        return format.isAudio() ? audioTimeline : videoTimeline;
    }

    @Nullable
    private YoutubeSabrInfo.Format formatForSegment(
            @NonNull final SabrMediaSegment segment,
            @Nullable final YoutubeSabrInfo.Format requestedAudio) {
        final int itag = segment.getHeader().getItag();
        final String xtags = segment.getHeader().getXtags();
        for (final YoutubeSabrInfo.Format video : spec.getVideoFormats()) {
            if (video.getItag() == itag && (xtags == null || Objects.equals(video.getXtags(), xtags))) {
                return video;
            }
        }
        if (requestedAudio != null && requestedAudio.getItag() == itag && (xtags == null
                || Objects.equals(requestedAudio.getXtags(), xtags))) return requestedAudio;
        YoutubeSabrInfo.Format onlyMatchingItag = null;
        int matchingItags = 0;
        for (final YoutubeSabrInfo.Format format : spec.getAudioFormats()) {
            if (format.getItag() == itag && Objects.equals(format.getXtags(), xtags)) return format;
            if (format.getItag() == itag) {
                onlyMatchingItag = format;
                matchingItags++;
            }
        }
        return xtags == null && matchingItags == 1 ? onlyMatchingItag : null;
    }
}
