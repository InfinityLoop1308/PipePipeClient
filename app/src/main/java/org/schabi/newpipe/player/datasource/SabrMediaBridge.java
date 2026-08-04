package org.schabi.newpipe.player.datasource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.Localization;
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
    private final SabrSessionStore.Holder holder;
    private final YoutubeSabrSession session;
    private final Localization localization;
    private final SabrBackoffState backoff;
    private final LinkedBlockingQueue<SabrSegmentKey> pending = new LinkedBlockingQueue<>();
    private final Map<String, SabrSegmentKey> pendingKeys = new ConcurrentHashMap<>();
    private final Map<String, IOException> failures = new ConcurrentHashMap<>();
    private final Map<String, SabrMediaSegment> ahead = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> nextSequences = new ConcurrentHashMap<>();
    private final Deque<String> aheadOrder = new ArrayDeque<>();
    private final Object available = new Object();
    private volatile IOException networkFailure;
    private volatile boolean stopped;
    private volatile boolean started;
    private volatile long mediaProgressVersion;
    private Thread worker;

    SabrMediaBridge(@NonNull final SabrSessionStore.Holder holder,
                    @NonNull final Localization localization,
                    @NonNull final SabrBackoffState backoff) {
        this.holder = holder;
        this.session = holder.session;
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
    SabrMediaSegment getCached(@NonNull final SabrSegmentKey request) {
        return ahead.get(key(request));
    }

    @Nullable
    SabrMediaSegment awaitReadableSegment(@NonNull final SabrSegmentKey request,
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

    void discard(@NonNull final SabrSegmentKey request) {
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
    IOException takeDemandFailure(@NonNull final SabrSegmentKey request,
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
        requestSegmentDemand(SabrSegmentKey.initialization(format), this, 0);
    }

    void requestSegmentDemand(@NonNull final SabrSegmentKey request,
                              @NonNull final Object readerOwner,
                              final long readerGeneration) {
        if (ahead.containsKey(key(request))) {
            return;
        }
        if (!request.isInitialization()) {
            nextSequences.put(request.getFormat().getItag(), request.getSequenceNumber());
        }
        final String key = key(request);
        if (pendingKeys.putIfAbsent(key, request) == null) {
            pending.offer(request);
            ensureStarted();
        }
    }

    void clearSegmentDemand(@NonNull final SabrSegmentKey request,
                            @NonNull final Object readerOwner,
                            final long readerGeneration) {
        final String key = key(request);
        pendingKeys.remove(key);
        failures.remove(key);
    }

    void requestRefetchFrom(@NonNull final SabrSegmentKey request) {
        nextSequences.put(request.getFormat().getItag(), request.getSequenceNumber());
        session.clearPlaybackCookie();
        requestSegmentDemand(request, this, 0);
    }

    void requestForwardSeekTo(@NonNull final SabrSegmentKey request) {
        nextSequences.put(request.getFormat().getItag(), request.getSequenceNumber());
        session.clearPlaybackCookie();
        requestSegmentDemand(request, this, 0);
    }

    void requestSeekTo(@NonNull final SabrSegmentKey request,
                       final boolean backward,
                       final long positionMs) {
        nextSequences.put(request.getFormat().getItag(), request.getSequenceNumber());
        nextSequences.put(holder.audioFormat.getItag(), holder.audioTimeline.getSequenceAt(positionMs));
        nextSequences.put(holder.videoFormat.getItag(), holder.videoTimeline.getSequenceAt(positionMs));
        session.clearPlaybackCookie();
        requestSegmentDemand(request, this, 0);
    }

    void noteSeekWithinCache() {
        // Media3 can continue reading the already published segment window.
    }

    private void run() {
        while (!stopped) {
            try {
                backoff.awaitReady();
                final SabrSegmentKey request = pending.take();
                final String requestKey = key(request);
                if (!pendingKeys.containsKey(requestKey)) {
                    continue;
                }
                final YoutubeSabrSession.RequestResult requestResult =
                        session.requestOnce(localization, holder.getPlayerTimeMs(),
                                holder.audioTimeline, bufferedThrough(holder.audioFormat),
                                holder.videoTimeline, bufferedThrough(holder.videoFormat),
                                holder.isAudioActive(), holder.isVideoActive(),
                                holder.getPlayerTimeMs() > 1_000,
                                holder.getBandwidthEstimate(), holder.getPlaybackRate(),
                                holder.getPoToken(), segment -> {
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
                holder.observeBandwidth(requestResult.getBandwidthSample());
                pendingKeys.remove(requestKey);
                pending.removeIf(candidate -> ahead.containsKey(key(candidate)));
                for (final SabrSegmentKey candidate : pending) {
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

    private int bufferedThrough(
            @NonNull final org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo.Format format) {
        final Integer next = nextSequences.get(format.getItag());
        if (next != null) return Math.max(0, next - 1);
        return Math.max(0, holder.getTimeline(format).getSequenceAt(holder.getPlayerTimeMs()) - 1);
    }


    private static String key(@NonNull final SabrSegmentKey request) {
        return key(request.getFormat().getItag(), request.isInitialization()
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
