package project.pipepipe.app.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.util.ExtractorHelper
import project.pipepipe.app.mediasource.MediaItemFactory
import project.pipepipe.app.uistate.VideoDetailPageState
import project.pipepipe.app.uistate.VideoDetailUiState

class VideoDetailViewModel {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mutableUiState = MutableStateFlow(VideoDetailUiState())
    val uiState: StateFlow<VideoDetailUiState> = mutableUiState.asStateFlow()

    fun open(serviceId: Int, url: String) {
        if (mutableUiState.value.currentStreamInfo?.url == url) {
            showDetail()
            return
        }
        mutableUiState.value = mutableUiState.value.copy(
            pageState = VideoDetailPageState.DETAIL_PAGE,
            isLoading = true,
            error = null
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ExtractorHelper.getStreamInfo(serviceId, url, false).blockingGet()
                }
            }.onSuccess {
                MediaItemFactory.fromStreamInfo(it)
                mutableUiState.value = mutableUiState.value.copy(
                    streamInfoStack = mutableUiState.value.streamInfoStack + it,
                    isLoading = false
                )
            }.onFailure {
                mutableUiState.value = mutableUiState.value.copy(isLoading = false, error = it)
            }
        }
    }

    fun navigateBack(): Boolean {
        val state = mutableUiState.value
        if (state.streamInfoStack.size <= 1) {
            return false
        }
        mutableUiState.value = state.copy(streamInfoStack = state.streamInfoStack.dropLast(1))
        return true
    }

    fun showDetail() {
        mutableUiState.value = mutableUiState.value.copy(pageState = VideoDetailPageState.DETAIL_PAGE)
    }

    fun showBottomPlayer() {
        mutableUiState.value = mutableUiState.value.copy(pageState = VideoDetailPageState.BOTTOM_PLAYER)
    }

    fun showFullscreen() {
        mutableUiState.value = mutableUiState.value.copy(pageState = VideoDetailPageState.FULLSCREEN_PLAYER)
    }

    fun hide() {
        mutableUiState.value = VideoDetailUiState()
    }
}
