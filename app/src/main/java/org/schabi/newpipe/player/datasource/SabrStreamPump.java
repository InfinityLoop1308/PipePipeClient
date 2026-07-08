package org.schabi.newpipe.player.datasource;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrNextRequestPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrRecoverableException;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Single consumer of a {@link YoutubeSabrSession}: one daemon thread pumps the server-driven SABR
 * stream and fills the session's (concurrent) segment cache ahead of the play head. The server
 * paces us with policy-only responses once we are far enough ahead. Both the audio and video
 * {@link SabrSegmentDataSource}s only read the cache, so they never fight over the session or block each
 * other on a network round-trip, which is exactly what starved a track in the old on-demand approach.
 */
final class SabrStreamPump {
    enum State {
        IDLE,
        REQUESTING,
        REPOSITIONING,
        THROTTLED,
        NETWORK_FAILED,
        TERMINAL,
        STOPPED
    }

    private static final String TAG = "SabrStreamPump";
    private static final long IDLE_POLL_MS = 100;     // server paced us / nothing new this round
    private static final long ERROR_RETRY_MS = 1000;  // transient network error
    private static final int MAX_CONSECUTIVE_IO_ERRORS = 5;
    // no reads for this long -> playback is gone. MUST stay above READAHEAD_CUSHION_MS: once the
    // player buffer is full it stops reading us for ~cushion seconds, and killing the pump in that
    // window left the cache to drain dry -> periodic rebuffering.
    private static final long IDLE_STOP_MS = 90_000;
    // Fallback margin the buffered edge stays ahead of actual playback when the server does not send
    // a target. The server-provided target is preferred and clamped below this ceiling.
    private static final long READAHEAD_CUSHION_MS = 10_000;
    private static final long STARTUP_READAHEAD_CUSHION_MS = 6_000;
    private static final long STARTUP_BURST_READAHEAD_CUSHION_MS = 25_000;
    // Startup bursts need to fill enough media for exact seeks, but YouTube SABR starts returning
    // policy-only responses when the reported server-side readahead gets too large. Cap only the
    // request-time player timestamp so local throttling and eviction still use the actual playhead.
    private static final long STARTUP_BURST_SERVER_AHEAD_MS = 16_000;
    private static final long STARTUP_BURST_MS = 25_000;
    private static final long SEEK_READAHEAD_CUSHION_MS = 5_000;
    private static final long SEEK_MODE_MS = 8_000;
    private static final long MIN_SERVER_READAHEAD_CUSHION_MS = 3_000;
    private static final long DEMAND_FORWARD_JUMP_MS = 30_000;
    // Hard byte ceiling on read-ahead so a high-bitrate stream can't OOM the heap. The pump is
    // intentionally tighter than the session's absolute cap so it slows down before eviction churn.
    private static final long MAX_AHEAD_BYTES = 24L * 1024 * 1024;
    // Keep this much already-played video in the cache so a short backward seek lands on cached
    // segments instead of a hole (eviction used to drop everything the reader passed, so any rewind
    // hit an evicted segment the pump never re-fetches -> dead buffer). Bounded, same order as the
    // forward cushion. Rewinds beyond this still need a session re-request (separate follow-up).
    private static final long BACK_BUFFER_MS = 12_000;
    // Fallback back-buffer used when the cache is already over the byte budget: at high bitrate (4K)
    // a 30s back-buffer + readahead exceeds MAX_AHEAD_BYTES, and since eviction can't drop segments
    // within the back-buffer window the cache can't drain -> the pump throttles forever and stalls.
    // Shrinking the back-buffer when over budget lets eviction free bytes so playback keeps fetching.
    private static final long MIN_BACK_BUFFER_MS = 2_000;
    // The back-buffer is sized by BYTES, not a fixed duration: already-played video is usually waste
    // after a seek, so keep only a small rewind cushion and let deeper rewinds re-fetch.
    private static final long BACK_BUFFER_BYTES = 4L * 1024 * 1024;

    private final YoutubeSabrSession session;
    private final SabrSessionStore.Holder holder;
    private final Localization localization;

