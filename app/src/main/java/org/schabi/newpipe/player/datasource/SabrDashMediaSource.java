package org.schabi.newpipe.player.datasource;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.StreamKey;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.source.CompositeMediaSource;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Exposes SABR segments through Media3's normal DASH pipeline while keeping the SABR session
 * lifecycle and pump controls in PipePipe's code.
 */
public final class SabrDashMediaSource extends CompositeMediaSource<Integer> {
    private static final String TAG = "SabrDashMediaSource";
    private static final long SEEK_FORWARD_SYNC_TOLERANCE_US = 2_000_000L;

    private final MediaItem mediaItem;
    private final SabrSessionStore.Holder holder;
    private final Localization localization;
    private final DashMediaSource childSource;
    private final PlaybackState playbackState = new PlaybackState();
    private boolean released;

    public SabrDashMediaSource(final MediaItem mediaItem,
                               final SabrSessionStore.Holder holder,
                               final Localization localization) throws IOException {
        this.mediaItem = mediaItem;
        this.holder = holder;
        this.localization = localization;
        this.holder.retainSource();
        this.playbackState.setReaderOwner(this);
        preloadInitialization(holder.videoFormat);
        preloadInitialization(holder.audioFormat);
        final DataSource.Factory sabrDataSourceFactory =
                () -> new SabrSegmentDataSource(holder, playbackState.getReaderOwner(),
                        localization, /* prependInit= */ false);
        final DashManifest manifest = buildManifest(holder);
        this.childSource = new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(sabrDataSourceFactory),
                /* manifestDataSourceFactory= */ null)
                .createMediaSource(manifest, mediaItem);
        Log.d(TAG, "create source video=" + holder.videoId
                + " videoItag=" + holder.videoFormat.getItag()
                + " audioItag=" + holder.audioFormat.getItag());
    }

    private void preloadInitialization(final YoutubeSabrFormat format) throws IOException {
        if (holder.getInitializationData(format.getItag()) != null) {
            return;
        }
        final byte[] data = holder.session.fetchInitializationDataFallback(format, localization);
        holder.setInitializationData(format.getItag(), data);
    }

    @NonNull
    @Override
    public MediaItem getMediaItem() {
        return mediaItem;
    }

    @Override
    protected void prepareSourceInternal(@Nullable final TransferListener mediaTransferListener) {
        super.prepareSourceInternal(mediaTransferListener);
        prepareChildSource(0, childSource);
    }

    @Override
    protected void onChildSourceInfoRefreshed(final Integer id,
                                              final MediaSource mediaSource,
                                              final Timeline timeline) {
        refreshSourceInfo(timeline);
    }

    @Override
    public MediaPeriod createPeriod(final MediaPeriodId id, final Allocator allocator,
                                    final long startPositionUs) {
        final MediaPeriod child = childSource.createPeriod(id, allocator, startPositionUs);
        final SabrDashMediaPeriod period = new SabrDashMediaPeriod(child);
        playbackState.setReaderOwner(period);
        Log.d(TAG, "createPeriod video=" + holder.videoId + " startUs=" + startPositionUs);
        return period;
    }

    @Override
    public void releasePeriod(final MediaPeriod mediaPeriod) {
        Log.d(TAG, "releasePeriod video=" + holder.videoId);
        final SabrDashMediaPeriod period = (SabrDashMediaPeriod) mediaPeriod;
        period.release();
        childSource.releasePeriod(period.child);
    }

    @Override
    protected void releaseSourceInternal() {
        if (!released) {
            released = true;
            Log.d(TAG, "release source video=" + holder.videoId);
            holder.releaseSource();
        }
    }

    private static DashManifest buildManifest(final SabrSessionStore.Holder holder)
            throws IOException {
        final long durationMs = Math.max(holder.audioFormat.getApproxDurationMs(),
                holder.videoFormat.getApproxDurationMs());
        final String mpd = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" "
                + "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" "
                + "minBufferTime=\"PT1.5S\" mediaPresentationDuration=\""
                + formatDuration(durationMs) + "\">"
                + "<Period id=\"0\" start=\"PT0S\">"
                + adaptationSet(holder, holder.videoFormat, C.TRACK_TYPE_VIDEO)
                + adaptationSet(holder, holder.audioFormat, C.TRACK_TYPE_AUDIO)
                + "</Period></MPD>";
        try {
            return new DashManifestParser().parse(Uri.parse("sabr://" + holder.videoId),
                    new ByteArrayInputStream(mpd.getBytes(StandardCharsets.UTF_8)));
        } catch (final IOException e) {
            throw new IOException("Error when parsing generated SABR DASH manifest", e);
        }
    }

    private static String adaptationSet(final SabrSessionStore.Holder holder,
                                        final YoutubeSabrFormat format,
                                        final int trackType) {
        final String mime = containerMimeType(format);
        final String codecs = codecs(format);
        final String contentType = trackType == C.TRACK_TYPE_AUDIO ? "audio" : "video";
        final StringBuilder builder = new StringBuilder()
                .append("<AdaptationSet id=\"").append(format.getItag())
                .append("\" contentType=\"").append(contentType)
                .append("\" mimeType=\"").append(xml(mime))
                .append("\" segmentAlignment=\"true\" startWithSAP=\"1\">")
                .append("<Representation id=\"").append(format.getItag())
                .append("\" bandwidth=\"").append(Math.max(1, format.getBitrate())).append("\"");
        if (codecs != null && !codecs.isEmpty()) {
            builder.append(" codecs=\"").append(xml(codecs)).append("\"");
        }
        if (trackType == C.TRACK_TYPE_VIDEO) {
            builder.append(" width=\"").append(Math.max(1, format.getWidth()))
                    .append("\" height=\"").append(Math.max(1, format.getHeight())).append("\"");
        } else {
            builder.append(" audioSamplingRate=\"48000\"");
        }
        builder.append(">")
                .append("<BaseURL>sabrseg://").append(format.getItag()).append("/</BaseURL>")
                .append(segmentTemplate(holder, format, trackType))
                .append("</Representation></AdaptationSet>");
        return builder.toString();
    }

    private static String segmentTemplate(final SabrSessionStore.Holder holder,
                                          final YoutubeSabrFormat format,
                                          final int trackType) {
        final long endSegment = holder.session.getStreamState().getEndSegment(format);
        if (endSegment <= 0 || endSegment > 10_000) {
            final int segmentDurationMs = trackType == C.TRACK_TYPE_AUDIO ? 10_000 : 5_000;
            return "<SegmentTemplate timescale=\"1000\" startNumber=\"1\" duration=\""
                    + segmentDurationMs + "\" initialization=\"init\" media=\"$Number$\"/>";
        }
        final StringBuilder builder = new StringBuilder()
                .append("<SegmentTemplate timescale=\"1000\" startNumber=\"1\" ")
                .append("initialization=\"init\" media=\"$Number$\">")
                .append("<SegmentTimeline>");
        for (int sequence = 1; sequence <= endSegment; sequence++) {
            final long startMs = holder.session.getStreamState().getSegmentStartMs(
                    format, sequence);
            final long endMs = holder.session.getStreamState().getSegmentEndMs(format, sequence);
            final long durationMs = Math.max(1, endMs - startMs);
            builder.append("<S t=\"").append(Math.max(0, startMs))
                    .append("\" d=\"").append(durationMs).append("\"/>");
        }
        return builder.append("</SegmentTimeline></SegmentTemplate>").toString();
    }

    private static String formatDuration(final long durationMs) {
        final long safeDurationMs = Math.max(1, durationMs);
        return "PT" + (safeDurationMs / 1000) + "."
                + String.format(java.util.Locale.US, "%03d", safeDurationMs % 1000) + "S";
    }

    private static String containerMimeType(final YoutubeSabrFormat format) {
        final String mime = format.getMimeType();
        if (mime == null || mime.isEmpty()) {
            return format.isAudio() ? MimeTypes.AUDIO_MP4 : MimeTypes.VIDEO_MP4;
        }
        final int semicolon = mime.indexOf(';');
        return semicolon >= 0 ? mime.substring(0, semicolon).trim() : mime.trim();
    }

    @Nullable
    private static String codecs(final YoutubeSabrFormat format) {
        final String mime = format.getMimeType();
        if (mime == null) {
            return null;
        }
        final int start = mime.indexOf("codecs=");
        if (start < 0) {
            return null;
        }
        return mime.substring(start + "codecs=".length()).replace("\"", "").trim();
    }

    private static String xml(final String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private final class SabrDashMediaPeriod implements MediaPeriod {
        private final MediaPeriod child;
        @Nullable
        private Callback callback;
        private long preparedPositionUs = C.TIME_UNSET;
        private boolean initialPositionApplied;

        SabrDashMediaPeriod(final MediaPeriod child) {
            this.child = child;
        }

        @Override
        public void prepare(final Callback cb, final long positionUs) {
            this.callback = cb;
            this.preparedPositionUs = positionUs;
            playbackState.setReaderOwner(this);
            child.prepare(new Callback() {
                @Override
                public void onPrepared(final MediaPeriod mediaPeriod) {
                    cb.onPrepared(SabrDashMediaPeriod.this);
                }

                @Override
                public void onContinueLoadingRequested(final MediaPeriod source) {
                    cb.onContinueLoadingRequested(SabrDashMediaPeriod.this);
                }
            }, positionUs);
        }

        @Override
        public void maybeThrowPrepareError() throws IOException {
            child.maybeThrowPrepareError();
        }

        @Override
        public TrackGroupArray getTrackGroups() {
            return child.getTrackGroups();
        }

        @Override
        public List<StreamKey> getStreamKeys(final List<ExoTrackSelection> trackSelections) {
            return child.getStreamKeys(trackSelections);
        }

        @Override
        public long selectTracks(final ExoTrackSelection[] selections,
                                 final boolean[] mayRetainStreamFlags,
                                 final SampleStream[] streams,
                                 final boolean[] streamResetFlags,
                                 final long positionUs) {
            playbackState.setReaderOwner(this);
            final boolean hasActiveTracks = updateActiveTracks(selections);
            applyInitialStartPosition(positionUs, hasActiveTracks);
            return child.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags,
                    positionUs);
        }

        private boolean updateActiveTracks(final ExoTrackSelection[] selections) {
            boolean videoActive = false;
            boolean audioActive = false;
            for (final ExoTrackSelection selection : selections) {
                if (selection == null) {
                    continue;
                }
                final Format format = selection.getSelectedFormat();
                if (format != null && String.valueOf(holder.videoFormat.getItag())
                        .equals(format.id)) {
                    videoActive = true;
                } else if (format != null && String.valueOf(holder.audioFormat.getItag())
                        .equals(format.id)) {
                    audioActive = true;
                }
            }
            holder.setActiveTracks(this, videoActive, audioActive);
            Log.d(TAG, "activeTracks video=" + holder.videoId
                    + " video=" + videoActive + " audio=" + audioActive);
            return videoActive || audioActive;
        }

        private void applyInitialStartPosition(final long positionUs,
                                               final boolean hasActiveTracks) {
            if (initialPositionApplied || !hasActiveTracks) {
                return;
            }
            initialPositionApplied = true;
            final long targetUs = Math.max(validPositionUs(preparedPositionUs),
                    validPositionUs(positionUs));
            if (targetUs <= 0) {
                return;
            }
            Log.d(TAG, "initialStart video=" + holder.videoId + " positionUs=" + targetUs);
            holder.requestSeek(targetUs / 1000L, localization);
        }

        private long validPositionUs(final long positionUs) {
            return positionUs == C.TIME_UNSET ? 0 : Math.max(0, positionUs);
        }

        @Override
        public void discardBuffer(final long positionUs, final boolean toKeyframe) {
            child.discardBuffer(positionUs, toKeyframe);
        }

        @Override
        public long readDiscontinuity() {
            return child.readDiscontinuity();
        }

        @Override
        public long seekToUs(final long positionUs) {
            playbackState.setReaderOwner(this);
            holder.advanceReaderGeneration(this);
            holder.requestSeek(positionUs / 1000L, localization);
            return child.seekToUs(positionUs);
        }

        @Override
        public long getAdjustedSeekPositionUs(final long positionUs,
                                              final SeekParameters seekParameters) {
            return child.getAdjustedSeekPositionUs(
                    adjustSeekForwardToNearSegmentBoundary(positionUs, seekParameters),
                    seekParameters);
        }

        private long adjustSeekForwardToNearSegmentBoundary(final long positionUs,
                                                           final SeekParameters seekParameters) {
            if (seekParameters.toleranceAfterUs <= 0) {
                return positionUs;
            }
            final long positionMs = Math.max(0, positionUs / 1000L);
            final int currentSequence = holder.session.getStreamState()
                    .getSegmentNumberAtOrAfterTimeMs(holder.videoFormat, positionMs);
            final long nextStartMs = holder.session.getStreamState()
                    .getSegmentStartMs(holder.videoFormat, currentSequence + 1);
            final long nextStartUs = nextStartMs * 1000L;
            final long toleranceUs = Math.min(SEEK_FORWARD_SYNC_TOLERANCE_US,
                    seekParameters.toleranceAfterUs);
            if (nextStartUs > positionUs
                    && nextStartUs - positionUs <= toleranceUs) {
                return nextStartUs;
            }
            return positionUs;
        }

        @Override
        public long getBufferedPositionUs() {
            return child.getBufferedPositionUs();
        }

        @Override
        public long getNextLoadPositionUs() {
            return child.getNextLoadPositionUs();
        }

        @Override
        public boolean continueLoading(final LoadingInfo loadingInfo) {
            return child.continueLoading(loadingInfo);
        }

        @Override
        public boolean isLoading() {
            return child.isLoading();
        }

        @Override
        public void reevaluateBuffer(final long positionUs) {
            child.reevaluateBuffer(positionUs);
        }

        private void release() {
            holder.releaseTracks(this);
            if (callback != null) {
                callback = null;
            }
        }
    }

    private static final class PlaybackState {
        @NonNull
        private Object readerOwner = new Object();

        synchronized void setReaderOwner(@NonNull final Object readerOwner) {
            this.readerOwner = readerOwner;
        }

        @NonNull
        synchronized Object getReaderOwner() {
            return readerOwner;
        }
    }
}
