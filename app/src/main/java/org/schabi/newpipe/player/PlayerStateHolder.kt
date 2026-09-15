package org.schabi.newpipe.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem

/**
 * Observable state of the [Player], exposed as [StateFlow]s.
 *
 * This is the read side of the player: the UI and the strategies collect these flows instead of
 * polling getters or being notified through callbacks.
 *
 * The [Player] is the only writer. Every flow is updated from an ExoPlayer listener callback,
 * from [Player.changeState], or from the single progress ticker; consumers never write here.
 * The getters of [Player] read back [StateFlow.getValue] where the flow is authoritative.
 *
 * Continuous values ([currentPosition], [duration], [bufferedPosition]) are sampled by the
 * ticker, so they may lag behind the real ExoPlayer value. Code that needs an exact position
 * must keep using the synchronous [Player.getCurrentPosition].
 *
 * Each field is a separate flow on purpose: a single combined snapshot would make every
 * position tick recompose the whole UI.
 */
class PlayerStateHolder {

    private val _playbackState = MutableStateFlow(PlayerPlaybackState.PREFLIGHT)
    val playbackState: StateFlow<PlayerPlaybackState> = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentItem = MutableStateFlow<PlayerMediaItem?>(null)
    val currentItem: StateFlow<PlayerMediaItem?> = _currentItem.asStateFlow()

    private val _currentItemIndex = MutableStateFlow(0)
    val currentItemIndex: StateFlow<Int> = _currentItemIndex.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleModeEnabled = MutableStateFlow(false)
    val shuffleModeEnabled: StateFlow<Boolean> = _shuffleModeEnabled.asStateFlow()

    private val _playbackParameters = MutableStateFlow(PlayerPlaybackParameters(1f, 1f))
    val playbackParameters: StateFlow<PlayerPlaybackParameters> = _playbackParameters.asStateFlow()

    private val _availableStreams = MutableStateFlow<List<VideoStream>>(emptyList())
    val availableStreams: StateFlow<List<VideoStream>> = _availableStreams.asStateFlow()

    private val _availableSubtitles = MutableStateFlow<List<String>>(emptyList())
    val availableSubtitles: StateFlow<List<String>> = _availableSubtitles.asStateFlow()

    private val _availableAudioLanguages = MutableStateFlow<List<AudioStream>>(emptyList())
    val availableAudioLanguages: StateFlow<List<AudioStream>> =
        _availableAudioLanguages.asStateFlow()

    fun setPlaybackState(state: PlayerPlaybackState) {
        _playbackState.value = state
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun setCurrentItem(item: PlayerMediaItem?) {
        _currentItem.value = item
    }

    fun setCurrentItemIndex(index: Int) {
        _currentItemIndex.value = index
    }

    fun setDuration(duration: Long) {
        _duration.value = duration
    }

    fun setCurrentPosition(position: Long) {
        _currentPosition.value = position
    }

    fun setBufferedPosition(position: Long) {
        _bufferedPosition.value = position
    }

    fun setRepeatMode(repeatMode: RepeatMode) {
        _repeatMode.value = repeatMode
    }

    fun setShuffleModeEnabled(enabled: Boolean) {
        _shuffleModeEnabled.value = enabled
    }

    fun setPlaybackParameters(parameters: PlayerPlaybackParameters) {
        _playbackParameters.value = parameters
    }

    fun setAvailableStreams(streams: List<VideoStream>) {
        _availableStreams.value = streams
    }

    fun setAvailableSubtitles(languages: List<String>) {
        _availableSubtitles.value = languages
    }

    fun setAvailableAudioLanguages(audioStreams: List<AudioStream>) {
        _availableAudioLanguages.value = audioStreams
    }
}
