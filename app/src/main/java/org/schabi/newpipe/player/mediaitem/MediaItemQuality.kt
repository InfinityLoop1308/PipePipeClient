package org.schabi.newpipe.player.mediaitem

import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * The sorted video streams and the selected index that produced a [PlayerMediaItem].
 *
 * Stored as a typed [Extras] entry under [ItemKeys.QUALITY], so it no longer lives inside a
 * media item type hierarchy.
 */
class MediaItemQuality private constructor(
    val sortedVideoStreams: List<VideoStream>,
    val selectedVideoStreamIndex: Int,
) {
    val selectedVideoStream: VideoStream?
        get() = if (selectedVideoStreamIndex < 0
            || selectedVideoStreamIndex >= sortedVideoStreams.size
        ) {
            null
        } else {
            sortedVideoStreams[selectedVideoStreamIndex]
        }

    companion object {
        @JvmStatic
        fun of(
            sortedVideoStreams: List<VideoStream>,
            selectedVideoStreamIndex: Int,
        ): MediaItemQuality = MediaItemQuality(sortedVideoStreams, selectedVideoStreamIndex)
    }
}
