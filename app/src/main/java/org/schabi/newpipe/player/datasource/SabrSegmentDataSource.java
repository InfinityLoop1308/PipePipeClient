package org.schabi.newpipe.player.datasource;

import android.net.Uri;

import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;

import java.io.IOException;

/**
 * Tier-2 chunk source helper: a {@link DataSource} that serves exactly ONE SABR segment (the init
 * segment or one media segment) from the session cache, then ends. The chunk framework
 * ({@code ChunkSampleStream}) opens one of these per chunk, so seeking is handled by the framework
 * picking the chunk index, NOT by byte-skipping a continuous stream (which the v1 source could not
 * land).
 *
 * <p>The segment is identified by the {@link DataSpec} uri: {@code sabrseg://<itag>/init} or
 * {@code sabrseg://<itag>/<sequenceNumber>}.</p>
 */
public final class SabrSegmentDataSource implements DataSource {

    private static final long WAIT_MS = 250;
    private static final long STALL_MS = 120_000;
    // After waiting this long for a media segment that's BEHIND the buffered edge, treat it as a
    // backward seek onto an evicted segment and ask the pump to reposition the session there.
    private static final long REFETCH_AFTER_MS = 2_000;
    // If a media segment is this far AHEAD of the buffered edge after REFETCH_AFTER_MS, it's a cold
    // forward seek (SponsorBlock skip at start, resume-from-history): the pump fills forward from the
    // edge and would take minutes to reach it, so jump the session onto it instead of waiting.
    private static final long FORWARD_SEEK_AHEAD_MS = 30_000;

    private final SabrSessionStore.Holder holder;
    private final YoutubeSabrFormat format;
    private final Localization localization;
    // Prepend the init segment so each media chunk is a self-contained fmp4 (init + one fragment),
    // which a fresh FragmentedMp4Extractor parses fully. SABR's init isn't a clean standalone atom
    // boundary, so feeding it on its own (DASH-style InitializationChunk) hit an EOF mid-atom.
    private final boolean prependInit;

    @Nullable
    private Uri uri;
    @Nullable
    private byte[] data;
    private int pos;
    private boolean opened;
    private volatile boolean canceled;

    public SabrSegmentDataSource(final SabrSessionStore.Holder holder,
                                 final YoutubeSabrFormat format,
                                 final Localization localization,
                                 final boolean prependInit) {
        this.holder = holder;
        this.format = format;
        this.localization = localization;
        this.prependInit = prependInit;
    }

    @Override
    public void addTransferListener(final TransferListener transferListener) {
        // Bandwidth metering not wired for the SABR source.
    }

    @Override
    public long open(final DataSpec dataSpec) throws IOException {
        this.uri = dataSpec.uri;
        this.canceled = false;
        this.pos = (int) Math.max(0, dataSpec.position);
        final SabrSegmentRequest request = requestFromUri(dataSpec.uri);
        if (prependInit && !request.isInitializationSegment()) {
            final byte[] init = awaitSegment(SabrSegmentRequest.initialization(format));
            final byte[] media = awaitSegment(request);
            final byte[] both = new byte[init.length + media.length];
            System.arraycopy(init, 0, both, 0, init.length);
            System.arraycopy(media, 0, both, init.length, media.length);
            this.data = both;
        } else {
            this.data = awaitSegment(request);
        }
        this.opened = true;
        final int remaining = data.length - pos;
        return dataSpec.length == C.LENGTH_UNSET ? remaining : Math.min(dataSpec.length, remaining);
    }

    @Override
    public int read(final byte[] target, final int offset, final int length) {
        if (length == 0) {
            return 0;
        }
        if (data == null || pos >= data.length) {
            return C.RESULT_END_OF_INPUT;
        }
        final int toCopy = Math.min(length, data.length - pos);
        System.arraycopy(data, pos, target, offset, toCopy);
        pos += toCopy;
        return toCopy;
    }

