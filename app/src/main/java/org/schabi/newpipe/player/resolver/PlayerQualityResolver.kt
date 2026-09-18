package org.schabi.newpipe.player.resolver

import android.content.Context
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.util.ListHelper
import java.util.function.BooleanSupplier

/**
 * Quality selection policy for the video player.
 *
 * The default resolution comes from a different preference depending on the current player type:
 * the main/video player uses the normal default resolution, while the popup player uses the popup
 * default resolution. Instead of capturing the whole [org.schabi.newpipe.player.Player], the
 * current player type is queried through [isVideoPlayer] only when a resolution is needed.
 */
class PlayerQualityResolver(
    private val context: Context,
    private val isVideoPlayer: BooleanSupplier,
) : QualityResolver {

    override fun getDefaultResolutionIndex(sortedVideos: List<VideoStream>): Int =
        if (isVideoPlayer.asBoolean) {
            ListHelper.getDefaultResolutionIndex(context, sortedVideos)
        } else {
            ListHelper.getPopupDefaultResolutionIndex(context, sortedVideos)
        }

    override fun getOverrideResolutionIndex(
        sortedVideos: List<VideoStream>,
        selectedResolution: String,
        selectedCodec: String?,
    ): Int = ListHelper.getResolutionAndCodecIndex(selectedResolution, selectedCodec, sortedVideos)

    override fun getCurrentAudioQualityIndex(audioStreams: List<AudioStream>): Int =
        ListHelper.getDefaultAudioFormat(context, audioStreams)
}
