package org.schabi.newpipe.player.mediaitem;

import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.util.Constants;

import androidx.annotation.NonNull;

/**
 * A dummy {@link MediaItemTag} used as a placeholder for streams that have not been resolved.
 *
 * <p>This object does not hold real metadata of any form.</p>
 **/
public final class PlaceholderTag implements MediaItemTag {
    public static final PlaceholderTag EMPTY = new PlaceholderTag(placeholderItem());
    private static final String UNKNOWN_VALUE_INTERNAL = "Placeholder";

    @NonNull
    private final PlayerMediaItem playerMediaItem;

    private PlaceholderTag(@NonNull final PlayerMediaItem playerMediaItem) {
        this.playerMediaItem = playerMediaItem;
    }

    private static PlayerMediaItem placeholderItem() {
        return new PlayerMediaItem.Builder()
                .uuid(PlayerMediaItem.newUuid())
                .mediaId(UNKNOWN_VALUE_INTERNAL)
                .serviceId(Constants.NO_SERVICE_ID)
                .url(UNKNOWN_VALUE_INTERNAL)
                .title(UNKNOWN_VALUE_INTERNAL)
                .uploaderName(UNKNOWN_VALUE_INTERNAL)
                .uploaderUrl(UNKNOWN_VALUE_INTERNAL)
                .durationSeconds(0)
                .thumbnailUrl(UNKNOWN_VALUE_INTERNAL)
                .streamType(StreamType.NONE)
                .build();
    }

    @NonNull
    @Override
    public PlayerMediaItem getPlayerMediaItem() {
        return playerMediaItem;
    }

    @NonNull
    @Override
    public MediaItemTag withPlayerMediaItem(@NonNull final PlayerMediaItem item) {
        return new PlaceholderTag(item);
    }
}
