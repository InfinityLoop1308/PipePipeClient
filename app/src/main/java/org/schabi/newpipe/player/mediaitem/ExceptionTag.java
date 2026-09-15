package org.schabi.newpipe.player.mediaitem;

import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import java.util.List;

import androidx.annotation.NonNull;

/**
 * A {@link MediaItemTag} for a stream that failed to load.
 *
 * <p>Metadata is taken from the underlying {@link PlayQueueItem}, including its stable
 * {@link PlayerMediaItem#getUuid() uuid}, so the failed media source still refers to the same
 * queue slot as the stream it replaced. The errors are carried on the backing
 * {@link PlayerMediaItem}.</p>
 **/
public final class ExceptionTag implements MediaItemTag {
    @NonNull
    private final PlayerMediaItem playerMediaItem;

    private ExceptionTag(@NonNull final PlayerMediaItem playerMediaItem) {
        this.playerMediaItem = playerMediaItem;
    }

    public static ExceptionTag of(@NonNull final PlayQueueItem playQueueItem,
                                  @NonNull final List<Exception> errors) {
        return new ExceptionTag(new PlayerMediaItem.Builder()
                .uuid(playQueueItem.getUuid())
                .mediaId(PlayerMediaItem.mediaIdOf(playQueueItem.getServiceId(),
                        playQueueItem.getUrl()))
                .serviceId(playQueueItem.getServiceId())
                .url(playQueueItem.getUrl())
                .title(playQueueItem.getTitle())
                .uploaderName(playQueueItem.getUploader())
                .uploaderUrl(playQueueItem.getUploaderUrl())
                .durationSeconds(playQueueItem.getDuration())
                .thumbnailUrl(playQueueItem.getThumbnailUrl())
                .streamType(playQueueItem.getStreamType())
                .errors(errors)
                .build());
    }

    @NonNull
    @Override
    public PlayerMediaItem getPlayerMediaItem() {
        return playerMediaItem;
    }

    @NonNull
    @Override
    public MediaItemTag withPlayerMediaItem(@NonNull final PlayerMediaItem item) {
        return new ExceptionTag(item);
    }
}
