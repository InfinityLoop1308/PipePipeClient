package org.schabi.newpipe.player

import android.app.Activity
import android.content.pm.ActivityInfo
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.util.DeviceUtils

/**
 * The single entry point for fullscreen and orientation changes.
 */
object PlayerUiModeHelper {
    @JvmStatic
    fun setFullscreen(player: Player, fullscreen: Boolean) {
        player.setFullscreen(fullscreen)
        applyVideoOrientation(player)
    }

    @JvmStatic
    fun applyVideoOrientation(player: Player) {
        if (!PlayerHelper.shouldRotateFullscreenToVideoOrientation(player.context) ||
            DeviceUtils.isTv(player.context)
        ) {
            return
        }

        val requestedOrientation = when {
            // Let system auto-rotation drive fullscreen without locking the video orientation.
            PlayerHelper.shouldRotationControlFullscreen(player.context) ->
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            !player.isFullscreen -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            player.isVerticalVideo -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        setOrientation(player.parentActivity, player, requestedOrientation)
    }

    @JvmStatic
    fun onOrientationChanged(player: Player, landscape: Boolean) {
        val activity = player.parentActivity ?: return
        if (!player.videoPlayerSelected() ||
            DeviceUtils.isTv(player.context) ||
            DeviceUtils.isInMultiWindow(activity) ||
            !PlayerHelper.shouldRotationControlFullscreen(player.context)
        ) {
            return
        }
        // This transition follows the screen; it must not request another orientation.
        player.setFullscreen(landscape)
    }

    @JvmStatic
    fun setOrientation(
        activity: Activity?,
        player: Player?,
        requestedOrientation: Int,
    ) {
        if (activity != null && activity.requestedOrientation != requestedOrientation) {
            activity.requestedOrientation = requestedOrientation
        }
    }
}
