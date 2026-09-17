package org.schabi.newpipe.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.video.VideoSize
import org.schabi.newpipe.R
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.util.DeviceUtils

/**
 * Owns the fullscreen state, the orientation of the screen and the aspect ratio of the video.
 *
 * Whether the player is fullscreen and whether the video it plays is vertical used to be kept in
 * [Player] while the decisions taken from them lived in `PlayerUiModeHelper`, which read them back
 * through [Player]. Both the state and the decisions are here now, and [Player] only forwards the
 * calls the rest of the player surface and the detail fragment still make.
 */
class PlayerUiModeController(private val player: Player) {

    /** Whether the player currently occupies the whole screen. */
    var isFullscreen = false
        private set

    /** Whether the video that ExoPlayer last reported is taller than it is wide. */
    var isVerticalVideo = false
        private set

    /**
     * Enter or leave fullscreen and let the requested screen orientation follow the player. This
     * is what everything outside the player asks for.
     */
    fun changeFullscreen(fullscreen: Boolean) {
        setFullscreen(fullscreen)
        applyVideoOrientation()
    }

    fun setFullscreen(fullscreen: Boolean) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "setFullscreen() called with: fullscreen = [$fullscreen]")
        }
        if (isFullscreen == fullscreen
            || player.popupPlayerSelected()
            || player.exoPlayerIsNull()
            || !player.listeners.hasFragmentListener()
        ) {
            return
        }

        isFullscreen = fullscreen
        // Pinch zoom is fullscreen-only and never survives either direction of the transition.
        player.gestureController.resetPinchZoom()
        if (!isFullscreen) {
            // Apply window insets because Android will not do it when orientation changes
            // from landscape to portrait (open vertical video to reproduce)
            player.binding.playbackControlRoot.setPadding(0, 0, 0, 0)
        } else {
            // Hide the controls while Android calculates the new window insets.
            player.hideControls(0L, 0L)
        }
        player.listeners.onFullscreenStateChanged(isFullscreen)

        val binding = player.binding
        if (isFullscreen) {
            binding.titleTextView.visibility = View.VISIBLE
            binding.channelTextView.visibility = View.VISIBLE
            binding.playerCloseButton.visibility = View.GONE
            binding.sleepTimer.visibility = View.VISIBLE
        } else {
            binding.titleTextView.visibility = View.GONE
            binding.channelTextView.visibility = View.GONE
            binding.playerCloseButton.visibility =
                if (player.videoPlayerSelected()) View.VISIBLE else View.GONE
            binding.sleepTimer.visibility = View.GONE
        }
        setupScreenRotationButton()
    }

    /**
     * This will be called when the device orientation changed on its own. The transition follows
     * the screen, so it must not request another orientation.
     */
    fun onOrientationChanged(landscape: Boolean) {
        val activity = player.parentActivity ?: return
        if (!player.videoPlayerSelected()
            || DeviceUtils.isTv(player.context)
            || DeviceUtils.isInMultiWindow(activity)
            || !PlayerHelper.shouldRotationControlFullscreen(player.context)
        ) {
            return
        }
        setFullscreen(landscape)
    }

    /**
     * When the user asked for it, fullscreen follows the orientation of the video instead of the
     * other way round.
     */
    fun applyVideoOrientation() {
        if (!PlayerHelper.shouldRotateFullscreenToVideoOrientation(player.context)
            || DeviceUtils.isTv(player.context)
        ) {
            return
        }

        // Let system auto-rotation drive fullscreen without locking the video orientation.
        val requestedOrientation = when {
            PlayerHelper.shouldRotationControlFullscreen(player.context) ->
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            !isFullscreen -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            isVerticalVideo -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        setOrientation(player.parentActivity, requestedOrientation)
    }

    fun onVideoSizeChanged(videoSize: VideoSize) {
        if (Player.DEBUG) {
            Log.d(
                Player.TAG, "onVideoSizeChanged() called with: "
                    + "width / height = [" + videoSize.width + " / " + videoSize.height
                    + " = " + (videoSize.width.toFloat() / videoSize.height) + "], "
                    + "unappliedRotationDegrees = [" + videoSize.unappliedRotationDegrees + "], "
                    + "pixelWidthHeightRatio = [" + videoSize.pixelWidthHeightRatio + "]"
            )
        }

        player.menuController.onVideoSizeChanged(videoSize.width, videoSize.height)
        isVerticalVideo = videoSize.width < videoSize.height

        if (isFullscreen) {
            applyVideoOrientation()
        }

        setupScreenRotationButton()
    }

    /** Restore the look that belongs to the main video player: scaling mode and full size root. */
    fun initVideoPlayer() {
        // Pinch zoom owns video scaling while enabled; otherwise restore the regular display mode.
        player.menuController.setResizeMode(
            if (PlayerHelper.isPinchToZoomEnabled(player.context)) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            } else {
                PlayerHelper.retrieveResizeModeFromPrefs(player)
            }
        )
        player.binding.surfaceView.resetPinchScale()
        player.binding.root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    fun setupScreenRotationButton() {
        val binding = player.binding
        binding.screenRotationButton.visibility =
            if (player.videoPlayerSelected()) View.VISIBLE else View.GONE
        binding.screenRotationButton.setImageDrawable(
            AppCompatResources.getDrawable(
                player.context,
                if (isFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
            )
        )
    }

    companion object {
        /**
         * Request an orientation on the player activity, unless it already is the requested one.
         * Public because the detail fragment locks the screen orientation on its own.
         */
        @JvmStatic
        fun setOrientation(activity: Activity?, requestedOrientation: Int) {
            if (activity != null && activity.requestedOrientation != requestedOrientation) {
                activity.requestedOrientation = requestedOrientation
            }
        }
    }
}
