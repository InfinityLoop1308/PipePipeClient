package project.pipepipe.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import project.pipepipe.app.platform.PlatformMediaController

object SharedContext {
    val queueManager = QueueManager()

    private val mutablePlaybackMode = MutableStateFlow(PlaybackMode.VIDEO_AUDIO)
    val playbackMode: StateFlow<PlaybackMode> = mutablePlaybackMode.asStateFlow()

    private val mutableMediaController = MutableStateFlow<PlatformMediaController?>(null)
    val mediaController: StateFlow<PlatformMediaController?> = mutableMediaController.asStateFlow()

    var platformMediaController: PlatformMediaController? = null
        set(value) {
            field = value
            mutableMediaController.value = value
            queueManager.attachController(value)
        }

    fun updatePlaybackMode(mode: PlaybackMode) {
        platformMediaController?.applyPlaybackMode(mode)
        mutablePlaybackMode.value = mode
    }
}
