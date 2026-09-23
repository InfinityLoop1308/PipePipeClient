package org.schabi.newpipe.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.MediaSource;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import org.schabi.newpipe.player.playback.PlaybackListener;

/**
 * Adapts the play-queue driven {@link PlaybackListener} callbacks to {@link Player}.
 *
 * <p>This adapter exists so that {@link Player} does not have to implement
 * {@link PlaybackListener}. As long as it did, {@link #onPlaybackUnblock(MediaSource)} and
 * {@link #sourceOf(PlayerMediaItem, StreamInfo)} were forced to be {@code public} and leaked
 * ExoPlayer's {@link MediaSource} on the public API of {@link Player}.</p>
 *
 * <p>The callbacks are only forwarded; the logic still lives in {@link Player}.</p>
 */
final class PlaybackListenerAdapter implements PlaybackListener {

    @NonNull private final Player player;

    PlaybackListenerAdapter(@NonNull final Player player) {
        this.player = player;
    }

    @Override
    public boolean isApproachingPlaybackEdge(final long timeToEndMillis) {
        return player.isApproachingPlaybackEdge(timeToEndMillis);
    }

    @Override
    public void onPlaybackBlock() {
        player.onPlaybackBlock();
    }

    @Override
    public void onPlaybackUnblock(final MediaSource mediaSource) {
        player.onPlaybackUnblock(mediaSource);
    }

    @Override
    public void onPlaybackSynchronize(@NonNull final PlayerMediaItem item,
                                      final boolean wasBlocked) {
        player.onPlaybackSynchronize(item, wasBlocked);
    }

    @Nullable
    @Override
    public MediaSource sourceOf(final PlayerMediaItem item, final StreamInfo info) {
        return player.sourceOf(item, info);
    }

    @Override
    public void onPlaybackShutdown() {
        player.onPlaybackShutdown();
    }

    @Override
    public void onPlayQueueEdited() {
        player.onPlayQueueEdited();
    }
}
