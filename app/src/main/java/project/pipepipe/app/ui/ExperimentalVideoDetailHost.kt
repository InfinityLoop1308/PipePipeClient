package project.pipepipe.app.ui

import android.view.View
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.schabi.newpipe.info_list.PipePipeComposeTheme
import org.schabi.newpipe.util.ThemeHelper
import project.pipepipe.app.service.PlaybackService
import project.pipepipe.app.SharedContext
import project.pipepipe.app.ui.screens.videodetail.VideoDetailScreen
import project.pipepipe.app.uistate.VideoDetailPageState
import project.pipepipe.app.viewmodel.VideoDetailViewModel

object ExperimentalVideoDetailHost {
    val viewModel = VideoDetailViewModel()

    @JvmStatic
    fun attach(activity: AppCompatActivity, view: ComposeView) {
        if (!ThemeHelper.shouldUseExperimentalNewUi(activity)) {
            view.visibility = View.GONE
            return
        }
        activity.startService(Intent(activity, PlaybackService::class.java))
        view.setContent {
            PipePipeComposeTheme(activity) {
                VideoDetailScreen(viewModel)
            }
        }
        activity.lifecycleScope.launch {
            viewModel.uiState.collectLatest {
                view.visibility =
                    if (it.pageState == project.pipepipe.app.uistate.VideoDetailPageState.HIDDEN) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                if (it.pageState == project.pipepipe.app.uistate.VideoDetailPageState.BOTTOM_PLAYER) {
                    delay(300)
                    if (viewModel.uiState.value.pageState
                        == project.pipepipe.app.uistate.VideoDetailPageState.BOTTOM_PLAYER
                    ) {
                        view.layoutParams = view.layoutParams.apply {
                            height = (64 * view.resources.displayMetrics.density).toInt()
                            if (this is CoordinatorLayout.LayoutParams) {
                                gravity = Gravity.BOTTOM
                            }
                        }
                    }
                } else {
                    view.layoutParams = view.layoutParams.apply {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        if (this is CoordinatorLayout.LayoutParams) {
                            gravity = Gravity.NO_GRAVITY
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun open(serviceId: Int, url: String) {
        viewModel.open(serviceId, url)
    }

    @JvmStatic
    fun expand() {
        viewModel.showDetail()
    }

    @JvmStatic
    fun showBottomPlayer() {
        viewModel.showBottomPlayer()
    }

    @JvmStatic
    fun onBackPressed(): Boolean = when (viewModel.uiState.value.pageState) {
        VideoDetailPageState.FULLSCREEN_PLAYER -> {
            viewModel.showDetail()
            true
        }
        VideoDetailPageState.DETAIL_PAGE -> {
            if (!viewModel.navigateBack()) {
                if (SharedContext.platformMediaController?.currentMediaItem?.value != null) {
                    viewModel.showBottomPlayer()
                } else {
                    viewModel.hide()
                }
            }
            true
        }
        else -> false
    }
}
