package org.schabi.newpipe.player.mediaitem

import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.util.NO_SERVICE_ID

/**
 * Factories for the [PlayerMediaItem]s used by the player.
 *
 * These know about the app's extractor types; [PlayerMediaItem] itself only carries the values,
 * which is what keeps it portable.
 */
object MediaItems {
    private const val PLACEHOLDER = "Placeholder"

    /**
     * A queue entry that has not been resolved yet, built from a list item.
     */
    @JvmStatic
    fun forStreamInfoItem(item: StreamInfoItem): PlayerMediaItem =
        PlayerMediaItem.Builder()
            .uuid(PlayerMediaItem.newUuid())
            .mediaId(PlayerMediaItem.mediaIdOf(item.serviceId, item.url))
            .serviceId(item.serviceId)
            .url(item.url)
            .title(item.name)
            .uploader(item.uploaderName)
            .uploaderUrl(item.uploaderUrl)
            .duration(item.duration)
            .thumbnailUrl(item.thumbnailUrl)
            .streamType(item.streamType)
            .isRoundPlayStream(item.isRoundPlayStream)
            .startAt(item.startAt)
            .build()

    /**
     * A resolved audio-only or live stream, without a video quality selection.
     */
    @JvmStatic
    fun forStreamInfo(streamInfo: StreamInfo): PlayerMediaItem = base(streamInfo)
        .putExtra(ItemKeys.STREAM_INFO, streamInfo)
        .build()

    /**
     * A queue entry built from an already extracted stream. Unlike [forStreamInfo], it does not
     * keep the [StreamInfo] inside [PlayerMediaItem.extras], so a serialized play queue stays
     * lightweight.
     */
    @JvmStatic
    fun forQueueItem(streamInfo: StreamInfo): PlayerMediaItem = base(streamInfo).build()

    /**
     * A resolved video stream with its sorted streams and selected index.
     */
    @JvmStatic
    fun forStreamInfo(
        streamInfo: StreamInfo,
        sortedVideoStreams: List<VideoStream>,
        selectedVideoStreamIndex: Int,
    ): PlayerMediaItem = base(streamInfo)
        .putExtra(
            ItemKeys.QUALITY,
            MediaItemQuality.of(sortedVideoStreams, selectedVideoStreamIndex),
        )
        .putExtra(ItemKeys.STREAM_INFO, streamInfo)
        .build()

    /**
     * A stream that failed to resolve, keeping the queue item's uuid so the failed media source
     * still refers to the same queue slot.
     */
    @JvmStatic
    fun forQueueItemFailure(
        item: PlayerMediaItem,
        errors: List<Exception>,
    ): PlayerMediaItem = item.withErrors(errors)

    /**
     * A dummy item for a stream that has not been resolved yet.
     */
    @JvmStatic
    fun placeholder(): PlayerMediaItem = PlayerMediaItem.Builder()
        .uuid(PlayerMediaItem.newUuid())
        .mediaId(PLACEHOLDER)
        .serviceId(NO_SERVICE_ID)
        .url(PLACEHOLDER)
        .title(PLACEHOLDER)
        .uploader(PLACEHOLDER)
        .uploaderUrl(PLACEHOLDER)
        .duration(0)
        .thumbnailUrl(PLACEHOLDER)
        .streamType(StreamType.NONE)
        .build()

    private fun base(streamInfo: StreamInfo): PlayerMediaItem.Builder =
        PlayerMediaItem.Builder()
            .uuid(PlayerMediaItem.newUuid())
            .mediaId(PlayerMediaItem.mediaIdOf(streamInfo.serviceId, streamInfo.url))
            .serviceId(streamInfo.serviceId)
            .url(streamInfo.url)
            .title(streamInfo.name)
            .uploader(streamInfo.uploaderName)
            .uploaderUrl(streamInfo.uploaderUrl)
            .duration(streamInfo.duration)
            .thumbnailUrl(streamInfo.thumbnailUrl)
            .streamType(streamInfo.streamType)
            .isRoundPlayStream(streamInfo.isRoundPlayStream)
            .startAt(streamInfo.startAt)
}
