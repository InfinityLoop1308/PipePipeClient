package org.schabi.newpipe.player.resolver

import android.content.Context
import com.google.android.exoplayer2.source.MediaSource
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.PlaybackStartupTrace
import org.schabi.newpipe.player.PlayerService.PlayerType
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver.SourceType
import java.util.Optional

/**
 * Owns the audio/video [PlaybackResolver]s and decides which one to use for the current playback
 * mode. This decision used to live in [org.schabi.newpipe.player.Player.sourceOf]; the mutable
 * resolver selection state (selected stream, audio track, last [SourceType]) is now kept here
 * instead of being spread over the player.
 */
class SourceResolver(
    context: Context,
    dataSource: PlayerDataSource,
    qualityResolver: QualityResolver,
) {
    private val videoResolver = VideoPlaybackResolver(context, dataSource, qualityResolver)
    private val audioResolver = AudioPlaybackResolver(context, dataSource)

    fun resolve(
        playerType: PlayerType,
        isAudioOnly: Boolean,
        info: StreamInfo,
        initialPositionMs: Long,
        startupTraceId: Long,
    ): MediaSource? {
        PlaybackStartupTrace.mark(startupTraceId, "resolver_started")

        if (playerType == PlayerType.AUDIO) {
            // orElse() is used on purpose (eager evaluation): the video resolver must still run so
            // that its SourceType side effect stays identical to the previous Player implementation.
            val resolved = Optional.ofNullable(audioResolver.resolve(info))
                .orElse(videoResolver.resolve(info, initialPositionMs))
            PlaybackStartupTrace.mark(startupTraceId, "resolver_finished")
            return resolved
        }

        if (isAudioOnly && videoResolver.streamSourceType.orElse(
                SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
            ) == SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
        ) {
            // If the current info has only video streams with audio and if the stream is played as
            // audio, we need to use the audio resolver, otherwise the video stream will be played
            // in background.
            val resolved = Optional.ofNullable(audioResolver.resolve(info))
                .orElse(videoResolver.resolve(info, initialPositionMs))
            PlaybackStartupTrace.mark(startupTraceId, "resolver_finished")
            return resolved
        }

        // Even if the stream is played in background, we need to use the video resolver if the
        // info played is separated video-only and audio-only streams; otherwise, if the audio
        // resolver was called when the app was in background, the app will only stream audio when
        // the user come back to the app and will never fetch the video stream.
        // Note that the video is not fetched when the app is in background because the video
        // renderer is fully disabled (see Player.useVideoSource), except for HLS streams
        // (see https://github.com/google/ExoPlayer/issues/9282).
        val resolved = videoResolver.resolve(info, initialPositionMs)
        PlaybackStartupTrace.mark(startupTraceId, "resolver_finished")
        return resolved
    }

    fun setSelectedStream(stream: VideoStream) {
        videoResolver.setSelectedStream(stream)
    }

    fun getAudioTrack(): String? = videoResolver.audioTrack

    fun setAudioTrack(audioTrackId: String?) {
        videoResolver.audioTrack = audioTrackId
        audioResolver.audioTrack = audioTrackId
    }

    fun getStreamSourceType(): Optional<SourceType> = videoResolver.streamSourceType
}
