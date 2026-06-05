package project.pipepipe.app.platform

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import project.pipepipe.app.PlaybackMode
import project.pipepipe.app.SharedContext
import project.pipepipe.app.mediasource.MediaItemFactory

@UnstableApi
class AndroidMediaController(
    private val player: Player,
    private val onStopService: () -> Unit = {}
) : PlatformMediaController {
    override val scope = CoroutineScope(Dispatchers.Main + Job())

    private val mutableIsPlaying = MutableStateFlow(player.isPlaying)
    override val isPlaying: StateFlow<Boolean> = mutableIsPlaying.asStateFlow()
    private val mutableCurrentPosition = MutableStateFlow(player.currentPosition)
    override val currentPosition: StateFlow<Long> = mutableCurrentPosition.asStateFlow()
    private val mutableDuration = MutableStateFlow(player.duration)
    override val duration: StateFlow<Long> = mutableDuration.asStateFlow()
    private val mutablePlaybackState = MutableStateFlow(player.playbackState.toPlaybackState())
    override val playbackState: StateFlow<PlaybackState> = mutablePlaybackState.asStateFlow()
    private val mutableCurrentSubtitles = MutableStateFlow<List<SubtitleCue>>(emptyList())
    override val currentSubtitles: StateFlow<List<SubtitleCue>> = mutableCurrentSubtitles.asStateFlow()
    private val mutableCurrentMediaItem =
        MutableStateFlow(player.currentMediaItem?.let(MediaItemFactory::fromMediaItem))
    override val currentMediaItem: StateFlow<PlatformMediaItem?> = mutableCurrentMediaItem.asStateFlow()
    private val mutableCurrentItemIndex = MutableStateFlow(player.currentMediaItemIndex)
    override val currentItemIndex: StateFlow<Int> = mutableCurrentItemIndex.asStateFlow()
    private val mutableRepeatMode = MutableStateFlow(player.repeatMode.toRepeatMode())
    override val repeatMode: StateFlow<RepeatMode> = mutableRepeatMode.asStateFlow()
    private val mutableShuffleModeEnabled = MutableStateFlow(player.shuffleModeEnabled)
    override val shuffleModeEnabled: StateFlow<Boolean> = mutableShuffleModeEnabled.asStateFlow()
    private val mutablePlaybackSpeed = MutableStateFlow(player.playbackParameters.speed)
    override val playbackSpeed: StateFlow<Float> = mutablePlaybackSpeed.asStateFlow()
    private val mutablePlaybackPitch = MutableStateFlow(player.playbackParameters.pitch)
    override val playbackPitch: StateFlow<Float> = mutablePlaybackPitch.asStateFlow()
    private val mutableBufferedPosition = MutableStateFlow(player.bufferedPosition)
    override val bufferedPosition: StateFlow<Long> = mutableBufferedPosition.asStateFlow()
    private val mutableAvailableResolutions = MutableStateFlow<List<ResolutionInfo>>(emptyList())
    override val availableResolutions: StateFlow<List<ResolutionInfo>> =
        mutableAvailableResolutions.asStateFlow()
    private val mutableAvailableSubtitles = MutableStateFlow<List<SubtitleInfo>>(emptyList())
    override val availableSubtitles: StateFlow<List<SubtitleInfo>> = mutableAvailableSubtitles.asStateFlow()
    private val mutableAvailableAudioLanguages = MutableStateFlow<List<AudioLanguageInfo>>(emptyList())
    override val availableAudioLanguages: StateFlow<List<AudioLanguageInfo>> =
        mutableAvailableAudioLanguages.asStateFlow()
    private val mutableCurrentAudioLanguage = MutableStateFlow("")
    override val currentAudioLanguage: StateFlow<String> = mutableCurrentAudioLanguage.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mutableIsPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            mutablePlaybackState.value = playbackState.toPlaybackState()
            mutableDuration.value = player.duration
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mutableCurrentMediaItem.value = mediaItem?.let(MediaItemFactory::fromMediaItem)
            mutableCurrentItemIndex.value = player.currentMediaItemIndex
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            mutableRepeatMode.value = repeatMode.toRepeatMode()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            mutableShuffleModeEnabled.value = shuffleModeEnabled
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            mutablePlaybackSpeed.value = playbackParameters.speed
            mutablePlaybackPitch.value = playbackParameters.pitch
        }
    }

    init {
        player.addListener(listener)
        scope.launch {
            while (isActive) {
                mutableCurrentPosition.value = player.currentPosition
                mutableBufferedPosition.value = player.bufferedPosition
                mutableDuration.value = player.duration
                delay(250)
            }
        }
    }

    override val nativePlayer: Any
        get() = player

    override fun getCurrentPositionRealtime(): Long = player.currentPosition
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stop() = player.stop()
    override fun prepare() = player.prepare()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun seekToItem(index: Int, positionMs: Long) = player.seekTo(index, positionMs)
    override fun seekToPrevious() = player.seekToPreviousMediaItem()
    override fun seekToNext() = player.seekToNextMediaItem()

    override fun setQueue(items: List<PlatformMediaItem>, startIndex: Int) {
        player.setMediaItems(items.map(MediaItemFactory::toMediaItem), startIndex, 0)
    }

    override fun setRepeatMode(mode: RepeatMode) {
        player.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }

    override fun setShuffleModeEnabled(enabled: Boolean) {
        player.shuffleModeEnabled = enabled
    }

    override fun setPlaybackParameters(speed: Float, pitch: Float) {
        player.setPlaybackParameters(androidx.media3.common.PlaybackParameters(speed, pitch))
    }

    override fun applyPlaybackMode(mode: PlaybackMode) {
        val exoPlayer = player as? ExoPlayer ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, mode == PlaybackMode.AUDIO_ONLY)
            .build()
    }

    override fun selectResolution(resolution: ResolutionInfo) {
        val exoPlayer = player as? ExoPlayer ?: return
        val group = exoPlayer.currentTracks.groups.firstOrNull {
            it.type == C.TRACK_TYPE_VIDEO && (0 until it.length).any { index ->
                it.getTrackFormat(index).height == resolution.height
            }
        } ?: return
        val index = (0 until group.length).firstOrNull {
            group.getTrackFormat(it).height == resolution.height
        } ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    override fun selectSubtitle(subtitle: SubtitleInfo) {
        val exoPlayer = player as? ExoPlayer ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(subtitle.language)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    override fun applyDefaultResolution(defaultResolution: String) {
    }

    override fun clearResolutionOverride() {
        val exoPlayer = player as? ExoPlayer ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
    }

    override fun selectAudioLanguage(language: String) {
        val exoPlayer = player as? ExoPlayer ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(language)
            .build()
    }

    override fun disableSubtitles() {
        val exoPlayer = player as? ExoPlayer ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    override fun stopService() = onStopService()
    override fun syncQueueShuffle() {
        val currentUuid = mutableCurrentMediaItem.value?.uuid
        val currentPosition = player.currentPosition
        val queue = SharedContext.queueManager.getCurrentQueue()
        val currentIndex = queue.indexOfFirst { it.uuid == currentUuid }.coerceAtLeast(0)
        player.setMediaItems(queue.map(MediaItemFactory::toMediaItem), currentIndex, currentPosition)
        player.prepare()
    }
    override fun syncQueueClear() = player.clearMediaItems()
    override fun syncQueueRemove(index: Int) = player.removeMediaItem(index)
    override fun syncQueueAppend(item: PlatformMediaItem) = player.addMediaItem(MediaItemFactory.toMediaItem(item))
    override fun syncQueueMove(from: Int, to: Int) = player.moveMediaItem(from, to)

    fun release() {
        player.removeListener(listener)
        scope.cancel()
    }

    private fun Int.toPlaybackState(): PlaybackState = when (this) {
        Player.STATE_BUFFERING -> PlaybackState.BUFFERING
        Player.STATE_READY -> PlaybackState.READY
        Player.STATE_ENDED -> PlaybackState.ENDED
        else -> PlaybackState.IDLE
    }

    private fun Int.toRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }
}
