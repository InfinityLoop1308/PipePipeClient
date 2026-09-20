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
     * Whether a fullscreen request is waiting for a player that could not honor it yet. The
     * service is not necessarily up and running, and the player is not necessarily the main video
     * player, at the moment the request is made, so the request is kept here and applied by
     * [applyPendingFullscreen] once the playback has been set up.
     */
    private var pendingFullscreen = false

    /**
     * Enter or leave fullscreen and let the requested screen orientation follow the player. This
     * is what everything outside the player asks for.
     */
    fun changeFullscreen(fullscreen: Boolean) {
        if (!fullscreen) {
            // Leaving fullscreen is also the answer to "do you still want that fullscreen?".
            pendingFullscreen = false
        } else if (!canEnterFullscreen()) {
            pendingFullscreen = true
            return
        }
        setFullscreen(fullscreen)
        applyVideoOrientation()
    }

    /**
     * Honor the fullscreen request that [changeFullscreen] could not honor when it was made. This
     * is what makes "start main player in fullscreen" work on a player that was still being set
     * up, which is the case for every playback started straight from the detail page (#2928).
     */
    fun applyPendingFullscreen() {
        if (!pendingFullscreen) {
            return
        }
        if (player.audioPlayerSelected() || player.popupPlayerSelected()) {
            // Only the main video player can be shown fullscreen, so the request is not for this.
            pendingFullscreen = false
            return
        }
        // Keep the request while the player is still being set up, it is asked again from every
        // point a playback can become ready: the setup of an intent and of a deferred init.
        if (!canEnterFullscreen()) {
            return
        }
        pendingFullscreen = false
        setFullscreen(true)
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
        pendingFullscreen = false
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
        // The detail fragment moves the layout to another parent from that callback and the
        // surface is recreated around the rotation, which drops the rendered subtitles. The
        // first frame of the new surface redraws them where it exists; this covers the devices
        // where the surface survives the transition (#2944).
        player.binding.subtitleView.post { player.layoutController.reapplyCues() }

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

    /** Whether entering fullscreen now would actually reach the screen. */
    private fun canEnterFullscreen(): Boolean =
        !player.popupPlayerSelected()
            && !player.exoPlayerIsNull()
            && player.listeners.hasFragmentListener()
            // The detail fragment ignores a fullscreen it cannot react to: without the player view
            // attached to an activity it neither hides the related items of the tablet layout nor
            // moves the player, which leaves the screen split between the two.
            && player.parentActivity != null

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

        val activity = player.parentActivity
        if (activity != null && DeviceUtils.isInMultiWindow(activity)) {
            // An orientation request in split-screen makes the OEM window manager treat the app
            // as non-resizable: the pane is pinned to half of the screen and the divider can no
            // longer be dragged. Never lock the orientation here, and drop a lock that was
            // requested before the window entered multi-window (#2925).
            setOrientation(activity, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
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
