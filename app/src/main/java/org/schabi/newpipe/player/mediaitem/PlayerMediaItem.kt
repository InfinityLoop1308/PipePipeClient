package org.schabi.newpipe.player.mediaitem

import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.util.NO_SERVICE_ID
import java.io.Serializable
import java.util.Optional
import java.util.UUID

/**
 * Portable, immutable representation of one playable entry, used both as the value type of the
 * media item model and as the entry held by a play queue.
 *
 * It carries two levels of identity:
 * - [uuid] identifies the queue slot / playback instance. It is assigned once and survives
 *   serialization, so it can be used for queue lookups and media source replacement instead
 *   of referential equality;
 * - [mediaId] identifies the content itself (service + url) and is stable across
 *   re-resolutions.
 *
 * It only describes the content: per-slot playback state (the recovery position, whether the
 * entry was auto-enqueued) is owned by the [org.schabi.newpipe.player.playqueue.PlayQueue],
 * which is also what keeps this type comparable and portable.
 *
 * Strategy-specific data is exchanged through [extras] (see [ItemKeys]), never through direct
 * references between strategies. Any modification returns a new [PlayerMediaItem] instance.
 *
 * The Java-friendly [Builder] is kept so the surrounding Java player code can construct items
 * without dealing with the many-argument constructor. The companion object hosts the factories
 * that translate the extractor's stream types into this value type.
 */
data class PlayerMediaItem(
    val uuid: String,
    val mediaId: String,
    val serviceId: Int,
    val url: String,
    val title: String,
    val uploader: String,
    val uploaderUrl: String?,
    val duration: Long,
    val thumbnailUrl: String?,
    val streamType: StreamType,
    val isRoundPlayStream: Boolean = false,
    val startAt: Long = 0,
    val errors: List<Exception> = emptyList(),
    val extras: Extras = Extras.EMPTY,
) : Serializable {

    fun withUuid(newUuid: String): PlayerMediaItem = copy(uuid = newUuid)

    fun <T> withExtra(key: Extras.Key<T>, value: T?): PlayerMediaItem =
        copy(extras = extras.plus(key, value))

    fun withErrors(newErrors: List<Exception>): PlayerMediaItem = copy(errors = newErrors)

    /**
     * The uploader name, exposed under the extractor's naming for Java callers.
     */
    fun getUploaderName(): String = uploader

    /**
     * The resolved stream, if this item was produced from one.
     */
    val maybeStreamInfo: Optional<StreamInfo>
        get() = Optional.ofNullable(extras[ItemKeys.STREAM_INFO])

    /**
     * The selected video quality, if this item came from a video stream.
     */
    val maybeQuality: Optional<MediaItemQuality>
        get() = Optional.ofNullable(extras[ItemKeys.QUALITY])

    class Builder {
        private var uuid: String = newUuid()
        private var mediaId: String = ""
        private var serviceId: Int = 0
        private var url: String = ""
        private var title: String = ""
        private var uploader: String = ""
        private var uploaderUrl: String? = null
        private var duration: Long = 0
        private var thumbnailUrl: String? = null
        private var streamType: StreamType = StreamType.NONE
        private var isRoundPlayStream: Boolean = false
        private var startAt: Long = 0
        private var errors: List<Exception> = emptyList()
        private var extras: Extras = Extras.EMPTY

        constructor()

        constructor(item: PlayerMediaItem) {
            uuid = item.uuid
            mediaId = item.mediaId
            serviceId = item.serviceId
            url = item.url
            title = item.title
            uploader = item.uploader
            uploaderUrl = item.uploaderUrl
            duration = item.duration
            thumbnailUrl = item.thumbnailUrl
            streamType = item.streamType
            isRoundPlayStream = item.isRoundPlayStream
            startAt = item.startAt
            errors = item.errors
            extras = item.extras
        }

        fun uuid(value: String) = apply { uuid = value }

        fun mediaId(value: String) = apply { mediaId = value }

        fun serviceId(value: Int) = apply { serviceId = value }

        fun url(value: String) = apply { url = value }

        fun title(value: String) = apply { title = value }

        fun uploader(value: String) = apply { uploader = value }

        fun uploaderUrl(value: String?) = apply { uploaderUrl = value }

        fun duration(value: Long) = apply { duration = value }

        fun thumbnailUrl(value: String?) = apply { thumbnailUrl = value }

        fun streamType(value: StreamType) = apply { streamType = value }

        fun isRoundPlayStream(value: Boolean) = apply { isRoundPlayStream = value }

        fun startAt(value: Long) = apply { startAt = value }

        fun errors(value: List<Exception>) = apply { errors = value }

        fun extras(value: Extras) = apply { extras = value }

        fun <T> putExtra(key: Extras.Key<T>, value: T?) = apply {
            extras = extras.plus(key, value)
        }

        fun build(): PlayerMediaItem = PlayerMediaItem(
            uuid = uuid,
            mediaId = mediaId,
            serviceId = serviceId,
            url = url,
            title = title,
            uploader = uploader,
            uploaderUrl = uploaderUrl,
            duration = duration,
            thumbnailUrl = thumbnailUrl,
            streamType = streamType,
            isRoundPlayStream = isRoundPlayStream,
            startAt = startAt,
            errors = errors,
            extras = extras,
        )
    }

    companion object {
        private const val PLACEHOLDER = "Placeholder"

        /**
         * Builds the stable content identity of a stream.
         */
        @JvmStatic
        fun mediaIdOf(serviceId: Int, url: String): String = "$serviceId:$url"

        /**
         * @return a new instance identity, used for a single queue slot / playback instance
         */
        @JvmStatic
        fun newUuid(): String = UUID.randomUUID().toString()

        /**
         * A queue entry that has not been resolved yet, built from a list item.
         */
        @JvmStatic
        fun forStreamInfoItem(item: StreamInfoItem): PlayerMediaItem =
            Builder()
                .uuid(newUuid())
                .mediaId(mediaIdOf(item.serviceId, item.url))
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
         * A queue entry built from an already extracted stream. Unlike the two-argument
         * [forStreamInfo], it does not keep the [StreamInfo] inside [extras], so a serialized
         * play queue stays lightweight.
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
         * A stream that failed to resolve, keeping the queue item's uuid so the failed media
         * source still refers to the same queue slot.
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
        fun placeholder(): PlayerMediaItem = Builder()
            .uuid(newUuid())
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

        private fun base(streamInfo: StreamInfo): Builder =
            Builder()
                .uuid(newUuid())
                .mediaId(mediaIdOf(streamInfo.serviceId, streamInfo.url))
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
}
