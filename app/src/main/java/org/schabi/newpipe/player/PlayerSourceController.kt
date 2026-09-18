package org.schabi.newpipe.player

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.trackselection.MappingTrackSelector
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver.SourceType

/**
 * Owns the audio/video source switching: minimizing a video player to audio-only (background or
 * end screen) and back, the decision whether the [org.schabi.newpipe.player.playback.MediaSourceManager]
 * must be reloaded for the new source, the SABR delivery detection and the video renderer index
 * lookup on the track selector.
 *
 * [Player] keeps only the [Player.isAudioOnly] flag itself, because [Player.sourceOf] reads it
 * when building the next media source.
 */
class PlayerSourceController(private val player: Player) {

    /**
     * This will be called when a user goes to another app/activity, turns off a screen.
     * We don't want to interrupt playback and don't want to see notification so
     * next lines of code will enable audio-only playback only if needed
     */
    fun useVideoSource(videoEnabled: Boolean) {
        val playQueue = player.playQueue
        if (playQueue == null || player.isAudioOnly == !videoEnabled || player.audioPlayerSelected()) {
            return
        }

        player.setAudioOnly(!videoEnabled)
        // When a user returns from background, controls could be hidden but SystemUI will be shown
        // 100%. Hide it.
        if (!player.isAudioOnly() && !player.isControlsVisible()) {
            player.hideSystemUIIfNeeded()
        }

        // The current metadata may be null sometimes (for e.g. when using an unstable connection
        // in livestreams) so we should be not able to execute the block below.
        // Reload the play queue manager in this case, which is the behavior when we don't know the
        // index of the video renderer or playQueueManagerReloadingNeeded returns true.
        val info = player.currentStreamInfo.orElse(null) ?: run {
            player.reloadPlayQueueManager()
            player.setRecovery()
            return
        }

        // In the case we don't know the source type, fallback to the one with video with audio or
        // audio-only source.
        val sourceType = player.sourceResolver.getStreamSourceType().orElse(
            SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
        )

        // A SABR source already exposes both audio and video, so background / foreground video
        // toggles only need to update Media3 track selection instead of rebuilding the source.
        if (!isCurrentStreamSabr()
            && playQueueManagerReloadingNeeded(sourceType, info, getVideoRendererIndex())
        ) {
            player.reloadPlayQueueManager()
        } else {
            val streamType = info.streamType
            if (streamType == StreamType.AUDIO_STREAM
                || streamType == StreamType.AUDIO_LIVE_STREAM
            ) {
                // Nothing to do more than setting the recovery position
                player.setRecovery()
                return
            }

            val parametersBuilder = player.trackSelector.buildUponParameters()

            // Enable/disable the video track and the ability to select subtitles
            parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !videoEnabled)
            parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoEnabled)

            player.trackSelector.setParameters(parametersBuilder)
        }

        player.setRecovery()
    }

    /**
     * Return whether the play queue manager needs to be reloaded when switching player type.
     *
     *
     * The play queue manager needs to be reloaded if the video renderer index is not known and if
     * the content is not an audio content, but also if none of the following cases is met:
     *
     *  * the content is an [StreamType.AUDIO_STREAM] audio stream or an
     *  [StreamType.AUDIO_LIVE_STREAM] audio live stream;
     *  * the content is a [StreamType.LIVE_STREAM] live stream and the source type is a
     *  [SourceType.LIVE_STREAM] live source;
     *  * the content's source is [SourceType.VIDEO_WITH_SEPARATED_AUDIO] a video stream
     *  with a separated audio source or has no audio-only streams available **and** is a
     *  [StreamType.LIVE_STREAM] live stream or a
     *  [StreamType.LIVE_STREAM] live stream.
     *
     *
     * @param sourceType         the [SourceType] of the stream
     * @param streamInfo         the [org.schabi.newpipe.extractor.stream.StreamInfo] of the stream
     * @param videoRendererIndex the video renderer index of the video source, if that's a video
     * source (or [.RENDERER_UNAVAILABLE])
     * @return whether the play queue manager needs to be reloaded
     */
    private fun playQueueManagerReloadingNeeded(
        sourceType: SourceType,
        streamInfo: org.schabi.newpipe.extractor.stream.StreamInfo,
        videoRendererIndex: Int,
    ): Boolean {
        val streamType = streamInfo.streamType

        if (videoRendererIndex == Player.RENDERER_UNAVAILABLE && streamType
            != StreamType.AUDIO_STREAM && streamType != StreamType.AUDIO_LIVE_STREAM
        ) {
            return true
        }

        // The content is an audio stream, an audio live stream, or a live stream with a live
        // source: it's not needed to reload the play queue manager because the stream source will
        // be the same as the current played
        if (streamType == StreamType.AUDIO_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM
            || streamType == StreamType.LIVE_STREAM && sourceType == SourceType.LIVE_STREAM
        ) {
            return false
        }

        // The content's source is a video with separated audio or a video with audio -> the video
        // and its fetch may be disabled
        // The content's source is a video with embedded audio and the content has no separated
        // audio stream available: it's probably not needed to reload the play queue manager
        // because the stream source will be probably the same as the current played
        if (sourceType == SourceType.VIDEO_WITH_SEPARATED_AUDIO
            || sourceType == SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
            && streamInfo.audioStreams.isNullOrEmpty()
        ) {
            // It's not needed to reload the play queue manager only if the content's stream type
            // is a video stream or a live stream
            return streamType != StreamType.VIDEO_STREAM && streamType != StreamType.LIVE_STREAM
        }

        // Other cases: the play queue manager reload is needed
        return true
    }

    fun isCurrentStreamSabr(): Boolean {
        return player.currentStreamInfo.map { info ->
            info.videoOnlyStreams.any { it.deliveryMethod == DeliveryMethod.SABR }
                    || info.videoStreams.any { it.deliveryMethod == DeliveryMethod.SABR }
                    || info.audioStreams.any { it.deliveryMethod == DeliveryMethod.SABR }
        }.orElse(false)
    }

    /**
     * Get the video renderer index of the current playing stream.
     *
     * This method returns the video renderer index of the current
     * [MappingTrackSelector.MappedTrackInfo] or [Player.RENDERER_UNAVAILABLE] if the current
     * [MappingTrackSelector.MappedTrackInfo] is null or if there is no video renderer index.
     *
     * @return the video renderer index or [Player.RENDERER_UNAVAILABLE] if it cannot be get
     */
    private fun getVideoRendererIndex(): Int {
        val mappedTrackInfo = player.trackSelector.currentMappedTrackInfo
            ?: return Player.RENDERER_UNAVAILABLE

        // Check every renderer
        for (i in 0 until mappedTrackInfo.rendererCount) {
            // Check the renderer is a video renderer and has at least one track
            if (!mappedTrackInfo.getTrackGroups(i).isEmpty
                && player.simpleExoPlayer.getRendererType(i) == C.TRACK_TYPE_VIDEO
            ) {
                // Return the first index found (there is at most one renderer per renderer type)
                return i
            }
        }
        // No video renderer index with at least one track found: return unavailable index
        return Player.RENDERER_UNAVAILABLE
    }
}
