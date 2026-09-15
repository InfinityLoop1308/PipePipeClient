package org.schabi.newpipe.player.playqueue;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.player.mediaitem.MediaItems;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SinglePlayQueue extends PlayQueue {
    public SinglePlayQueue(final StreamInfoItem item) {
        super(0, Collections.singletonList(MediaItems.forStreamInfoItem(item)));
    }

    public SinglePlayQueue(final StreamInfo info) {
        super(0, Collections.singletonList(MediaItems.forQueueItem(info)));
    }

    public SinglePlayQueue(final StreamInfo info, final long startPosition) {
        super(0, Collections.singletonList(MediaItems.forQueueItem(info)));
        setRecovery(getIndex(), startPosition);
    }

    public SinglePlayQueue(final List<StreamInfoItem> items, final int index) {
        super(index, playQueueItemsOf(items));
    }

    private static List<PlayerMediaItem> playQueueItemsOf(final List<StreamInfoItem> items) {
        final List<PlayerMediaItem> playQueueItems = new ArrayList<>(items.size());
        for (final StreamInfoItem item : items) {
            playQueueItems.add(MediaItems.forStreamInfoItem(item));
        }
        return playQueueItems;
    }

    @Override
    public boolean isComplete() {
        return true;
    }

    @Override
    public void fetch() {
    }
}
