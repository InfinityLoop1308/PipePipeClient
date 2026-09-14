package org.schabi.newpipe.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AnticipateInterpolator
import androidx.core.content.ContextCompat
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.PlayerPopupCloseOverlayBinding
import org.schabi.newpipe.player.helper.PlayerHelper
import kotlin.math.sqrt

/**
 * Owns the popup player as a system window: the [WindowManager.LayoutParams], the full-screen
 * drag-to-close overlay, the cached screen size and the teardown animation.
 *
 * This is a pure view/window helper: playback state stays in [Player], which keeps delegate
 * methods and getters with the same names so that existing callers (the gesture listeners,
 * [PlayerService], ...) are unaffected.
 */
class PopupWindowController(private val player: Player) {

    private companion object {
        const val TAG = "PopupWindowController"
    }

    val windowManager: WindowManager? =
        ContextCompat.getSystemService(player.context, WindowManager::class.java)

    private var closeOverlayBinding: PlayerPopupCloseOverlayBinding? = null

    var popupLayoutParams: WindowManager.LayoutParams? = null
        private set

    var isPopupClosing: Boolean = false
        private set

    var screenWidth: Float = 0f
        private set

    var screenHeight: Float = 0f
        private set

    @SuppressLint("RtlHardcoded")
    fun initPopup() {
        if (Player.DEBUG) {
            Log.d(TAG, "initPopup() called")
        }

        // Popup is already added to windowManager
        if (popupHasParent()) {
            return
        }

        updateScreenSize()

        popupLayoutParams = PlayerHelper.retrievePopupLayoutParamsFromPrefs(player)
        val params = popupLayoutParams!!

        player.binding.surfaceView.setHeights(params.height, params.height)

        checkPopupPositionBounds()

        player.binding.loadingPanel.minimumWidth = params.width
        player.binding.loadingPanel.minimumHeight = params.height

        player.service.removeViewFromParent()
        windowManager!!.addView(player.binding.root, params)

        // Popup doesn't have aspectRatio selector, using FIT automatically
        player.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    }

    @SuppressLint("RtlHardcoded")
    fun initPopupCloseOverlay() {
        if (Player.DEBUG) {
            Log.d(TAG, "initPopupCloseOverlay() called")
        }

        // closeOverlayView is already added to windowManager
        if (closeOverlayBinding != null) {
            return
        }

        closeOverlayBinding =
            PlayerPopupCloseOverlayBinding.inflate(LayoutInflater.from(player.context))

        val closeOverlayLayoutParams = PlayerHelper.buildCloseOverlayLayoutParams()
        closeOverlayBinding!!.closeButton.visibility = View.GONE
        windowManager!!.addView(closeOverlayBinding!!.root, closeOverlayLayoutParams)
    }

    /**
     * Check if [popupLayoutParams]' position is within an arbitrary boundary that goes from
     * (0, 0) to (screenWidth, screenHeight). If it is out of these boundaries, its position
     * is changed.
     */
    fun checkPopupPositionBounds() {
        if (Player.DEBUG) {
            Log.d(TAG, "checkPopupPositionBounds() called with: "
                    + "screenWidth = [$screenWidth], screenHeight = [$screenHeight]")
        }
        val params = popupLayoutParams ?: return

        if (params.x < 0) {
            params.x = 0
        } else if (params.x > screenWidth - params.width) {
            params.x = (screenWidth - params.width).toInt()
        }

        if (params.y < 0) {
            params.y = 0
        } else if (params.y > screenHeight - params.height) {
            params.y = (screenHeight - params.height).toInt()
        }
    }

    fun updateScreenSize() {
        val manager = windowManager ?: return

        val metrics = DisplayMetrics()
        manager.defaultDisplay.getMetrics(metrics)

        screenWidth = metrics.widthPixels.toFloat()
        screenHeight = metrics.heightPixels.toFloat()
        if (Player.DEBUG) {
            Log.d(TAG, "updateScreenSize() called: screenWidth = [$screenWidth], "
                    + "screenHeight = [$screenHeight]")
        }
    }

