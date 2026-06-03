package org.schabi.newpipe.player.datasource;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;

import java.io.IOException;

/**
 * ExoPlayer {@link DataSource} exposing one SABR format (audio or video) as a continuous byte
 * stream: init segment then media segments. It only reads the session's concurrent cache, which a
 * single {@link SabrStreamPump} fills ahead of the play head; so the two data sources never touch
 * the network nor block each other. We end the stream past the last segment, on a pump fatal error,
 * or if the play head stays frozen long enough to call it a genuine stall.
 *
 * <p>v1: sequential read from the start, seeks skip forward, length unknown until end-of-stream.</p>
 */
public final class SabrDataSource implements DataSource {

    private static final String TAG = "SabrDataSource";

    private static final long WAIT_MS = 250;
    // only bail once the pump's been dry a while (bailing makes ExoPlayer re-open us, which unsticks
    // the flow). be patient at cold start: the ~45s WebView mint = zero segments for a bit, and most
    // underruns just sort themselves out once the pump catches up.
    // do NOT EOF early on a stall: ExoPlayer re-opens at a byte offset and our v1 byte-skip seek
    // fucks the fragmented container = frozen video. so we just ride the stall out.
    private static final long STALL_MS = 120_000;

    private final SabrSessionStore.Holder holder;
    private final YoutubeSabrFormat format;
    private final Localization localization;

    @Nullable
    private Uri uri;
    @Nullable
    private byte[] current;
    private int currentPos;
    private boolean initServed;
    private int nextSeq;
    private boolean ended;
    private long skipRemaining;
    private volatile boolean canceled;
    // SABR-DIAG: avoid spamming the WAIT log every poll; only log when the awaited segment changes.
    private int waitLoggedSeq = -2;
    private boolean waitLoggedInit;

    public SabrDataSource(final SabrSessionStore.Holder holder,
                          final YoutubeSabrFormat format,
                          final Localization localization) {
        this.holder = holder;
        this.format = format;
        this.localization = localization;
    }

    @Override
    public void addTransferListener(final TransferListener transferListener) {
        // Bandwidth metering not wired for the SABR v1 source.
    }

    @Override
    public long open(final DataSpec dataSpec) {
        this.uri = dataSpec.uri;
        this.current = null;
        this.currentPos = 0;
        this.initServed = false;
        this.nextSeq = 1; // SABR media sequence numbers are 1-based (0 is rejected)
        this.ended = false;
        this.skipRemaining = Math.max(0, dataSpec.position);
        this.canceled = false;
        Log.i(TAG, "SABR-DIAG open itag=" + format.getItag() + " pos=" + dataSpec.position);
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(final byte[] target, final int offset, final int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (ended) {
            return C.RESULT_END_OF_INPUT;
        }
        // Drop bytes for a forward seek (v1 skips from the start).
        while (skipRemaining > 0) {
            if (!ensureBuffer()) {
                return C.RESULT_END_OF_INPUT;
            }
            final int available = current.length - currentPos;
            final int drop = (int) Math.min(available, skipRemaining);
            currentPos += drop;
            skipRemaining -= drop;
        }
        if (!ensureBuffer()) {
            return C.RESULT_END_OF_INPUT;
        }
        final int available = current.length - currentPos;
        final int toCopy = Math.min(length, available);
        System.arraycopy(current, currentPos, target, offset, toCopy);
        currentPos += toCopy;
        return toCopy;
    }

    /**
     * Make sure {@link #current} has unread bytes, waiting for the pump to cache the next segment.
     *
     * @return false if the stream is exhausted
     */
    private boolean ensureBuffer() throws IOException {
        if (current != null && currentPos < current.length) {
            return true;
        }
        final SabrStreamPump pump = holder.getPump(localization);
        while (true) {
            if (canceled) {
                ended = true;
                return false;
            }
            final SabrSegmentRequest request = initServed
                    ? SabrSegmentRequest.media(format, nextSeq)
                    : SabrSegmentRequest.initialization(format);
            pump.ensureStarted();
            final SabrMediaSegment segment = pump.getCached(request);
            if (segment != null) {
                Log.i(TAG, "SABR-DIAG serve itag=" + format.getItag()
                        + (request.isInitializationSegment()
                                ? " init" : " seq=" + request.getSequenceNumber())
                        + " len=" + segment.getLength());
                if (initServed) {
                    nextSeq++;
                } else {
                    initServed = true;
                }
                current = segment.getData();
                currentPos = 0;
                if (current.length == 0) {
                    continue;
                }
                return true;
            }
            if (waitLoggedSeq != nextSeq || waitLoggedInit != initServed) {
                waitLoggedSeq = nextSeq;
                waitLoggedInit = initServed;
                Log.i(TAG, "SABR-DIAG WAIT itag=" + format.getItag()
                        + (initServed ? " want seq=" + nextSeq : " want init"));
            }
            if (holder.isBeyondEnd(request)) {
                Log.i(TAG, "SABR-DIAG EOF beyond-end itag=" + format.getItag()
                        + " seq=" + nextSeq + " init=" + !initServed);
                ended = true;
                return false;
            }
            if (pump.isFatal()) {
                // Surface a real error (not a clean EOF) so ExoPlayer reports a playback error
                // instead of pretending the video ended. The session was evicted on fatal, so a
                // retry rebuilds a fresh one.
                throw new IOException("SABR pump fatal for itag=" + format.getItag()
                        + " at seq=" + nextSeq);
            }
            // not cached yet: pump's fetching or the server's pacing us. wait, don't signal EOF
            // (that triggers a corrupting re-open). only bail if the pump's been fully dry long
            // enough to be a real dead stall.
            if (pump.millisSinceLastSegment() > STALL_MS) {
                Log.i(TAG, "end of SABR stream (stalled) itag=" + format.getItag()
                        + " at seq=" + nextSeq);
                ended = true;
                return false;
            }
            try {
                Thread.sleep(WAIT_MS);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (canceled) {
                    ended = true;
                    return false; // clean cancellation (close/seek/release), not a playback error
                }
                throw new IOException("Interrupted during SABR wait", ie);
            }
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        // Unblock a read() that is waiting for the pump (it polls this flag), so ExoPlayer can
        // release this loader thread promptly on stop/seek/track-change.
        canceled = true;
        current = null;
        currentPos = 0;
    }

    /** Factory binding a {@link SabrDataSource} to one shared session holder + format. */
    public static final class Factory implements DataSource.Factory {
        private final SabrSessionStore.Holder holder;
        private final YoutubeSabrFormat format;
        private final Localization localization;

        public Factory(final SabrSessionStore.Holder holder,
                       final YoutubeSabrFormat format,
                       final Localization localization) {
            this.holder = holder;
            this.format = format;
            this.localization = localization;
        }

        @Override
        public DataSource createDataSource() {
            return new SabrDataSource(holder, format, localization);
        }
    }
}
