package org.schabi.newpipe.youtube;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrAttestationException;
import org.schabi.newpipe.extractor.services.youtube.sabr.protocol.SabrStreamingResponseReader;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Coordinates protocol-level SABR request recovery for all client consumers. */
public final class SabrRequestCoordinator {
    private static final long EMPTY_RESPONSE_RETRY_MS = 250L;
    private static final long MAX_CONTINUOUS_BACKOFF_MS = 30_000L;
    private static final long MAX_CONTINUOUS_NO_PROGRESS_MS = 30_000L;
    private static final int MAX_EMPTY_BACK_TO_BACK = 4;
    private static final long MAX_DEFERRED_WITHOUT_PROGRESS_MS = 4_000L;

    private final YoutubeSabrSession session;
    private final SabrAttestationRetryHandler attestationRetryHandler;
    private final LongConsumer backoffObserver;
    private long backoffDeadlineNs;
    private long noProgressDeadlineNs;
    private int consecutiveEmpty;
    private long stalledSinceNs;
    private long stalledBackoffMs;
    private boolean recoveryAttempted;

    public SabrRequestCoordinator(
            final YoutubeSabrSession session,
            final SabrAttestationRetryHandler attestationRetryHandler,
            final LongConsumer backoffObserver) {
        this.session = Objects.requireNonNull(session, "session");
        this.attestationRetryHandler = Objects.requireNonNull(
                attestationRetryHandler, "attestationRetryHandler");
        this.backoffObserver = backoffObserver == null ? ignored -> { } : backoffObserver;
    }

    /** Executes a logical request until it produces media. */
    public YoutubeSabrSession.RequestResult request(
            final YoutubeSabrRequest request,
            final SabrStreamingResponseReader.SegmentConsumer consumer)
            throws IOException, ExtractionException {
        return request(request, consumer, null);
    }

