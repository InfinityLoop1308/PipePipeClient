package org.schabi.newpipe.player.mediaitem;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;

import androidx.annotation.NonNull;

/**
 * A {@link MediaItemTag} for a resolved stream that is ready for playback.
 *
 * <p>The underlying {@link StreamInfo} and the selected {@link Quality} are stored as typed
 * {@link Extras} entries ({@link ItemKeys#STREAM_INFO} and {@link ItemKeys#QUALITY}), not as
 * hardcoded fields, so downstream strategies only depend on the extras registry.</p>
 **/
public final class StreamInfoTag implements MediaItemTag {
    @NonNull
    private final PlayerMediaItem playerMediaItem;

    private StreamInfoTag(@NonNull final PlayerMediaItem playerMediaItem) {
        this.playerMediaItem = playerMediaItem;
    }

    public static StreamInfoTag of(@NonNull final StreamInfo streamInfo,
                                   @NonNull final List<VideoStream> sortedVideoStreams,
                                   final int selectedVideoStreamIndex) {
        final Quality quality = Quality.of(sortedVideoStreams, selectedVideoStreamIndex);
        return new StreamInfoTag(base(streamInfo).putExtra(ItemKeys.QUALITY, quality).build());
    }

    public static StreamInfoTag of(@NonNull final StreamInfo streamInfo) {
        return new StreamInfoTag(base(streamInfo).build());
    }

    @NonNull
    private static PlayerMediaItem.Builder base(@NonNull final StreamInfo streamInfo) {
        return new PlayerMediaItem.Builder()
                .uuid(PlayerMediaItem.newUuid())
                .mediaId(PlayerMediaItem.mediaIdOf(streamInfo.getServiceId(),
                        streamInfo.getUrl()))
                .serviceId(streamInfo.getServiceId())
                .url(streamInfo.getUrl())
                .title(streamInfo.getName())
                .uploaderName(streamInfo.getUploaderName())
                .uploaderUrl(streamInfo.getUploaderUrl())
                .durationSeconds(streamInfo.getDuration())
                .thumbnailUrl(streamInfo.getThumbnailUrl())
                .streamType(streamInfo.getStreamType())
                .putExtra(ItemKeys.STREAM_INFO, streamInfo);
    }

    @NonNull
    @Override
    public PlayerMediaItem getPlayerMediaItem() {
        return playerMediaItem;
    }

    @NonNull
    @Override
    public MediaItemTag withPlayerMediaItem(@NonNull final PlayerMediaItem item) {
        return new StreamInfoTag(item);
    }
}
