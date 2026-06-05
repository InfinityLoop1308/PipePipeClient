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
            if (pump.millisSinceLastSegment() > STALL_MS) {
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
