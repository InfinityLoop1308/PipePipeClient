package org.schabi.newpipe.player

import android.graphics.Color
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import org.schabi.newpipe.ktx.AnimationType
import org.schabi.newpipe.ktx.animate

/**
 * Owns the visibility of the player control root (the play/pause bar and its shadows).
 *
 * The delayed hide timer, the previous/next/queue/segments button visibility and the
 * system UI adjustments that accompany the controls all live here. [Player] only keeps
 * delegate methods with the same names, since the whole player surface still talks to it.
 */
class PlayerControlsVisibilityController(private val player: Player) {

    private val controlsVisibilityHandler = Handler()

    val isControlsVisible: Boolean
        get() = player.binding?.playbackControlRoot?.visibility == View.VISIBLE

    fun showControlsThenHide() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "showControlsThenHide() called")
        }
        showOrHideButtons()
        showSystemUIPartially()

        val binding = player.binding
        val hideTime = if (binding.playbackControlRoot.isInTouchMode) {
            Player.DEFAULT_CONTROLS_HIDE_TIME
        } else {
            Player.DPAD_CONTROLS_HIDE_TIME
        }

        showHideShadow(true, Player.DEFAULT_CONTROLS_DURATION.toLong())
        binding.playbackControlRoot.animate(
            true,
            Player.DEFAULT_CONTROLS_DURATION.toLong(),
            AnimationType.ALPHA,
            0L
        ) { hideControls(Player.DEFAULT_CONTROLS_DURATION.toLong(), hideTime.toLong()) }
    }

    fun showControls(duration: Long) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "showControls() called")
        }
        showOrHideButtons()
        showSystemUIPartially()
        controlsVisibilityHandler.removeCallbacksAndMessages(null)
        showHideShadow(true, duration)
        player.binding.playbackControlRoot.animate(true, duration)
    }

    fun hideControls(duration: Long, delay: Long) {
        if (Player.DEBUG) {
            Log.d(
                Player.TAG,
                "hideControls() called with: duration = [$duration], delay = [$delay]"
            )
        }

        showOrHideButtons()

        controlsVisibilityHandler.removeCallbacksAndMessages(null)
        controlsVisibilityHandler.postDelayed({
            showHideShadow(false, duration)
            player.binding.playbackControlRoot.animate(
                false,
                duration,
                AnimationType.ALPHA,
                0L
            ) { hideSystemUIIfNeeded() }
        }, delay)
    }

    /**
     * Manages the controls after a click occurred on the player UI.
     * @param v the view that was clicked
     */
    fun manageControlsAfterOnClick(v: View) {
        if (player.currentState.isCompleted) {
            return
        }

        controlsVisibilityHandler.removeCallbacksAndMessages(null)
        showHideShadow(true, Player.DEFAULT_CONTROLS_DURATION.toLong())
        player.binding.playbackControlRoot.animate(
            true,
            Player.DEFAULT_CONTROLS_DURATION.toLong(),
            AnimationType.ALPHA,
            0L
        ) {
            if (player.currentState.isPlaying && !player.menuController.isSomePopupMenuVisible) {
                val binding = player.binding
                if (v.id == binding.playPauseButton.id ||
                    // Hide controls in fullscreen immediately
                    (v.id == binding.screenRotationButton.id && player.isFullscreen)
                ) {
                    hideControls(0L, 0L)
                } else {
                    hideControls(
                        Player.DEFAULT_CONTROLS_DURATION.toLong(),
                        Player.DEFAULT_CONTROLS_HIDE_TIME.toLong()
                    )
                }
            }
        }
    }

    fun showHideShadow(show: Boolean, duration: Long) {
        val binding = player.binding
        binding.playbackControlsShadow.animate(show, duration, AnimationType.ALPHA, 0L, null)
        binding.playerTopShadow.animate(show, duration, AnimationType.ALPHA, 0L, null)
        binding.playerBottomShadow.animate(show, duration, AnimationType.ALPHA, 0L, null)
    }

    /** Shows or hides the previous/next/queue/segments buttons depending on the play queue. */
    fun showOrHideButtons() {
        val playQueue = player.playQueue ?: return
        val binding = player.binding

        val showPrev = playQueue.index != 0
        val showNext = playQueue.index + 1 != playQueue.streams.size
        val showQueue = true
        /* only when stream has segments and is not playing in popup player */
        val showSegment = !player.popupPlayerSelected() &&
            !player.currentStreamInfo
                .map { info -> info.streamSegments }
                .map { segments -> segments.isEmpty() }
                .orElse(true)

        binding.playPreviousButton.visibility = if (showPrev) View.VISIBLE else View.INVISIBLE
        binding.playPreviousButton.alpha = if (showPrev) 1.0f else 0.0f
        binding.playNextButton.visibility = if (showNext) View.VISIBLE else View.INVISIBLE
        binding.playNextButton.alpha = if (showNext) 1.0f else 0.0f
        binding.queueButton.visibility = if (showQueue) View.VISIBLE else View.GONE
        binding.queueButton.alpha = if (showQueue) 1.0f else 0.0f
        binding.segmentsButton.visibility = if (showSegment) View.VISIBLE else View.GONE
        binding.segmentsButton.alpha = if (showSegment) 1.0f else 0.0f
    }

    fun showSystemUIPartially() {
        val activity: AppCompatActivity = player.parentActivity ?: return
        if (player.isFullscreen) {
            activity.window.setStatusBarColor(Color.TRANSPARENT)
            activity.window.setNavigationBarColor(Color.TRANSPARENT)
            val visibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            activity.window.decorView.systemUiVisibility = visibility
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    fun hideSystemUIIfNeeded() {
        player.listeners.hideSystemUIIfNeeded()
    }
}
