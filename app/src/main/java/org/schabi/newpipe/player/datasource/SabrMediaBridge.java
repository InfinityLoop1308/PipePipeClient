package org.schabi.newpipe.player.datasource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Synchronously bridges one Media3 segment read to serialized SABR transactions. */
final class SabrMediaBridge {
    private static final int MAX_AHEAD_SEGMENTS = 64;
    private static final long COOKIE_RECOVERY_AFTER_MS = 10_000;
    private static final long EMPTY_RESPONSE_RETRY_MS = 250;

    private final YoutubeSabrSession session;
    private final SabrSourceSpec spec;
    private final YoutubeSabrInfo.Format videoFormat;
    private final YoutubeSabrFormatTimeline audioTimeline;
    private final YoutubeSabrFormatTimeline videoTimeline;
    private final Map<SabrSegmentKey, SabrMediaSegment> ahead = new ConcurrentHashMap<>();
    private final Map<YoutubeSabrInfo.Format, Integer> nextSequences =
            new ConcurrentHashMap<>();
    private final Map<SabrSegmentKey, AtomicInteger> activeDemands = new ConcurrentHashMap<>();
    private final Deque<SabrSegmentKey> aheadOrder = new ArrayDeque<>();
    private final Object requestLock = new Object();

    private volatile boolean stopped;
    @Nullable private volatile Thread requestThread;

    SabrMediaBridge(@NonNull final YoutubeSabrSession session,
                    @NonNull final SabrSourceSpec spec) {
        this.session = session;
        this.spec = spec;
        videoFormat = spec.getVideoFormat();
        audioTimeline = spec.getAudioTimeline();
        videoTimeline = spec.getVideoTimeline();
    }

    @NonNull
    byte[] fetchInitialization(@NonNull final YoutubeSabrInfo.Format format,
                               final long timeoutMs)
            throws IOException, ExtractionException {
        byte[] data = spec.getInitializationData(format);
        if (data != null) return data;
        final long deadlineNs = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
        synchronized (requestLock) {
            requestThread = Thread.currentThread();
            try {
                data = spec.getInitializationData(format);
                if (data != null) return data;
                awaitBackoffWithinBudget(SabrSegmentKey.initialization(format), deadlineNs);
                if (stopped) throw new IOException("SABR bridge is stopped");
                final long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(
                        ensureBudget(SabrSegmentKey.initialization(format), deadlineNs)));
                data = session.fetchInitializationData(format, remainingMs,
                        segment -> acceptSegment(segment, format.isAudio() ? format : null));
                ensureBudget(SabrSegmentKey.initialization(format), deadlineNs);
                spec.putInitializationData(format, data);
                return data;
            } finally {
                requestThread = null;
            }
        }
    }

    void seedSegments(@NonNull final List<SabrMediaSegment> segments) {
        for (final SabrMediaSegment segment : segments) {
            acceptSegment(segment, spec.getBootstrapAudioFormat());
        }
    }

    @NonNull
    SabrMediaSegment fetchSegment(@NonNull final SabrSegmentKey request,
                                  final long timeoutMs)
            throws IOException, ExtractionException {
        final long deadlineNs = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
        retainDemand(request);
        try {
            SabrMediaSegment segment = ahead.get(request);
            if (segment != null) return segment;
            if (!request.isInitialization()) {
                nextSequences.put(request.getFormat(), request.getSequenceNumber());
            }
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

                        final YoutubeSabrInfo.Format activeAudio = activeAudioFormat(request);
                        final boolean audioActive = activeAudio != null;
                        final boolean videoActive = hasActiveDemandFor(videoFormat);
                        final long playerTimeMs = request.isInitialization() ? 0
                                : Math.max(0, timelineFor(request.getFormat())
                                .getStartMs(request.getSequenceNumber()));
                        final YoutubeSabrSession.RequestResult result = session.requestOnce(
                                activeAudio == null ? spec.getBootstrapAudioFormat() : activeAudio,
                                videoFormat,
                                playerTimeMs,
                                audioTimeline, activeAudio == null ? 0 : bufferedThrough(activeAudio),
                                videoTimeline, bufferedThrough(videoFormat),
                                audioActive, videoActive, videoActive && !audioActive,
                                1.0f, received -> acceptSegment(received, activeAudio));
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
        } finally {
            releaseDemand(request);
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
            if (backoffMs <= 0) return;
            final long remainingNs = ensureBudget(request, deadlineNs);
            if (TimeUnit.MILLISECONDS.toNanos(backoffMs) >= remainingNs) {
                throw timeout(request, "SABR backoff cannot fit within the fetch budget");
            }
            sleep(backoffMs);
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
        if (stopped || segment.getHeader().isInitSegment()) {
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
        int protectedKeysSeen = 0;
        while (aheadOrder.size() > MAX_AHEAD_SEGMENTS
                && protectedKeysSeen < aheadOrder.size()) {
            final SabrSegmentKey oldest = aheadOrder.removeFirst();
            if (activeDemands.containsKey(oldest)) {
                aheadOrder.addLast(oldest);
                protectedKeysSeen++;
                continue;
            }
            final SabrMediaSegment removed = ahead.remove(oldest);
            if (removed != null) removed.delete();
            protectedKeysSeen = 0;
        }
    }

    private void retainDemand(@NonNull final SabrSegmentKey request) {
        activeDemands.compute(request, (ignored, count) -> {
            if (count == null) return new AtomicInteger(1);
            count.incrementAndGet();
            return count;
        });
    }

    private void releaseDemand(@NonNull final SabrSegmentKey request) {
        activeDemands.computeIfPresent(request,
                (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    private boolean hasActiveDemandFor(@NonNull final YoutubeSabrInfo.Format format) {
        for (final SabrSegmentKey demand : activeDemands.keySet()) {
            if (demand.getFormat().getItag() == format.getItag()) return true;
        }
        return false;
    }

    @Nullable
    private YoutubeSabrInfo.Format activeAudioFormat(@NonNull final SabrSegmentKey request) {
        if (request.getFormat().isAudio()) return request.getFormat();
        for (final SabrSegmentKey demand : activeDemands.keySet()) {
            if (demand.getFormat().isAudio()) return demand.getFormat();
        }
        return null;
    }

    private int bufferedThrough(@NonNull final YoutubeSabrInfo.Format format) {
        final Integer next = nextSequences.get(format);
        return next == null ? 0 : Math.max(0, next - 1);
    }

    @NonNull
    private YoutubeSabrFormatTimeline timelineFor(@NonNull final YoutubeSabrInfo.Format format) {
        return format.isAudio() ? audioTimeline : videoTimeline;
    }

    @Nullable
    private YoutubeSabrInfo.Format formatForSegment(
            @NonNull final SabrMediaSegment segment,
            @Nullable final YoutubeSabrInfo.Format requestedAudio) {
        final int itag = segment.getHeader().getItag();
        final String xtags = segment.getHeader().getXtags();
        if (videoFormat.getItag() == itag && (xtags == null
                || Objects.equals(videoFormat.getXtags(), xtags))) return videoFormat;
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
