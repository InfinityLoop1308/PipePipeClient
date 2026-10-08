package org.schabi.newpipe.util.dearrow

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Everything [DeArrowBinder] needs to know about one row.
 *
 * Nothing here depends on a resolved stream: the video id comes from the URL, and which thumbnail
 * source works is decided by the fetch results, not by the stream's type. So a row can be built
 * from a list item without any extractor work.
 */
class DeArrowRow private constructor(
    /** The 11-character YouTube video id, or null when DeArrow has nothing to say. */
    val videoId: String?,
    val serviceId: Int,
    /** The uploader's own thumbnail, or null when there is none. */
    val originalThumbnailUrl: String?,
    /**
     * Whether the binder owns the thumbnail view: it may show a placeholder while a replacement
     * is fetched and fall back to the uploader's image. False when the caller loads the image
     * itself and the binder must only ever paint over the top, as on the video detail page.
     */
    val ownsThumbnail: Boolean,
) {
    companion object {
        /** A row built from an extractor item: search results, related streams, a channel page. */
        @JvmStatic
        fun from(infoItem: InfoItem?, originalThumbnailUrl: String?): DeArrowRow {
            if (infoItem !is StreamInfoItem) {
                return DeArrowRow(null, -1, originalThumbnailUrl, true)
            }
            return DeArrowRow(
                if (infoItem.serviceId == ServiceList.YouTube.serviceId) {
                    DeArrowVideoId.fromUrl(infoItem.url)
                } else {
                    null
                },
                infoItem.serviceId,
                originalThumbnailUrl,
                true,
            )
        }

        /** A row built from a stored stream: the subscription feed and the watch history. */
        @JvmStatic
        fun fromStored(
            serviceId: Int,
            url: String?,
            originalThumbnailUrl: String?,
        ): DeArrowRow = DeArrowRow(
            if (serviceId == ServiceList.YouTube.serviceId) DeArrowVideoId.fromUrl(url) else null,
            serviceId,
            originalThumbnailUrl,
            true,
        )

        /** A row whose thumbnail the caller loads itself, as on the video detail page. */
        @JvmStatic
        fun fromDetail(serviceId: Int, url: String?): DeArrowRow = DeArrowRow(
            if (serviceId == ServiceList.YouTube.serviceId) DeArrowVideoId.fromUrl(url) else null,
            serviceId,
            null,
            false,
        )
    }
}