    /**
     * Executes a logical request until it produces progress.
     *
     * <p>{@code progressChecker} reports whether the response advanced the caller's state;
     * when null, any delivered media segment counts as progress. Server-requested backoff
     * and unproductive responses are tracked separately: backoff freezes accumulate across
     * consecutive backoff requests until a response delivers progress, while unproductive
     * responses accumulate until progress is made. Both are bounded by a continuous 30s
     * budget.
     *
     * <p>Stalls are additionally detected earlier: after {@value #MAX_EMPTY_BACK_TO_BACK}
     * consecutive empty responses or {@value #MAX_DEFERRED_WITHOUT_PROGRESS_MS}ms spent
     * deferred/backing off without progress, exactly one in-place recovery attempt is made;
     * if that attempt also yields no progress a {@link SabrRecoveryExhaustedException} is
     * thrown so the caller can rebuild the media source instead of spinning.
     */
    public YoutubeSabrSession.RequestResult request(
            final YoutubeSabrRequest request,
            final SabrStreamingResponseReader.SegmentConsumer consumer,
            final BooleanSupplier progressChecker)
            throws IOException, ExtractionException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(consumer, "consumer");
        resetRecoveryState();
        while (true) {
            awaitBackoff();
            if (recoveryAttempted) {
                sleepInPlaceRecoveryPause();
            }
            final YoutubeSabrSession.RequestResult result;
            try {
                result = session.requestOnce(request, segment -> {
                    attestationRetryHandler.onMediaReceived();
                    consumer.accept(segment);
                });
            } catch (final SabrAttestationException error) {
                attestationRetryHandler.prepareRetry(session, error);
                continue;
            }

            final boolean progress = progressChecker != null
                    ? progressChecker.getAsBoolean() : result.getSegmentCount() > 0;
            final long backoffMs = result.getBackoffMs();
            backoffObserver.accept(backoffMs);
            updateBackoffEpisode(progress, backoffMs);
            updateNoProgressEpisode(progress, backoffMs);
            if (progress) {
                resetRecoveryState();
                return result;
            }
            trackStall(result.isDeferred(), backoffMs);
            if (recoveryAttempted) {
                throw new SabrRecoveryExhaustedException(
                        "SABR in-place recovery produced no progress: empty="
                                + consecutiveEmpty + ", backoffSinceProgressMs="
                                + stalledBackoffMs,
                        stalledBackoffMs > 0);
            }
            if (isStallLimitReached()) {
                recoveryAttempted = true;
                continue;
            }
            if (result.isDeferred()) {
                continue;
            }
            sleep(EMPTY_RESPONSE_RETRY_MS);
        }
    }

    private void sleepInPlaceRecoveryPause() throws IOException {
        sleep(EMPTY_RESPONSE_RETRY_MS);
    }

    private void trackStall(final boolean deferred, final long backoffMs) {
        if (deferred || backoffMs > 0) {
            if (stalledSinceNs == 0) {
                stalledSinceNs = System.nanoTime();
            }
            stalledBackoffMs += Math.max(backoffMs, 0);
            consecutiveEmpty = 0;
            return;
        }
        consecutiveEmpty++;
    }

    private boolean isStallLimitReached() {
        if (consecutiveEmpty >= MAX_EMPTY_BACK_TO_BACK) {
            return true;
        }
        return stalledSinceNs != 0
                && (System.nanoTime() - stalledSinceNs) / 1_000_000L
                > MAX_DEFERRED_WITHOUT_PROGRESS_MS;
    }

    private void awaitBackoff() throws IOException {
        while (true) {
            final long remainingMs = session.getBackoffRemainingMs();
            backoffObserver.accept(remainingMs);
            if (remainingMs <= 0) {
                return;
            }
            throwIfBudgetExceeded(backoffDeadlineNs, remainingMs,
                    "SABR continuous backoff exceeded " + MAX_CONTINUOUS_BACKOFF_MS + "ms");
            sleep(Math.min(remainingMs, EMPTY_RESPONSE_RETRY_MS));
        }
    }

    private void updateBackoffEpisode(final boolean progress, final long backoffMs)
            throws IOException {
        if (progress) {
            backoffDeadlineNs = 0;
        }
        if (backoffMs <= 0) {
            return;
        }
        if (backoffDeadlineNs == 0) {
            backoffDeadlineNs = System.nanoTime()
                    + MAX_CONTINUOUS_BACKOFF_MS * 1_000_000L;
            return;
        }
        throwIfBudgetExceeded(backoffDeadlineNs, backoffMs,
                "SABR continuous backoff exceeded " + MAX_CONTINUOUS_BACKOFF_MS + "ms");
    }

    private void updateNoProgressEpisode(final boolean progress, final long backoffMs)
            throws IOException {
        if (progress) {
            noProgressDeadlineNs = 0;
            return;
        }
        if (noProgressDeadlineNs == 0) {
            noProgressDeadlineNs = System.nanoTime()
                    + MAX_CONTINUOUS_NO_PROGRESS_MS * 1_000_000L;
            return;
        }
        throwIfBudgetExceeded(noProgressDeadlineNs,
                backoffMs > 0 ? backoffMs : EMPTY_RESPONSE_RETRY_MS,
                "SABR continuous no-progress exceeded "
                        + MAX_CONTINUOUS_NO_PROGRESS_MS + "ms");
    }

    private static void throwIfBudgetExceeded(
            final long deadlineNs, final long waitMs, final String message)
            throws IOException {
        if (deadlineNs != 0 && waitMs * 1_000_000L > deadlineNs - System.nanoTime()) {
            throw new IOException(message);
        }
    }

    /** Zeroes the empty-response and deferred/backoff stall counters. */
    public void resetRecoveryState() {
        consecutiveEmpty = 0;
        stalledSinceNs = 0;
        stalledBackoffMs = 0;
        recoveryAttempted = false;
    }

    private static void sleep(final long milliseconds) throws IOException {
        try {
            Thread.sleep(milliseconds);
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            final InterruptedIOException interrupted = new InterruptedIOException(
                    "Interrupted during SABR request");
            interrupted.initCause(error);
            throw interrupted;
        }
    }
}
