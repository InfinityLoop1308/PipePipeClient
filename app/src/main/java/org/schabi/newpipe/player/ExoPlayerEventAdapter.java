package org.schabi.newpipe.player;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.Player.PositionInfo;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.video.VideoSize;

/**
 * Adapts ExoPlayer's {@link com.google.android.exoplayer2.Player.Listener} callbacks to
 * {@link Player}.
 *
 * <p>This adapter exists so that {@link Player} does not have to implement ExoPlayer's listener
 * interface. As long as it did, every callback below was forced to be {@code public}, which leaked
 * ExoPlayer types ({@link PlaybackException}, {@link PlaybackParameters}, {@link Tracks},
 * {@link VideoSize}, ...) on the public API of {@link Player}.</p>
 *
 * <p>The callbacks are only forwarded; the logic still lives in {@link Player}.</p>
 */
final class ExoPlayerEventAdapter implements com.google.android.exoplayer2.Player.Listener {

    @NonNull private final Player player;

    ExoPlayerEventAdapter(@NonNull final Player player) {
        this.player = player;
    }

    @Override
    public void onEvents(@NonNull final com.google.android.exoplayer2.Player exoPlayer,
                         @NonNull final com.google.android.exoplayer2.Player.Events events) {
        // Dispatches the batched events to the individual callbacks below, which forward them to
        // the player. The player then handles the event batch itself.
        com.google.android.exoplayer2.Player.Listener.super.onEvents(exoPlayer, events);
        player.onEvents(exoPlayer, events);
    }

    @Override
    public void onPlayWhenReadyChanged(final boolean playWhenReady, final int reason) {
        player.onPlayWhenReadyChanged(playWhenReady, reason);
    }

    @Override
    public void onPlaybackStateChanged(final int playbackState) {
        player.onPlaybackStateChanged(playbackState);
    }

    @Override
    public void onIsLoadingChanged(final boolean isLoading) {
        player.onIsLoadingChanged(isLoading);
    }

    @Override
    public void onRepeatModeChanged(final int repeatMode) {
        player.onRepeatModeChanged(repeatMode);
    }

    @Override
    public void onShuffleModeEnabledChanged(final boolean shuffleModeEnabled) {
        player.onShuffleModeEnabledChanged(shuffleModeEnabled);
    }

    @Override
    public void onTracksChanged(@NonNull final Tracks tracks) {
        player.onTracksChanged(tracks);
    }

    @Override
    public void onPlaybackParametersChanged(@NonNull final PlaybackParameters parameters) {
        player.onPlaybackParametersChanged(parameters);
    }

    @Override
    public void onPositionDiscontinuity(@NonNull final PositionInfo oldPosition,
                                        @NonNull final PositionInfo newPosition,
                                        final int discontinuityReason) {
        player.onPositionDiscontinuity(oldPosition, newPosition, discontinuityReason);
    }

    @Override
    public void onRenderedFirstFrame() {
        player.onRenderedFirstFrame();
    }

    @Override
    public void onCues(@NonNull final CueGroup cueGroup) {
        player.onCues(cueGroup);
    }

    @Override
    public void onVideoSizeChanged(@NonNull final VideoSize videoSize) {
        player.onVideoSizeChanged(videoSize);
    }

    @Override
    public void onPlayerError(@NonNull final PlaybackException error) {
        player.onPlayerError(error);
    }
}
