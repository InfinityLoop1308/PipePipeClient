package org.schabi.newpipe.player.datasource;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrAttestationException;
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment;
import org.schabi.newpipe.player.SabrBackoffCoordinator;
import org.schabi.newpipe.youtube.SabrAttestationRetryHandler;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Synchronously bridges one Media3 segment read to serialized SABR transactions. */
final class SabrMediaBridge {
    private static final int MAX_AHEAD_SEGMENTS = 64;
    private static final long EMPTY_RESPONSE_RETRY_MS = 250;
    private static final long PREPARATION_TIMEOUT_MS = 30_000;

    private final YoutubeSabrSession session;
    private final SabrSourceSpec spec;
    private final Context appContext;
    private final SabrAttestationRetryHandler attestationRetryHandler;
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
    private final Object stateLock = new Object();

    private volatile boolean stopped;
    private boolean requestInFlight;
    private long transactionGeneration;
    @Nullable private Throwable terminalFailure;
    @Nullable private volatile Thread requestThread;

    SabrMediaBridge(@NonNull final Context context,
                    @NonNull final YoutubeSabrSession session,
                    @NonNull final SabrSourceSpec spec) {
        appContext = context.getApplicationContext();
        this.session = session;
        this.spec = spec;
        attestationRetryHandler = new SabrAttestationRetryHandler(spec.getVideoId());
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

    @NonNull
    private RequestContext playbackRequest(
            final long playerTimeMs,
            @NonNull final YoutubeSabrInfo.Format activeAudio,
            final boolean audioActive,
            final boolean videoActive) {
        final List<YoutubeSabrRequest.Track> tracks = new ArrayList<>(2);
        if (audioActive) {
            tracks.add(YoutubeSabrRequest.Track.of(activeAudio, audioTimeline,
                    bufferedThrough(activeAudio)));
        }
        if (videoActive) {
            tracks.add(YoutubeSabrRequest.Track.of(videoFormat, videoTimeline,
                    bufferedThrough(videoFormat)));
        }
        return new RequestContext(
                YoutubeSabrRequest.playback(playerTimeMs, 1.0f, tracks), activeAudio);
    }

    /** Prepares timelines while retaining any media returned around the initial position. */
    void prepareTimelines(final long initialPositionMs) throws IOException, ExtractionException {
        final List<YoutubeSabrInfo.Format> preferredFormats = new ArrayList<>(2);
        preferredFormats.add(spec.getBootstrapAudioFormat());
        preferredFormats.add(spec.getBootstrapVideoFormat());
        awaitResult(PREPARATION_TIMEOUT_MS,
                () -> hasTimelines() ? Boolean.TRUE : null,
                () -> new RequestContext(YoutubeSabrRequest.preparation(
                        Math.max(0, initialPositionMs), preferredFormats),
                        spec.getBootstrapAudioFormat()),
                reason -> new IOException(reason + ": video=" + spec.getVideoId()
                        + ", playerMs=" + initialPositionMs
                        + ", trace=" + session.getDiagnosticTrace()));
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
        final SabrMediaSegment segment = ahead.get(request);
        if (segment != null) return segment;
        nextSequences.put(request.getFormat(), request.getSequenceNumber());
        return awaitResult(timeoutMs,
                () -> ahead.get(request),
                () -> {
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
                    return playbackRequest(playerTimeMs, activeAudio,
                            audioActive, videoActive);
                },
                reason -> timeout(request, reason));
    }

    @NonNull
    private <T> T awaitResult(final long timeoutMs,
                              @NonNull final Supplier<T> resultSupplier,
                              @NonNull final Supplier<RequestContext> requestSupplier,
                              @NonNull final FailureFactory failureFactory)
            throws IOException, ExtractionException {
        final long deadlineNs = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
        while (true) {
            final T value = resultSupplier.get();
            if (value != null) return value;
            ensureBudget(deadlineNs, failureFactory);
            synchronized (stateLock) {
                final T synchronizedValue = resultSupplier.get();
                if (synchronizedValue != null) return synchronizedValue;
                throwIfStoppedOrFailed();
                if (requestInFlight) {
                    waitForTransaction(transactionGeneration, deadlineNs, failureFactory);
                    continue;
                }
                requestInFlight = true;
                requestThread = Thread.currentThread();
            }

            YoutubeSabrSession.RequestResult result = null;
            Throwable failureToPublish = null;
            try {
                if (!awaitBackoffWithinBudget(deadlineNs)) {
                    throw failureFactory.create(
                            "SABR backoff cannot fit within the request budget");
                }
                ensureBudget(deadlineNs, failureFactory);
                throwIfStoppedOrFailed();
                try {
                    final RequestContext context = requestSupplier.get();
                    result = session.requestOnce(context.request, segment -> {
                        attestationRetryHandler.onMediaReceived();
                        acceptSegment(segment, context.requestedAudio);
                    });
                    publishBackoff(result.getBackoffMs());
                } catch (final SabrAttestationException error) {
                    try {
                        attestationRetryHandler.prepareRetry(session, error);
                    } catch (final ExtractionException retryFailure) {
                        failureToPublish = retryFailure;
                        throw retryFailure;
                    }
                } catch (final IOException | ExtractionException | RuntimeException error) {
                    failureToPublish = error;
                    throw error;
                }
                if (result != null && !result.isDeferred() && result.getSegmentCount() == 0
                        && session.getBackoffRemainingMs() == 0
                        && !sleepWithinBudget(deadlineNs, EMPTY_RESPONSE_RETRY_MS)) {
                    throw failureFactory.create("SABR request exceeded its budget");
                }
            } finally {
                completeTransaction(failureToPublish);
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
        final Thread current;
        synchronized (stateLock) {
            current = requestThread;
            stateLock.notifyAll();
        }
        if (current != null) current.interrupt();
        for (final SabrMediaSegment segment : ahead.values()) segment.delete();
        ahead.clear();
        synchronized (aheadOrder) {
            aheadOrder.clear();
        }
    }

    private boolean awaitBackoffWithinBudget(final long deadlineNs) throws IOException {
        while (true) {
            final long serverBackoffMs = session.getBackoffRemainingMs();
            publishBackoff(serverBackoffMs);
            if (serverBackoffMs <= 0) return true;
            if (TimeUnit.MILLISECONDS.toNanos(serverBackoffMs)
                    >= deadlineNs - System.nanoTime()) {
                return false;
            }
            sleep(serverBackoffMs);
        }
    }

    private void waitForTransaction(final long observedGeneration,
                                    final long deadlineNs,
                                    @NonNull final FailureFactory failureFactory)
            throws IOException, ExtractionException {
        while (transactionGeneration == observedGeneration && !stopped
                && terminalFailure == null) {
            final long remainingNs = deadlineNs - System.nanoTime();
            if (remainingNs <= 0) {
                throw failureFactory.create("SABR request exceeded its budget");
            }
            try {
                stateLock.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNs)));
            } catch (final InterruptedException error) {
                Thread.currentThread().interrupt();
                if (stopped) throw new IOException("SABR bridge is stopped", error);
                final InterruptedIOException interrupted =
                        new InterruptedIOException("Interrupted during SABR fetch");
                interrupted.initCause(error);
                throw interrupted;
            }
        }
        throwIfStoppedOrFailed();
    }

