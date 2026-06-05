package org.schabi.newpipe.player.datasource;

import android.net.Uri;
import android.util.Log;

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
        int waited = 0;
        while (true) {
            if (waited > 0 && waited % 8 == 0) {
                Log.i("SabrSeg", "WAIT itag=" + format.getItag() + " seq="
                        + (request.isInitializationSegment() ? "init" : request.getSequenceNumber())
                        + " sinceSeg=" + pump.millisSinceLastSegment());
            }
            waited++;
            if (canceled) {
                throw new IOException("SABR segment read canceled");
            }
            pump.ensureStarted();
            final SabrMediaSegment segment = pump.getCached(request);
            if (segment != null) {
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

    private static String box(final byte[] b, final int off) {
        if (b == null || off < 0 || off + 8 > b.length) {
            return "EOF@" + off;
        }
        final long size = ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
        final String type = new String(b, off + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return size + ":" + type;
    }

    private static int nextBox(final byte[] b, final int off) {
        if (b == null || off + 8 > b.length) {
            return b == null ? 0 : b.length;
        }
        final long size = ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
        return size <= 0 ? b.length : off + (int) size;
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
