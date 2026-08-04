package org.schabi.newpipe.player.datasource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/** Bridges Media3 segment demand to serialized SABR transactions. */
final class SabrMediaBridge {
    private static final int MAX_AHEAD_SEGMENTS = 64;
    private final YoutubeSabrSession session;
    private final Localization localization;
    private final SabrBackoffState backoff;
    private final LinkedBlockingQueue<SabrSegmentRequest> pending = new LinkedBlockingQueue<>();
    private final Map<String, SabrSegmentRequest> pendingKeys = new ConcurrentHashMap<>();
    private final Map<String, IOException> failures = new ConcurrentHashMap<>();
    private final Map<String, SabrMediaSegment> ahead = new ConcurrentHashMap<>();
    private final Deque<String> aheadOrder = new ArrayDeque<>();
    private final Object available = new Object();
    private volatile IOException networkFailure;
    private volatile boolean stopped;
    private volatile boolean started;
    private volatile long mediaProgressVersion;
    private Thread worker;

    SabrMediaBridge(@NonNull final YoutubeSabrSession session,
                    @NonNull final Localization localization,
                    @NonNull final SabrBackoffState backoff) {
        this.session = session;
        this.localization = localization;
        this.backoff = backoff;
    }

    void seedSegments(@NonNull final List<SabrMediaSegment> segments) {
        for (final SabrMediaSegment segment : segments) {
            final String segmentKey = key(segment.getHeader().getItag(),
                    segment.getHeader().isInitSegment()
                            ? "init" : String.valueOf(segment.getHeader().getSequenceNumber()));
            final SabrMediaSegment previous = ahead.putIfAbsent(segmentKey, segment);
            if (previous != null) {
                segment.delete();
            } else {
                synchronized (available) {
                    aheadOrder.addLast(segmentKey);
                }
            }
        }
        synchronized (available) {
            trimAhead();
            available.notifyAll();
        }
    }

    synchronized void ensureStarted() {
        if (started || stopped) {
            return;
        }
        started = true;
        worker = new Thread(this::run, "SabrMediaBridge");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        stopped = true;
        final Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
        for (final SabrMediaSegment segment : ahead.values()) {
            segment.delete();
        }
        ahead.clear();
        synchronized (available) {
            aheadOrder.clear();
            available.notifyAll();
        }
    }

    @Nullable
    SabrMediaSegment getCached(@NonNull final SabrSegmentRequest request) {
        return ahead.get(key(request));
    }

    @Nullable
    SabrMediaSegment awaitReadableSegment(@NonNull final SabrSegmentRequest request,
                                          final long timeoutMs) throws InterruptedException {
        SabrMediaSegment segment = getCached(request);
        if (segment != null || timeoutMs <= 0) {
            return segment;
        }
        synchronized (available) {
            segment = getCached(request);
            if (segment == null) {
                available.wait(timeoutMs);
                segment = getCached(request);
            }
        }
        return segment;
    }

    void discard(@NonNull final SabrSegmentRequest request) {
        final String segmentKey = key(request);
        final SabrMediaSegment segment = ahead.remove(segmentKey);
        synchronized (available) {
            aheadOrder.remove(segmentKey);
        }
        if (segment != null) {
            segment.delete();
        }
    }

    @Nullable
    IOException takeNetworkFailure() {
        final IOException failure = networkFailure;
        networkFailure = null;
        return failure;
    }

    @Nullable
    IOException takeDemandFailure(@NonNull final SabrSegmentRequest request,
                                  @NonNull final Object readerOwner,
                                  final long readerGeneration) {
        return failures.remove(key(request));
    }

    boolean canRecover() {
        return !stopped && networkFailure == null;
    }

    String getStateName() {
        return stopped ? "STOPPED" : (pending.isEmpty() ? "IDLE" : "REQUESTING");
    }

    long getAheadBytes() {
        long bytes = 0;
        for (final SabrMediaSegment segment : ahead.values()) {
            bytes += segment.getLength();
        }
        return bytes;
    }

    long getMediaProgressVersion() {
        return mediaProgressVersion;
    }

