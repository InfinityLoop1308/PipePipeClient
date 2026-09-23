package org.schabi.newpipe.player.mediasource;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.CompositeMediaSource;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.upstream.Allocator;

import org.schabi.newpipe.player.mediaitem.ExoMediaItems;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;

import androidx.annotation.NonNull;

final class PlaceholderMediaSource
        extends CompositeMediaSource<Void> implements ManagedMediaSource {
    public static final PlaceholderMediaSource COPY = new PlaceholderMediaSource();
    private static final MediaItem MEDIA_ITEM = ExoMediaItems.asExoMediaItem(PlayerMediaItem.placeholder());

    private PlaceholderMediaSource() { }

    @Override
    public MediaItem getMediaItem() {
        return MEDIA_ITEM;
    }

    @Override
    protected void onChildSourceInfoRefreshed(final Void id,
                                              final MediaSource mediaSource,
                                              final Timeline timeline) {
        /* Do nothing, no timeline updates or error will stall playback */
    }

    @Override
    public MediaPeriod createPeriod(final MediaPeriodId id, final Allocator allocator,
                                    final long startPositionUs) {
        return null;
    }

    @Override
    public void releasePeriod(final MediaPeriod mediaPeriod) { }

    @Override
    public boolean shouldBeReplacedWith(@NonNull final PlayerMediaItem newIdentity,
                                        final boolean isInterruptable) {
        return true;
    }

    @Override
    public boolean isStreamEqual(@NonNull final PlayerMediaItem stream) {
        return false;
    }
}