    private volatile boolean started;
    private volatile boolean stopped;
    private volatile boolean clearCacheOnStop;
    private volatile State state = State.IDLE;
    private volatile IOException networkFailure;
    private volatile long lastReadMs;
    private volatile long lastRequestMs;
    // Set by a reader blocked on an evicted segment behind the edge (backward seek); the loop
    // repositions the session onto it next round. Single-slot: the latest rewind target wins.
    private volatile SabrSegmentRequest pendingRefetch;
    // Set by a reader blocked on a segment far AHEAD of the buffered edge (cold/forward seek:
    // SponsorBlock skip at start, resume-from-history). The forward pump fills from edge 0 and would
    // take minutes to reach it, so the loop jumps the session onto it next round. Single-slot.
    private volatile SabrSegmentRequest pendingForwardSeek;
    private volatile long pendingForwardSeekPositionMs = -1;
    // Level-triggered reader demand: if any DataSource is blocked on a concrete media segment, keep
    // driving SABR toward the oldest/lowest pending demand until it is cached. This prevents the
    // forward pump from staying throttled while a reader is already waiting just beyond the edge.
    private final Map<DemandKey, SegmentDemand> activeDemands = new ConcurrentHashMap<>();
    private volatile YoutubeSabrFormat pendingInitialization;
    private volatile long seekModeUntilMs;
    private volatile long startedAtMs;
    private Thread thread;

    SabrStreamPump(@NonNull final YoutubeSabrSession session,
                   @NonNull final SabrSessionStore.Holder holder,
                   @NonNull final Localization localization) {
        this.session = session;
        this.holder = holder;
        this.localization = localization;
    }