    void requestInitialization(@NonNull final org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo.Format format) {
        requestSegmentDemand(SabrSegmentRequest.initialization(format), this, 0);
    }

    void requestSegmentDemand(@NonNull final SabrSegmentRequest request,
                              @NonNull final Object readerOwner,
                              final long readerGeneration) {
        if (ahead.containsKey(key(request))) {
            return;
        }
        final String key = key(request);
        if (pendingKeys.putIfAbsent(key, request) == null) {
            pending.offer(request);
            ensureStarted();
        }
    }

    void clearSegmentDemand(@NonNull final SabrSegmentRequest request,
                            @NonNull final Object readerOwner,
                            final long readerGeneration) {
        final String key = key(request);
        pendingKeys.remove(key);
        failures.remove(key);
    }

    void requestRefetchFrom(@NonNull final SabrSegmentRequest request) {
        session.getStreamState().rewindTo(request);
        requestSegmentDemand(request, this, 0);
    }

    void requestForwardSeekTo(@NonNull final SabrSegmentRequest request) {
        session.getStreamState().jumpTo(request);
        requestSegmentDemand(request, this, 0);
    }

    void requestSeekTo(@NonNull final SabrSegmentRequest request,
                       final boolean backward,
                       final long positionMs) {
        if (backward) {
            session.getStreamState().rewindTo(request, positionMs);
        } else {
            session.getStreamState().jumpTo(request, positionMs);
        }
        requestSegmentDemand(request, this, 0);
    }

    void noteSeekWithinCache() {
        // Media3 can continue reading the already published segment window.
    }

    private void run() {
        while (!stopped) {
            try {
                backoff.awaitReady();
                final SabrSegmentRequest request = pending.take();
                final String requestKey = key(request);
                if (!pendingKeys.containsKey(requestKey)) {
                    continue;
                }
                final YoutubeSabrSession.RequestResult requestResult =
                        session.requestOnce(localization, segment -> {
                    final String segmentKey = key(segment.getHeader().getItag(),
                            segment.getHeader().isInitSegment()
                                    ? "init" : String.valueOf(segment.getHeader().getSequenceNumber()));
                    final SabrMediaSegment previous = ahead.putIfAbsent(segmentKey, segment);
                    if (previous != null && previous != segment) {
                        segment.delete();
                    } else if (previous == null) {
                        mediaProgressVersion++;
                        synchronized (available) {
                            aheadOrder.addLast(segmentKey);
                            trimAhead();
                        }
                    }
                    synchronized (available) {
                        available.notifyAll();
                    }
                        });
                // Backoff is returned as request data; the owning Holder publishes it to
                // observers and gates the next request.
                backoff.update(requestResult.getBackoffMs());
                pendingKeys.remove(requestKey);
                pending.removeIf(candidate -> ahead.containsKey(key(candidate)));
                for (final SabrSegmentRequest candidate : pending) {
                    if (ahead.containsKey(key(candidate))) {
                        pendingKeys.remove(key(candidate));
                    }
                }
            } catch (final InterruptedException e) {
                if (stopped) {
                    break;
                }
                Thread.currentThread().interrupt();
                break;
            } catch (final IOException | ExtractionException e) {
                final IOException failure = e instanceof IOException
                        ? (IOException) e : new IOException("SABR request failed", e);
                networkFailure = failure;
                for (final String key : pendingKeys.keySet()) {
                    failures.put(key, failure);
                }
                pending.clear();
                pendingKeys.clear();
            }
        }
    }


    private static String key(@NonNull final SabrSegmentRequest request) {
        return key(request.getFormat().getItag(), request.isInitializationSegment()
                ? "init" : String.valueOf(request.getSequenceNumber()));
    }

    private static String key(final int itag, @NonNull final String sequence) {
        return itag + ":" + sequence;
    }

    private void trimAhead() {
        while (aheadOrder.size() > MAX_AHEAD_SEGMENTS) {
            final String oldest = aheadOrder.removeFirst();
            if (pendingKeys.containsKey(oldest)) {
                aheadOrder.addLast(oldest);
                break;
            }
            final SabrMediaSegment removed = ahead.remove(oldest);
            if (removed != null) {
                removed.delete();
            }
        }
    }
}
