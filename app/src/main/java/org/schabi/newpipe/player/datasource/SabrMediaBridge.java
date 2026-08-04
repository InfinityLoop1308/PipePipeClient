package org.schabi.newpipe.player.datasource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/** Bridges Media3 segment demand to serialized SABR transactions. */
final class SabrMediaBridge {
    private final YoutubeSabrSession session;
    private final Localization localization;
    private final LinkedBlockingQueue<SabrSegmentRequest> pending = new LinkedBlockingQueue<>();
    private final Map<String, SabrSegmentRequest> pendingKeys = new ConcurrentHashMap<>();
    private final Map<String, IOException> failures = new ConcurrentHashMap<>();
    private volatile IOException networkFailure;
    private volatile boolean stopped;
    private volatile boolean started;
    private Thread worker;

    SabrMediaBridge(@NonNull final YoutubeSabrSession session,
                    @NonNull final Localization localization) {
        this.session = session;
        this.localization = localization;
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
    }

    @Nullable
    SabrMediaSegment getCached(@NonNull final SabrSegmentRequest request) {
        return session.getReadableSegment(request);
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

    void requestInitialization(@NonNull final org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo.Format format) {
        requestSegmentDemand(SabrSegmentRequest.initialization(format), this, 0);
    }

    void requestSegmentDemand(@NonNull final SabrSegmentRequest request,
                              @NonNull final Object readerOwner,
                              final long readerGeneration) {
        if (session.getCachedSegment(request) != null) {
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
        session.prepareForRewind(request);
        requestSegmentDemand(request, this, 0);
    }

    void requestForwardSeekTo(@NonNull final SabrSegmentRequest request) {
        session.prepareForForwardJump(request);
        requestSegmentDemand(request, this, 0);
    }

    void requestSeekTo(@NonNull final SabrSegmentRequest request,
                       final boolean backward,
                       final long positionMs) {
        if (backward) {
            session.prepareForRewind(request, positionMs);
        } else {
            session.prepareForForwardJump(request, positionMs);
        }
        requestSegmentDemand(request, this, 0);
    }

    void noteSeekWithinCache() {
        // Media3 can continue reading the already published segment window.
    }

    private void run() {
        while (!stopped) {
            try {
                final SabrSegmentRequest request = pending.take();
                final String requestKey = key(request);
                if (!pendingKeys.containsKey(requestKey)) {
                    continue;
                }
                session.requestOnce(localization);
                pendingKeys.remove(requestKey);
                pending.removeIf(candidate -> session.getCachedSegment(candidate) != null);
                for (final SabrSegmentRequest candidate : pending) {
                    if (session.getCachedSegment(candidate) != null) {
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
        return request.getFormat().getItag() + ":"
                + (request.isInitializationSegment() ? "init" : request.getSequenceNumber());
    }
}
