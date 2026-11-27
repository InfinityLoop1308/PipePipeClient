package org.schabi.newpipe.player.helper;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecAdapter;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * A {@link DefaultRenderersFactory} which only uses {@link CustomMediaCodecVideoRenderer} as an
 * implementation of video codec renders.
 *
 * <p>
 * As no ExoPlayer extension is currently used, the reflection code used by ExoPlayer to try to
 * load video extension libraries is not needed in our case and has been removed. This should be
 * changed in the case an extension is shipped with the app, such as the AV1 one.
 * </p>
 */
public final class CustomRenderersFactory extends DefaultRenderersFactory {

    private final boolean useCustomVideoRenderer;

    public CustomRenderersFactory(final Context context, final boolean useCustomVideoRenderer) {
        super(context);
        this.useCustomVideoRenderer = useCustomVideoRenderer;
    }

    @Override
    protected void buildVideoRenderers(final Context context,
                                       @ExtensionRendererMode final int extensionRendererMode,
                                       final MediaCodecSelector mediaCodecSelector,
                                       final boolean enableDecoderFallback,
                                       final Handler eventHandler,
                                       final VideoRendererEventListener eventListener,
                                       final long allowedVideoJoiningTimeMs,
                                       final ArrayList<Renderer> out) {
        if (useCustomVideoRenderer) {
            out.add(new CustomMediaCodecVideoRenderer(context, getCodecAdapterFactory(),
                    mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback,
                    eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY));
        } else {
            super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector,
                    enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs,
                    out);
        }
    }

    @Override
    protected void buildAudioRenderers(final Context context,
                                       @ExtensionRendererMode final int extensionRendererMode,
                                       final MediaCodecSelector mediaCodecSelector,
                                       final boolean enableDecoderFallback,
                                       final AudioSink audioSink,
                                       final Handler eventHandler,
                                       final AudioRendererEventListener eventListener,
                                       final ArrayList<Renderer> out) {
        out.add(new MediaCodecAudioRenderer(context, getCodecAdapterFactory(), mediaCodecSelector,
                enableDecoderFallback, eventHandler, eventListener, audioSink) {
            @Override
            protected boolean processOutputBuffer(final long positionUs,
                    final long elapsedRealtimeUs, @Nullable final MediaCodecAdapter codec,
                    @Nullable final ByteBuffer buffer, final int bufferIndex,
                    final int bufferFlags, final int sampleCount,
                    final long bufferPresentationTimeUs, final boolean isDecodeOnlyBuffer,
                    final boolean isLastBuffer, final Format format) throws ExoPlaybackException {
                return super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer,
                        bufferIndex, bufferFlags, sampleCount, bufferPresentationTimeUs,
                        isDecodeOnlyBuffer || bufferPresentationTimeUs < getLastResetPositionUs(),
                        isLastBuffer, format);
            }
        });
    }
}