    private SabrSegmentRequest requestFromUri(final Uri u) throws IOException {
        // sabrseg://<itag>/<init|seq>
        final String seg = u.getLastPathSegment();
        if (seg == null) {
            throw new IOException("Bad SABR segment uri: " + u);
        }
        if ("init".equals(seg)) {
            return SabrSegmentRequest.initialization(format);
        }
        try {
            return SabrSegmentRequest.media(format, Integer.parseInt(seg));
        } catch (final NumberFormatException e) {
            throw new IOException("Bad SABR segment uri: " + u, e);
        }
    }

    /** Block until the pump has cached this segment, or give up on a real stall / cancellation. */
    private byte[] awaitSegment(final SabrSegmentRequest request) throws IOException {
        final SabrStreamPump pump = holder.getPump(localization);
        final long waitStart = System.currentTimeMillis();
        long lastRefetchMs = 0;
        while (true) {
            if (canceled) {
                throw new IOException("SABR segment read canceled");
            }
            pump.ensureStarted();
            final SabrMediaSegment segment = pump.getCached(request);
            if (segment != null) {
                if (!segment.getHeader().isInitSegment()) {
                    // Tell the pump how far this track has been loaded so it keeps feeding ahead
                    // (and repositions after a seek). Without this readerHead stayed 0 and the pump
                    // throttled forever after the initial fill.
                    holder.setReaderPositionMs(format.getItag(),
                            segment.getHeader().getStartMs() + segment.getHeader().getDurationMs());
                }
                return segment.getData();
            }
            if (pump.isFatal()) {
                throw new IOException("SABR pump fatal for itag=" + format.getItag());
            }
            // Backward seek to an evicted segment behind the buffered edge: the forward pump never
            // re-fetches it, so it would never arrive. Drop our read position onto it (so eviction +
            // pacing follow the rewind, not the stale pre-seek position) and ask the pump to
            // reposition the session there. The edge check leaves a merely-slow forward fetch (the
            // segment is still ahead of the edge) to the normal pump, so forward playback is untouched.
            if (!request.isInitializationSegment()) {
                final long now = System.currentTimeMillis();
                if (now - waitStart > REFETCH_AFTER_MS && now - lastRefetchMs > REFETCH_AFTER_MS) {
                    final long edgeMs = holder.session.getStreamState().getMinBufferedEndMs();
                    final long segStartMs = holder.session.getStreamState()
                            .getSegmentStartMs(format, request.getSequenceNumber());
                    if (segStartMs < edgeMs) {
                        // Backward seek onto an evicted segment behind the edge.
                        holder.setReaderPositionMs(format.getItag(), segStartMs);
                        pump.requestRefetchFrom(request);
                        lastRefetchMs = now;
                    } else if (segStartMs > edgeMs + FORWARD_SEEK_AHEAD_MS) {
                        // Cold/forward seek far ahead of where the pump is filling (SponsorBlock skip
                        // at start, resume-from-history): jump the session onto it instead of waiting
                        // for the forward pump to crawl there. A merely-slow normal fetch (target just
                        // past the edge) stays on the pump, so steady playback is untouched.
                        holder.setReaderPositionMs(format.getItag(), segStartMs);
                        pump.requestForwardSeekTo(request);
                        lastRefetchMs = now;
                    }
                }
            }
            // Stall = THIS segment hasn't arrived within STALL_MS of us actually waiting for it. Do
            // NOT use the pump's "time since it last produced a segment": the pump legitimately stops
            // producing while throttled (buffer full, edge far ahead), so that clock goes stale and
            // the first cache miss after a long throttle false-stalls at ~STALL_MS. That was the
            // recurring ~2min freeze on longer/higher-bitrate streams.
            if (System.currentTimeMillis() - waitStart > STALL_MS) {
                throw new IOException("SABR segment stalled for itag=" + format.getItag());
            }
            try {
                Thread.sleep(WAIT_MS);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted awaiting SABR segment", ie);
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
        canceled = true;
        data = null;
        opened = false;
    }
}