    private void completeTransaction(@Nullable final Throwable failure) {
        synchronized (stateLock) {
            if (failure != null && terminalFailure == null) {
                terminalFailure = failure;
            }
            requestInFlight = false;
            requestThread = null;
            transactionGeneration++;
            stateLock.notifyAll();
        }
    }

    private void throwIfStoppedOrFailed() throws IOException, ExtractionException {
        final Throwable failure;
        synchronized (stateLock) {
            if (stopped) throw new IOException("SABR bridge is stopped");
            failure = terminalFailure;
        }
        if (failure == null) return;
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof ExtractionException) throw (ExtractionException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IOException("SABR request failed", failure);
    }

    private void publishBackoff(final long remainingMs) {
        if (remainingMs > 0L) {
            SabrBackoffCoordinator.getInstance().begin(appContext, this, remainingMs);
        } else {
            SabrBackoffCoordinator.getInstance().clear(appContext, this);
        }
    }

    private static boolean sleepWithinBudget(final long deadlineNs,
                                             final long requestedMs) throws IOException {
        final long remainingNs = deadlineNs - System.nanoTime();
        if (remainingNs <= 0) return false;
        sleep(Math.min(requestedMs, Math.max(1,
                TimeUnit.NANOSECONDS.toMillis(remainingNs))));
        return true;
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

    private static void ensureBudget(final long deadlineNs,
                                     @NonNull final FailureFactory failureFactory)
            throws IOException {
        if (deadlineNs - System.nanoTime() <= 0) {
            throw failureFactory.create("SABR request exceeded its budget");
        }
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

    private static final class RequestContext {
        @NonNull private final YoutubeSabrRequest request;
        @Nullable private final YoutubeSabrInfo.Format requestedAudio;

        private RequestContext(@NonNull final YoutubeSabrRequest request,
                               @Nullable final YoutubeSabrInfo.Format requestedAudio) {
            this.request = request;
            this.requestedAudio = requestedAudio;
        }
    }

    @FunctionalInterface
    private interface FailureFactory {
        @NonNull
        IOException create(@NonNull String reason);
    }
}
