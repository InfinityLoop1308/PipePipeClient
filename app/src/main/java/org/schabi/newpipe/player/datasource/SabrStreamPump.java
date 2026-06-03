package org.schabi.newpipe.player.datasource;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.io.IOException;
import java.util.List;

/**
 * Single consumer of a {@link YoutubeSabrSession}: one daemon thread pumps the server-driven SABR
 * stream and fills the session's (concurrent) segment cache ahead of the play head. The server
 * paces us with policy-only responses once we are far enough ahead. Both the audio and video
 * {@link SabrDataSource}s only read the cache, so they never fight over the session or block each
 * other on a network round-trip, which is exactly what starved a track in the old on-demand approach.
 */
final class SabrStreamPump {

    private static final String TAG = "SabrStreamPump";
    private static final long IDLE_POLL_MS = 400;     // server paced us / nothing new this round
    private static final long ERROR_RETRY_MS = 1000;  // transient network error
    private static final long IDLE_STOP_MS = 15_000;  // no reads for this long -> playback is gone

    private final YoutubeSabrSession session;
    private final SabrSessionStore.Holder holder;
    private final Localization localization;

    private volatile boolean started;
    private volatile boolean stopped;
    private volatile boolean fatal;
    private volatile long lastReadMs;
    private volatile long lastSegmentMs;
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
        if (fatal || (started && !stopped)) {
            return;
        }
        synchronized (this) {
            if (fatal || (started && !stopped)) {
                return;
            }
            stopped = false;
            started = true;
            lastSegmentMs = System.currentTimeMillis();
            thread = new Thread(this::loop, "SabrStreamPump");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Stop the pump thread and release it (called on eviction / playback teardown). */
    void stop() {
        synchronized (this) {
            stopped = true;
            // Don't self-interrupt: stop() is also reached from the pump thread itself via
            // evict-on-fatal, and setting our own interrupt flag could break a later blocking call.
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
    }

    /** ms since the pump last grabbed a segment. basically "is this thing dead or what". */
    long millisSinceLastSegment() {
        return System.currentTimeMillis() - lastSegmentMs;
    }

    @Nullable
    SabrMediaSegment getCached(@NonNull final SabrSegmentRequest request) {
        lastReadMs = System.currentTimeMillis();
        return session.getCachedSegment(request);
    }

    boolean isFatal() {
        return fatal;
    }

    private void loop() {
        Log.i(TAG, "SABR-DIAG pump start: aFmt=" + holder.audioFormat.getItag()
                + " vFmt=" + holder.videoFormat.getItag() + " video=" + holder.videoId);
        try {
            while (!stopped) {
                if (System.currentTimeMillis() - lastReadMs > IDLE_STOP_MS || session.isComplete()) {
                    break;
                }
                try {
                    session.getStreamState().setPlayerTimeMs(Math.max(0, holder.getPlayerTimeMs()));
                    final List<SabrMediaSegment> segments = session.pumpOnce(localization);
                    if (segments.isEmpty()) {
                        Thread.sleep(IDLE_POLL_MS);
                    } else {
                        lastSegmentMs = System.currentTimeMillis();
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (final IOException e) {
                    Log.i(TAG, "SABR-DIAG transient IO, retry: " + e.getMessage());
                    sleepQuietly(ERROR_RETRY_MS);
                } catch (final ExtractionException e) {
                    Log.w(TAG, "SABR-DIAG pump FATAL (session evicted): " + e.getMessage(), e);
                    fatal = true;
                    // Drop the dead session so a re-open rebuilds a fresh one (new token, new state).
                    SabrSessionStore.evict(holder.videoId);
                    break;
                }
            }
        } finally {
            synchronized (this) {
                stopped = true;
            }
        }
    }

    private static void sleepQuietly(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
