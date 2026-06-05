package project.pipepipe.app.uistate

import org.schabi.newpipe.extractor.stream.StreamInfo

data class VideoDetailUiState(
    val streamInfoStack: List<StreamInfo> = emptyList(),
    val pageState: VideoDetailPageState = VideoDetailPageState.HIDDEN,
    val isLoading: Boolean = false,
    val error: Throwable? = null
) {
    val currentStreamInfo: StreamInfo?
        get() = streamInfoStack.lastOrNull()
}