    /** Start (or restart, if it idled out) the pump thread, and mark the session as actively read. */
    void ensureStarted() {
        lastReadMs = System.currentTimeMillis();
        if (state == State.TERMINAL || (started && !stopped)) {
            return;
        }
        synchronized (this) {
            if (state == State.TERMINAL || (started && !stopped)) {
                return;
            }
            stopped = false;
            started = true;
            startedAtMs = System.currentTimeMillis();
            state = State.IDLE;
            thread = new Thread(this::loop, "SabrStreamPump");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Stop the pump thread and release it (called on eviction / playback teardown). */
    void stop() {
        synchronized (this) {
            stopped = true;
            clearCacheOnStop = true;
            // Don't self-interrupt: stop() is also reached from the pump thread itself via
            // evict-on-fatal, and setting our own interrupt flag could break a later blocking call.
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
    }

    @Nullable
    SabrMediaSegment getCached(@NonNull final SabrSegmentRequest request) {
        // revive the pump if it idled out: any read means playback is live again.
        ensureStarted();
        return session.getCachedSegment(request);
    }

    @Nullable
    synchronized IOException takeNetworkFailure() {
        final IOException failure = networkFailure;
        networkFailure = null;
        return failure;
    }

    boolean canRecover() {
        return state != State.REQUESTING && state != State.REPOSITIONING
                && state != State.TERMINAL;
    }

    String getStateName() {
        return state.name();
    }

    /** A reader is blocked on an evicted segment behind the buffered edge (backward seek). Ask the
     * loop to reposition the session onto it so the server re-sends from there. */
    void requestRefetchFrom(@NonNull final SabrSegmentRequest request) {
        activateSeekMode();
        pendingRefetch = request;
        ensureStarted();
        wake();
    }

    /** A reader is blocked on a segment far ahead of the buffered edge (cold/forward seek, e.g. a
     * SponsorBlock skip at the start). Ask the loop to jump the session onto it so the server streams
     * from there instead of crawling forward from the start. */
    void requestForwardSeekTo(@NonNull final SabrSegmentRequest request) {
        activateSeekMode();
        pendingForwardSeek = request;
        pendingForwardSeekPositionMs = -1;
        ensureStarted();
        wake();
    }

    void requestSegmentDemand(@NonNull final SabrSegmentRequest request,
                              @NonNull final Object readerOwner,
                              final long readerGeneration) {
        if (request.isInitializationSegment()) {
            requestInitialization(request.getFormat());
            return;
        }
        if (session.getCachedSegment(request) != null) {
            clearSegmentDemand(request, readerOwner, readerGeneration);
            return;
        }
        activateSeekMode();
        final DemandKey key = DemandKey.from(request, readerOwner, readerGeneration);
        activeDemands.computeIfAbsent(key, ignored -> new SegmentDemand(
                request, readerOwner, readerGeneration, System.currentTimeMillis()));
        ensureStarted();
        wake();
    }

    void clearSegmentDemand(@NonNull final SabrSegmentRequest request,
                            @NonNull final Object readerOwner,
                            final long readerGeneration) {
        activeDemands.remove(DemandKey.from(request, readerOwner, readerGeneration));
    }

    /** Reposition immediately when Media3 reports a seek outside its buffered sample queue. */
    void requestSeekTo(@NonNull final SabrSegmentRequest request, final boolean backward) {
        requestSeekTo(request, backward, -1);
    }

    /** Reposition immediately when Media3 reports a seek outside its buffered sample queue. */
    void requestSeekTo(@NonNull final SabrSegmentRequest request, final boolean backward,
                       final long positionMs) {
        activateSeekMode();
        if (backward) {
            pendingForwardSeek = null;
            pendingForwardSeekPositionMs = -1;
            pendingRefetch = request;
        } else {
            pendingRefetch = null;
            pendingForwardSeek = request;
            pendingForwardSeekPositionMs = positionMs;
        }
        ensureStarted();
        wake();
    }

    void noteSeekWithinCache() {
        activateSeekMode();
        ensureStarted();
        wake();
    }

    void requestInitialization(@NonNull final YoutubeSabrFormat format) {
        pendingInitialization = format;
        ensureStarted();
        wake();
    }

    private void loop() {
        int consecutiveIoErrors = 0;
        state = State.IDLE;
        try {
            while (!stopped) {
                // Don't die on completion/idle while a reposition is pending: a backward seek after
                // playback buffered to the end arrives with isComplete()=true, and breaking here
                // meant the restarted pump exited before ever processing the refetch -> the reader
                // waited forever (full buffer on rewind-after-end). prepareForRewind resets the
                // buffered head, so isComplete() turns false again once the reposition runs.
                if (pendingRefetch == null && pendingForwardSeek == null
                        && activeDemands.isEmpty() && pendingInitialization == null
                        && (System.currentTimeMillis() - lastReadMs > IDLE_STOP_MS
                                || session.isComplete())) {
                    break;
                }
                try {
                    // Drive off what the player has ACTUALLY read, not the play head: the play head
                    // freezes while buffering and that deadlocked the pump. readerHead = furthest
                    // track read; readerTail = slowest track read (safe to evict below).
                    final long readerHeadMs = holder.getReaderHeadMs();
                    // Evict what both tracks have read past, EVERY round (or a full cache never drains
                    // and the throttle latches forever -> freeze), keeping BACK_BUFFER_MS behind the
                    // reader so a short backward seek finds its segments cached. But when the cache is
                    // already over the byte budget (high bitrate), shrink the back-buffer so eviction
                    // can actually drain it, otherwise the pump throttles forever and playback stalls.
                    final long backBufferMs = session.getCachedBytes() > MAX_AHEAD_BYTES
                            ? MIN_BACK_BUFFER_MS : targetBackBufferMs();
                    session.setPlayHeadMs(Math.max(0, holder.getReaderTailMs() - backBufferMs));
                    session.evictPlayed();
                    final long edgeMs = session.getStreamState().getMinBufferedEndMs();
                    final YoutubeSabrFormat initialization = pendingInitialization;
                    if (initialization != null) {
                        pendingInitialization = null;
                        state = State.REPOSITIONING;
                        session.addDiagnosticEvent("pump_initialization itag="
                                + initialization.getItag());
                        session.prepareForInitialization(initialization);
                        pumpOnceStreaming();
                        state = State.IDLE;
                        consecutiveIoErrors = 0;
                        continue;
                    }
                    // Backward seek beyond the back-buffer: a reader is blocked on an evicted segment
                    // behind the edge. Reposition the session onto it (prepareForMediaSegment sets
                    // buffered=up-to-(seg-1) + playerTime=seg start, so the server re-sends from there)
                    // instead of fetching forward this round. Bypasses the throttle by design.
                    final SabrSegmentRequest refetch = pendingRefetch;
                    if (refetch != null) {
                        pendingRefetch = null;
                        state = State.REPOSITIONING;
                        session.addDiagnosticEvent("pump_rewind itag="
                                + refetch.getFormat().getItag()
                                + " seq=" + refetch.getSequenceNumber());
                        session.prepareForRewind(refetch);
                        pumpOnceStreaming();
                        state = State.IDLE;
                        consecutiveIoErrors = 0;
                        continue;
                    }
                    // Cold/forward seek (SponsorBlock skip, user seek far ahead): a reader is blocked
                    // on a segment far ahead of the edge. Jump the session onto it
                    // (prepareForForwardJump moves the buffered head to the target, so the edge-driven
                    // pacing follows the new position instead of ping-ponging back to the old span).
                    final SabrSegmentRequest forwardSeek = pendingForwardSeek;
                    if (forwardSeek != null) {
                        final long forwardSeekPositionMs = pendingForwardSeekPositionMs;
                        pendingForwardSeek = null;
                        pendingForwardSeekPositionMs = -1;
                        if (isSeekTargetCached(forwardSeek, forwardSeekPositionMs)) {
                            session.addDiagnosticEvent("pump_forward_cached itag="
                                    + forwardSeek.getFormat().getItag()
                                    + " seq=" + forwardSeek.getSequenceNumber()
                                    + " positionMs=" + forwardSeekPositionMs);
                            state = State.IDLE;
                            continue;
                        }
                        state = State.REPOSITIONING;
                        session.addDiagnosticEvent("pump_forward itag="
                                + forwardSeek.getFormat().getItag()
                                + " init=" + forwardSeek.isInitializationSegment()
                                + " seq=" + forwardSeek.getSequenceNumber());
                        session.prepareForForwardJump(forwardSeek);
                        pumpOnceStreaming();
                        state = State.IDLE;
                        consecutiveIoErrors = 0;
                        continue;
                    }
                    final SegmentDemand demand = selectDemand(edgeMs);
                    if (demand != null) {
                        if (session.getCachedSegment(demand.request) != null) {
                            clearDemand(demand);
                        } else {
                            final long demandStartMs = session.getStreamState()
                                    .getSegmentStartMs(demand.request.getFormat(),
                                            demand.request.getSequenceNumber());
                            if (demandStartMs < edgeMs) {
                                state = State.REPOSITIONING;
                                session.addDiagnosticEvent("pump_demand_rewind itag="
                                        + demand.request.getFormat().getItag()
                                        + " seq=" + demand.request.getSequenceNumber()
                                        + " startMs=" + demandStartMs
                                        + " edgeMs=" + edgeMs);
                                session.prepareForRewind(demand.request);
                                pumpOnceStreamingUntilCached(demand.request);
                                if (session.getCachedSegment(demand.request) != null) {
                                    clearDemand(demand);
                                }
                                state = State.IDLE;
                                consecutiveIoErrors = 0;
                                continue;
                            } else if (demandStartMs > edgeMs + DEMAND_FORWARD_JUMP_MS) {
                                state = State.REPOSITIONING;
                                session.addDiagnosticEvent("pump_demand_forward itag="
                                        + demand.request.getFormat().getItag()
                                        + " seq=" + demand.request.getSequenceNumber()
                                        + " startMs=" + demandStartMs
                                        + " edgeMs=" + edgeMs);
                                session.prepareForForwardJump(demand.request);
                                pumpOnceStreamingUntilCached(demand.request);
                                if (session.getCachedSegment(demand.request) != null) {
                                    clearDemand(demand);
                                }
                                state = State.IDLE;
                                consecutiveIoErrors = 0;
                                continue;
                            } else {
                                state = State.REQUESTING;
                                session.addDiagnosticEvent("pump_demand itag="
                                        + demand.request.getFormat().getItag()
                                        + " seq=" + demand.request.getSequenceNumber()
                                        + " startMs=" + demandStartMs
                                        + " edgeMs=" + edgeMs
                                        + " sinceMs=" + Math.max(0,
                                                System.currentTimeMillis()
                                                        - demand.sinceMs));
                                final long playerTimeMs = holder.getPlayerTimeMs();
                                final long requestPlayerTimeMs = cappedServerAheadPlayerTimeMs(
                                        playerTimeMs, edgeMs);
                                session.getStreamState().setPlayerTimeMs(requestPlayerTimeMs);
                                final int segmentCount = pumpOnceStreamingUntilCached(
                                        demand.request);
                                if (session.getCachedSegment(demand.request) != null) {
                                    clearDemand(demand);
                                }
                                state = State.IDLE;
                                consecutiveIoErrors = 0;
                                if (segmentCount == 0) {
                                    awaitWake(IDLE_POLL_MS);
                                }
                                continue;
                            }
                        }
                    }
                    final long readaheadCushionMs = targetReadaheadCushionMs();
                    final long playerTimeMs = holder.getPlayerTimeMs();
                    final long aheadMs = Math.max(0, edgeMs - playerTimeMs);
                    final boolean heartbeatDue = isHeartbeatDue();
                    final boolean throttled = (aheadMs >= readaheadCushionMs && !heartbeatDue)
                            || session.getCachedBytes() > MAX_AHEAD_BYTES;
                    if (throttled) {
                        if (state != State.THROTTLED) {
                            session.addDiagnosticEvent("pump_throttled cushionMs="
                                    + readaheadCushionMs
                                    + " unstartedReader=" + holder.hasUnstartedActiveReader()
                                    + " edgeMs=" + edgeMs
                                    + " playerTimeMs=" + playerTimeMs
                                    + " aheadMs=" + aheadMs
                                    + " readerHeadMs=" + readerHeadMs
                                    + " readerTailMs=" + holder.getReaderTailMs()
                                    + " cachedBytes=" + session.getCachedBytes()
                                    + " requestNumber=" + session.getRequestNumber());
                        }
                        state = State.THROTTLED;
                        awaitWake(IDLE_POLL_MS);
                        continue;
                    }
                    state = State.REQUESTING;
                    final long requestPlayerTimeMs = startupRequestPlayerTimeMs(playerTimeMs,
                            edgeMs);
                    session.getStreamState().setPlayerTimeMs(requestPlayerTimeMs);
                    final int segmentCount = pumpOnceStreaming();
                    state = State.IDLE;
                    consecutiveIoErrors = 0;
                    if (segmentCount == 0) {
                        awaitWake(IDLE_POLL_MS);
                    }
                } catch (final IOException e) {
                    consecutiveIoErrors++;
                    if (consecutiveIoErrors >= MAX_CONSECUTIVE_IO_ERRORS) {
                        Log.w(TAG, "SABR pump network failure "
                                + holder.videoId, e);
                        networkFailure = e;
                        state = State.NETWORK_FAILED;
                        break;
                    }
                    sleepQuietly(ERROR_RETRY_MS);
                } catch (final SabrRecoverableException e) {
                    Log.i(TAG, "SABR media failure: " + e.getMessage());
                    state = State.TERMINAL;
                    holder.failTerminal(new SabrLogicException("SABR media failure", e));
                    break;
                } catch (final ExtractionException e) {
                    if (Thread.currentThread().isInterrupted() || holder.isInvalidated()) {
                        Log.i(TAG, "SABR pump canceled video=" + holder.videoId
                                + " invalidated=" + holder.isInvalidated()
                                + " message=" + e.getMessage());
                        holder.session.addDiagnosticEvent("pump_canceled invalidated="
                                + holder.isInvalidated() + " message=" + e.getMessage());
                        break;
                    }
                    Log.i(TAG, "SABR pump fatal: " + e.getMessage());
                    state = State.TERMINAL;
                    holder.failTerminal(new SabrLogicException("SABR logic failure", e));
                    break;
                } catch (final OutOfMemoryError e) {
                    Log.e(TAG, "SABR pump OOM; evicting session " + holder.videoId, e);
                    state = State.TERMINAL;
                    holder.failTerminal(new SabrLogicException("SABR memory failure", e));
                    break;
                }
            }
        } finally {
            if (clearCacheOnStop) {
                session.clearCache();
            }
            synchronized (this) {
                stopped = true;
                if (state != State.TERMINAL && state != State.NETWORK_FAILED) {
                    state = State.STOPPED;
                }
            }
        }
    }

    private int pumpOnceStreaming() throws IOException, ExtractionException {
        try {
            return session.pumpOnceStreaming(localization);
        } finally {
            lastRequestMs = System.currentTimeMillis();
        }
    }

    private int pumpOnceStreamingUntilCached(@NonNull final SabrSegmentRequest request)
            throws IOException, ExtractionException {
        try {
            return session.pumpOnceStreamingUntilCached(localization, request);
        } finally {
            lastRequestMs = System.currentTimeMillis();
        }
    }

    private long targetReadaheadCushionMs() {
        if (isSeekMode()) {
            return SEEK_READAHEAD_CUSHION_MS;
        }
        if (isStartupBurst()) {
            return STARTUP_BURST_READAHEAD_CUSHION_MS;
        }
        if (holder.hasUnstartedActiveReader()) {
            return STARTUP_READAHEAD_CUSHION_MS;
        }
        final SabrNextRequestPolicy policy = session.getStreamState().getNextRequestPolicy();
        if (policy == null) {
            return READAHEAD_CUSHION_MS;
        }
        final int serverTargetMs = Math.max(policy.getTargetAudioReadaheadMs(),
                policy.getTargetVideoReadaheadMs());
        if (serverTargetMs <= 0) {
            return READAHEAD_CUSHION_MS;
        }
        return Math.max(MIN_SERVER_READAHEAD_CUSHION_MS,
                Math.min(READAHEAD_CUSHION_MS, serverTargetMs));
    }

    private long startupRequestPlayerTimeMs(final long playerTimeMs, final long edgeMs) {
        if (!isStartupBurst()) {
            return playerTimeMs;
        }
        return cappedServerAheadPlayerTimeMs(playerTimeMs, edgeMs);
    }

    private long cappedServerAheadPlayerTimeMs(final long playerTimeMs, final long edgeMs) {
        return Math.max(playerTimeMs, edgeMs - STARTUP_BURST_SERVER_AHEAD_MS);
    }

    private boolean isStartupBurst() {
        return startedAtMs > 0 && System.currentTimeMillis() - startedAtMs < STARTUP_BURST_MS;
    }

    private boolean isHeartbeatDue() {
        final SabrNextRequestPolicy policy = session.getStreamState().getNextRequestPolicy();
        final int maximumMs = policy == null ? -1 : policy.getMaxTimeSinceLastRequestMs();
        return maximumMs > 0 && lastRequestMs > 0
                && System.currentTimeMillis() - lastRequestMs >= maximumMs;
    }

    /** Back-buffer duration for THIS stream's bitrate, so it holds ~{@link #BACK_BUFFER_BYTES}
     * regardless of resolution. Clamped to [MIN, MAX]; falls back to the time-based default when the
     * bitrate is unknown. */
    private long targetBackBufferMs() {
        if (isSeekMode()) {
            return MIN_BACK_BUFFER_MS;
        }
        final long bitsPerSec = (long) holder.videoFormat.getBitrate()
                + Math.max(0, holder.audioFormat.getBitrate());
        if (bitsPerSec <= 0) {
            return BACK_BUFFER_MS;
        }
        final long bytesPerMs = Math.max(1, bitsPerSec / 8 / 1000);
        return Math.max(MIN_BACK_BUFFER_MS,
                Math.min(BACK_BUFFER_MS, BACK_BUFFER_BYTES / bytesPerMs));
    }

    private void activateSeekMode() {
        seekModeUntilMs = System.currentTimeMillis() + SEEK_MODE_MS;
    }

    private boolean isSeekMode() {
        return System.currentTimeMillis() < seekModeUntilMs;
    }

    private boolean isSeekTargetCached(@NonNull final SabrSegmentRequest request,
                                       final long positionMs) {
        if (session.getCachedSegment(request) == null) {
            return false;
        }
        if (request.isInitializationSegment()) {
            return true;
        }
        final YoutubeSabrFormat targetFormat = request.getFormat();
        final YoutubeSabrFormat companionFormat;
        if (targetFormat.getItag() == holder.videoFormat.getItag()) {
            companionFormat = holder.audioFormat;
        } else if (targetFormat.getItag() == holder.audioFormat.getItag()) {
            companionFormat = holder.videoFormat;
        } else {
            return true;
        }
        final long companionTimeMs = positionMs >= 0 ? positionMs
                : session.getStreamState().getSegmentStartMs(targetFormat,
                        request.getSequenceNumber());
        final int companionSequence = session.getStreamState()
                .getSegmentNumberAtOrAfterTimeMs(companionFormat, companionTimeMs);
        return session.getCachedSegment(SabrSegmentRequest.media(companionFormat,
                companionSequence)) != null;
    }

    @Nullable
    private SegmentDemand selectDemand(final long edgeMs) {
        SegmentDemand selected = null;
        long selectedStartMs = Long.MAX_VALUE;
        for (final SegmentDemand demand : activeDemands.values()) {
            if (!holder.isReaderGenerationActive(demand.readerOwner, demand.readerGeneration)
                    || session.getCachedSegment(demand.request) != null) {
                clearDemand(demand);
                continue;
            }
            final long startMs = session.getStreamState().getSegmentStartMs(
                    demand.request.getFormat(), demand.request.getSequenceNumber());
            if (selected == null || startMs < selectedStartMs
                    || (startMs == selectedStartMs && demand.sinceMs < selected.sinceMs)) {
                selected = demand;
                selectedStartMs = startMs;
            }
        }
        return selected;
    }

    private void clearDemand(@NonNull final SegmentDemand demand) {
        activeDemands.remove(DemandKey.from(demand.request, demand.readerOwner,
                demand.readerGeneration));
    }

    private static final class SegmentDemand {
        @NonNull
        private final SabrSegmentRequest request;
        @NonNull
        private final Object readerOwner;
        private final long readerGeneration;
        private final long sinceMs;

        private SegmentDemand(@NonNull final SabrSegmentRequest request,
                              @NonNull final Object readerOwner,
                              final long readerGeneration,
                              final long sinceMs) {
            this.request = request;
            this.readerOwner = readerOwner;
            this.readerGeneration = readerGeneration;
            this.sinceMs = sinceMs;
        }
    }

    private static final class DemandKey {
        private final int itag;
        private final int sequenceNumber;
        private final Object readerOwner;
        private final long readerGeneration;
        private final int ownerHash;

        private DemandKey(final int itag,
                          final int sequenceNumber,
                          @NonNull final Object readerOwner,
                          final long readerGeneration) {
            this.itag = itag;
            this.sequenceNumber = sequenceNumber;
            this.readerOwner = readerOwner;
            this.readerGeneration = readerGeneration;
            this.ownerHash = System.identityHashCode(readerOwner);
        }

        private static DemandKey from(@NonNull final SabrSegmentRequest request,
                                      @NonNull final Object readerOwner,
                                      final long readerGeneration) {
            return new DemandKey(request.getFormat().getItag(), request.getSequenceNumber(),
                    readerOwner, readerGeneration);
        }

        @Override
        public boolean equals(@Nullable final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DemandKey)) {
                return false;
            }
            final DemandKey key = (DemandKey) other;
            return itag == key.itag
                    && sequenceNumber == key.sequenceNumber
                    && readerOwner == key.readerOwner
                    && readerGeneration == key.readerGeneration;
        }

        @Override
        public int hashCode() {
            int result = itag;
            result = 31 * result + sequenceNumber;
            result = 31 * result + ownerHash;
            result = 31 * result + (int) (readerGeneration ^ (readerGeneration >>> 32));
            return result;
        }
    }

    private static void sleepQuietly(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void wake() {
        final Thread pumpThread = thread;
        if (pumpThread != null) {
            LockSupport.unpark(pumpThread);
        }
    }

    private void awaitWake(final long timeoutMs) {
        LockSupport.parkNanos(timeoutMs * 1_000_000L);
    }
}
