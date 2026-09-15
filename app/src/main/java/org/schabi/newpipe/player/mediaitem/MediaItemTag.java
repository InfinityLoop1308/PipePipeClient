package org.schabi.newpipe.player.mediaitem;

import android.net.Uri;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.MediaItem.RequestMetadata;
import com.google.android.exoplayer2.MediaMetadata;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ExoPlayer-facing view over a {@link PlayerMediaItem}.
 *
 * <p>The portable state lives in {@link #getPlayerMediaItem()}; this interface only adapts it to
 * the ExoPlayer {@link MediaItem} world ({@link #asMediaItem()}) and keeps the historical
 * metadata accessors used by the player internals. Metadata that used to be hardcoded
 * ({@link StreamInfo}, {@link Quality}) is now carried as typed {@link Extras} entries, so
 * strategies can read and publish it without referencing each other.</p>
 **/
public interface MediaItemTag {

    /**
     * @return the portable, immutable representation backing this tag
     */
    @NonNull
    PlayerMediaItem getPlayerMediaItem();

    /**
     * Returns a tag of the same kind backed by {@code item}. Used to derive an updated immutable
     * media item (new uuid, new extras, new errors).
     */
    @NonNull
    MediaItemTag withPlayerMediaItem(@NonNull PlayerMediaItem item);

    @NonNull
    default Extras getExtras() {
        return getPlayerMediaItem().getExtras();
    }

    @NonNull
    default String getUuid() {
        return getPlayerMediaItem().getUuid();
    }

    @NonNull
    default String getMediaId() {
        return getPlayerMediaItem().getMediaId();
    }

    @NonNull
    default MediaItemTag withUuid(@NonNull final String uuid) {
        return withPlayerMediaItem(getPlayerMediaItem().withUuid(uuid));
    }

    @NonNull
    default <T> MediaItemTag withExtra(@NonNull final Extras.Key<T> key,
                                       @Nullable final T value) {
        return withPlayerMediaItem(getPlayerMediaItem().withExtra(key, value));
    }

    @NonNull
    default List<Exception> getErrors() {
        return getPlayerMediaItem().getErrors();
    }

    default int getServiceId() {
        return getPlayerMediaItem().getServiceId();
    }

    @NonNull
    default String getTitle() {
        return getPlayerMediaItem().getTitle();
    }

    @NonNull
    default String getUploaderName() {
        return getPlayerMediaItem().getUploaderName();
    }

    default long getDurationSeconds() {
        return getPlayerMediaItem().getDurationSeconds();
    }

    @NonNull
    default String getStreamUrl() {
        return getPlayerMediaItem().getUrl();
    }

    @Nullable
    default String getThumbnailUrl() {
        return getPlayerMediaItem().getThumbnailUrl();
    }

    @Nullable
    default String getUploaderUrl() {
        return getPlayerMediaItem().getUploaderUrl();
    }

    @NonNull
    default StreamType getStreamType() {
        return getPlayerMediaItem().getStreamType();
    }

    @NonNull
    default Optional<StreamInfo> getMaybeStreamInfo() {
        return Optional.ofNullable(getExtras().get(ItemKeys.STREAM_INFO));
    }

    @NonNull
    default Optional<Quality> getMaybeQuality() {
        return Optional.ofNullable(getExtras().get(ItemKeys.QUALITY));
    }

    /**
     * The media id handed to ExoPlayer / the media session. It must be unique within the
     * playlist, so the instance {@link #getUuid() uuid} is used. The content identity is
     * available separately as {@link PlayerMediaItem#getMediaId()}.
     */
    @NonNull
    default String makeMediaId() {
        return getUuid();
    }

    @NonNull
    default MediaItem asMediaItem() {
        final MediaMetadata.Builder mediaMetadata = new MediaMetadata.Builder()
                .setArtist(getUploaderName())
                .setDescription(getTitle())
                .setDisplayTitle(getTitle())
                .setTitle(getTitle());

        final String thumbnailUrl = getThumbnailUrl();
        if (thumbnailUrl != null) {
            mediaMetadata.setArtworkUri(Uri.parse(thumbnailUrl));
        }

        final RequestMetadata requestMetaData = new RequestMetadata.Builder()
                .setMediaUri(Uri.parse(getStreamUrl()))
                .build();

        return MediaItem.fromUri(getStreamUrl())
                .buildUpon()
                .setMediaId(makeMediaId())
                .setMediaMetadata(mediaMetadata.build())
                .setRequestMetadata(requestMetaData)
                .setTag(this)
                .build();
    }

    @NonNull
    static Optional<MediaItemTag> from(@Nullable final MediaItem mediaItem) {
        if (mediaItem == null || mediaItem.localConfiguration == null
                || !(mediaItem.localConfiguration.tag instanceof MediaItemTag)) {
            return Optional.empty();
        }

        return Optional.of((MediaItemTag) mediaItem.localConfiguration.tag);
    }

    final class Quality {
        @NonNull
        private final List<VideoStream> sortedVideoStreams;
        private final int selectedVideoStreamIndex;

        private Quality(@NonNull final List<VideoStream> sortedVideoStreams,
                        final int selectedVideoStreamIndex) {
            this.sortedVideoStreams = sortedVideoStreams;
            this.selectedVideoStreamIndex = selectedVideoStreamIndex;
        }

        static Quality of(@NonNull final List<VideoStream> sortedVideoStreams,
                          final int selectedVideoStreamIndex) {
            return new Quality(sortedVideoStreams, selectedVideoStreamIndex);
        }

        @NonNull
        public List<VideoStream> getSortedVideoStreams() {
            return sortedVideoStreams;
        }

        public int getSelectedVideoStreamIndex() {
            return selectedVideoStreamIndex;
        }

        @Nullable
        public VideoStream getSelectedVideoStream() {
            return selectedVideoStreamIndex < 0
                    || selectedVideoStreamIndex >= sortedVideoStreams.size()
                    ? null : sortedVideoStreams.get(selectedVideoStreamIndex);
        }
    }
}