    /**
     * Changes the size of the popup based on the width.
     * @param width the new width, the height is calculated with
     *              [PlayerHelper.getMinimumVideoHeight]
     */
    fun changePopupSize(width: Int) {
        if (Player.DEBUG) {
            Log.d(TAG, "changePopupSize() called with: width = [$width]")
        }

        if (anyPopupViewIsNull()) {
            return
        }

        val minimumWidth = player.context.resources.getDimension(R.dimen.popup_minimum_width)
        val actualWidth = when {
            width.toFloat() > screenWidth -> screenWidth
            width.toFloat() < minimumWidth -> minimumWidth
            else -> width.toFloat()
        }.toInt()
        val actualHeight = PlayerHelper.getMinimumVideoHeight(width.toFloat()).toInt()
        if (Player.DEBUG) {
            Log.d(TAG, "updatePopupSize() updated values:"
                    + "  width = [$actualWidth], height = [$actualHeight]")
        }

        val params = popupLayoutParams!!
        params.width = actualWidth
        params.height = actualHeight
        player.binding.surfaceView.setHeights(params.height, params.height)
        windowManager!!.updateViewLayout(player.binding.root, params)
    }

    fun changePopupWindowFlags(flags: Int) {
        if (Player.DEBUG) {
            Log.d(TAG, "changePopupWindowFlags() called with: flags = [$flags]")
        }

        if (!anyPopupViewIsNull()) {
            val params = popupLayoutParams!!
            params.flags = flags
            windowManager!!.updateViewLayout(player.binding.root, params)
        }
    }

    fun closePopup() {
        if (Player.DEBUG) {
            Log.d(TAG, "closePopup() called, isPopupClosing = $isPopupClosing")
        }
        if (isPopupClosing) {
            return
        }
        isPopupClosing = true

        player.saveStreamProgressState()
        windowManager!!.removeView(player.binding.root)

        animatePopupOverlayAndFinishService()
    }

    fun removePopupFromView() {
        val manager = windowManager ?: return

        // Close popup menus before removing from view to prevent crash
        player.closeAllPopupMenus()

        // wrap in try-catch since it could sometimes generate errors randomly
        try {
            if (popupHasParent()) {
                manager.removeView(player.binding.root)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to remove popup from window manager", e)
        }

        try {
            val closeOverlayHasParent = closeOverlayBinding?.root?.parent != null
            if (closeOverlayHasParent) {
                manager.removeView(closeOverlayBinding!!.root)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to remove popup overlay from window manager", e)
        }
    }

    private fun animatePopupOverlayAndFinishService() {
        val binding = closeOverlayBinding!!
        val targetTranslationY =
            (binding.closeButton.rootView.height - binding.closeButton.y).toInt()

        binding.closeButton.animate().setListener(null).cancel()
        binding.closeButton.animate()
            .setInterpolator(AnticipateInterpolator())
            .translationY(targetTranslationY.toFloat())
            .setDuration(400)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    end()
                }

                override fun onAnimationEnd(animation: Animator) {
                    end()
                }

                private fun end() {
                    closeOverlayBinding?.let {
                        windowManager!!.removeView(it.root)
                        closeOverlayBinding = null
                    }
                    player.service.stopService()
                }
            }).start()
    }

    private fun popupHasParent(): Boolean {
        val binding = player.binding ?: return false
        return binding.root.layoutParams is WindowManager.LayoutParams
                && binding.root.parent != null
    }

    private fun anyPopupViewIsNull(): Boolean {
        // TODO understand why checking getParentActivity() != null
        return popupLayoutParams == null || windowManager == null
                || player.parentActivity != null || player.binding.root.parent == null
    }

    fun distanceFromCloseButton(popupMotionEvent: MotionEvent): Int {
        val binding = closeOverlayBinding!!
        val closeOverlayButtonX = binding.closeButton.left + binding.closeButton.width / 2
        val closeOverlayButtonY = binding.closeButton.top + binding.closeButton.height / 2

        val params = popupLayoutParams!!
        val fingerX = params.x + popupMotionEvent.x
        val fingerY = params.y + popupMotionEvent.y

        val dx = (closeOverlayButtonX - fingerX).toDouble()
        val dy = (closeOverlayButtonY - fingerY).toDouble()

        return sqrt(dx * dx + dy * dy).toInt()
    }

    fun getClosingRadius(): Float {
        val buttonRadius = closeOverlayBinding!!.closeButton.width / 2
        // 20% wider than the button itself
        return buttonRadius * 1.2f
    }

    fun isInsideClosingRadius(popupMotionEvent: MotionEvent): Boolean =
        distanceFromCloseButton(popupMotionEvent) <= getClosingRadius()

    fun getCloseOverlayButton(): FloatingActionButton = closeOverlayBinding!!.closeButton
}
