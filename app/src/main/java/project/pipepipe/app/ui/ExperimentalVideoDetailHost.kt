package project.pipepipe.app.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.schabi.newpipe.info_list.PipePipeComposeTheme
import org.schabi.newpipe.util.ThemeHelper
import project.pipepipe.app.uistate.VideoDetailPageState

object ExperimentalVideoDetailHost {
    private val mutablePageState = MutableStateFlow(VideoDetailPageState.HIDDEN)
    val pageState: StateFlow<VideoDetailPageState> = mutablePageState.asStateFlow()

    @JvmStatic
    fun attach(activity: AppCompatActivity, view: ComposeView) {
        if (!ThemeHelper.shouldUseExperimentalNewUi(activity)) {
            view.visibility = View.GONE
            return
        }
        view.setContent {
            PipePipeComposeTheme(activity) {
                Content()
            }
        }
        activity.lifecycleScope.launch {
            pageState.collect {
                view.visibility = if (it == VideoDetailPageState.HIDDEN) View.GONE else View.VISIBLE
            }
        }
    }

    fun showDetail() {
        mutablePageState.value = VideoDetailPageState.DETAIL_PAGE
    }

    fun showBottomPlayer() {
        mutablePageState.value = VideoDetailPageState.BOTTOM_PLAYER
    }

    fun showFullscreen() {
        mutablePageState.value = VideoDetailPageState.FULLSCREEN_PLAYER
    }

    fun hide() {
        mutablePageState.value = VideoDetailPageState.HIDDEN
    }

    @Composable
    private fun Content() {
        val state by pageState.collectAsState()
        if (state != VideoDetailPageState.HIDDEN) {
            Box(Modifier.fillMaxSize())
        }
    }
}
