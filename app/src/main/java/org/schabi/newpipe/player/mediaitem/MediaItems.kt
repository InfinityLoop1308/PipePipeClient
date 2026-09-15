package org.schabi.newpipe.player.mediaitem

import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.util.NO_SERVICE_ID

/**
 * Factories for the [PlayerMediaItem]s used by the player.
 *
 * These know about the app's extractor and queue types; [PlayerMediaItem] itself does not, which
 * is what keeps it portable.
 */
object MediaItems {
    private const val PLACEHOLDER = "Placeholder"

    /**
     * A resolved audio-only or live stream, without a video quality selection.
     */
    @JvmStatic
    fun forStreamInfo(streamInfo: StreamInfo): PlayerMediaItem = base(streamInfo).build()

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
        .build()

    /**
     * A stream that failed to resolve, keeping the queue item's uuid so the failed media source
     * still refers to the same queue slot.
     */
    @JvmStatic
    fun forQueueItemFailure(
        playQueueItem: PlayQueueItem,
        errors: List<Exception>,
    ): PlayerMediaItem = PlayerMediaItem.Builder()
        .uuid(playQueueItem.uuid)
        .mediaId(PlayerMediaItem.mediaIdOf(playQueueItem.serviceId, playQueueItem.url))
        .serviceId(playQueueItem.serviceId)
        .url(playQueueItem.url)
        .title(playQueueItem.title)
        .uploaderName(playQueueItem.uploader)
        .uploaderUrl(playQueueItem.uploaderUrl)
        .durationSeconds(playQueueItem.duration)
        .thumbnailUrl(playQueueItem.thumbnailUrl)
        .streamType(playQueueItem.streamType)
        .errors(errors)
        .build()

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
        .uploaderName(PLACEHOLDER)
        .uploaderUrl(PLACEHOLDER)
        .durationSeconds(0)
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
            .uploaderName(streamInfo.uploaderName)
            .uploaderUrl(streamInfo.uploaderUrl)
            .durationSeconds(streamInfo.duration)
            .thumbnailUrl(streamInfo.thumbnailUrl)
            .streamType(streamInfo.streamType)
            .putExtra(ItemKeys.STREAM_INFO, streamInfo)
}
