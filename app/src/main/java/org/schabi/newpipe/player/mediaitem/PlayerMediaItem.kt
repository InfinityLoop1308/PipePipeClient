package org.schabi.newpipe.player.mediaitem

import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.Serializable
import java.util.Optional
import java.util.UUID

/**
 * Portable, immutable representation of one playable entry.
 *
 * It carries two levels of identity:
 * - [uuid] identifies the queue slot / playback instance. It is assigned once and survives
 *   serialization, so it can be used for queue lookups and media source replacement instead
 *   of referential equality;
 * - [mediaId] identifies the content itself (service + url) and is stable across
 *   re-resolutions.
 *
 * Strategy-specific data is exchanged through [extras] (see [ItemKeys]), never through direct
 * references between strategies. Any modification returns a new [PlayerMediaItem] instance.
 *
 * The Java-friendly [Builder] is kept so the surrounding Java player code can construct items
 * without dealing with the twelve-argument constructor.
 */
data class PlayerMediaItem(
    val uuid: String,
    val mediaId: String,
    val serviceId: Int,
    val url: String,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val streamType: StreamType,
    val errors: List<Exception> = emptyList(),
    val extras: Extras = Extras.EMPTY,
) : Serializable {

    fun withUuid(newUuid: String): PlayerMediaItem = copy(uuid = newUuid)

    fun <T> withExtra(key: Extras.Key<T>, value: T?): PlayerMediaItem =
        copy(extras = extras.plus(key, value))

    fun withErrors(newErrors: List<Exception>): PlayerMediaItem = copy(errors = newErrors)

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
        private var uploaderName: String = ""
        private var uploaderUrl: String? = null
        private var durationSeconds: Long = 0
        private var thumbnailUrl: String? = null
        private var streamType: StreamType = StreamType.NONE
        private var errors: List<Exception> = emptyList()
        private var extras: Extras = Extras.EMPTY

        constructor()

        constructor(item: PlayerMediaItem) {
            uuid = item.uuid
            mediaId = item.mediaId
            serviceId = item.serviceId
            url = item.url
            title = item.title
            uploaderName = item.uploaderName
            uploaderUrl = item.uploaderUrl
            durationSeconds = item.durationSeconds
            thumbnailUrl = item.thumbnailUrl
            streamType = item.streamType
            errors = item.errors
            extras = item.extras
        }

        fun uuid(value: String) = apply { uuid = value }

        fun mediaId(value: String) = apply { mediaId = value }

        fun serviceId(value: Int) = apply { serviceId = value }

        fun url(value: String) = apply { url = value }

        fun title(value: String) = apply { title = value }

        fun uploaderName(value: String) = apply { uploaderName = value }

        fun uploaderUrl(value: String?) = apply { uploaderUrl = value }

        fun durationSeconds(value: Long) = apply { durationSeconds = value }

        fun thumbnailUrl(value: String?) = apply { thumbnailUrl = value }

        fun streamType(value: StreamType) = apply { streamType = value }

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
            uploaderName = uploaderName,
            uploaderUrl = uploaderUrl,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrl,
            streamType = streamType,
            errors = errors,
            extras = extras,
        )
    }

    companion object {
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
    }
}
