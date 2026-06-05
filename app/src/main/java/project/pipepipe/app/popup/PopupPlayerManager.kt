package project.pipepipe.app.popup

import android.content.Context
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.PlayerPopupCloseOverlayBinding
import org.schabi.newpipe.player.Player.IDLE_WINDOW_FLAGS
import org.schabi.newpipe.player.helper.PlayerHelper
import project.pipepipe.app.platform.PlatformMediaController
import kotlin.math.hypot

class PopupPlayerManager(
    private val context: Context,
    private val controller: PlatformMediaController,
    private val onClose: () -> Unit
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var popupView: FrameLayout? = null
    private var closeOverlay: PlayerPopupCloseOverlayBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var initialX = 0
    private var initialY = 0
    private var initialRawX = 0f
    private var initialRawY = 0f
    private var initialDistance = 0.0
    private var initialWidth = 0

    fun show() {
        if (popupView != null) {
            return
        }
        updateScreenSize()
        val width = preferences.getFloat(
            context.getString(R.string.popup_saved_width_key),
            context.resources.getDimension(R.dimen.popup_default_width)
        ).toInt()
        val height = PlayerHelper.getMinimumVideoHeight(width.toFloat()).toInt()
        layoutParams = WindowManager.LayoutParams(
            width,
            height,
            PlayerHelper.popupLayoutParamType(),
            IDLE_WINDOW_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.LEFT or Gravity.TOP
            x = preferences.getInt(
                context.getString(R.string.popup_saved_x_key),
                screenWidth / 2 - width / 2
            )
            y = preferences.getInt(
                context.getString(R.string.popup_saved_y_key),
                screenHeight / 2 - height / 2
            )
        }
        checkBounds()
        closeOverlay = PlayerPopupCloseOverlayBinding.inflate(android.view.LayoutInflater.from(context))
        closeOverlay?.closeButton?.visibility = View.GONE
        windowManager.addView(closeOverlay?.root, PlayerHelper.buildCloseOverlayLayoutParams())
        popupView = FrameLayout(context).apply {
            addView(
                PlayerView(context).apply {
                    useController = false
                    player = controller.nativePlayer as Player
                    setOnTouchListener(::onTouch)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        windowManager.addView(popupView, layoutParams)
    }

    fun remove() {
        popupView?.let {
            runCatching { windowManager.removeView(it) }
        }
        closeOverlay?.root?.let {
            runCatching { windowManager.removeView(it) }
        }
        popupView = null
        closeOverlay = null
        layoutParams = null
    }

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialRawX = event.rawX
                initialRawY = event.rawY
                closeOverlay?.closeButton?.visibility = View.VISIBLE
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    initialDistance = pointerDistance(event)
                    initialWidth = params.width
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2 && initialDistance > 0) {
                    resize((initialWidth * pointerDistance(event) / initialDistance).toInt())
                } else {
                    params.x = initialX + (event.rawX - initialRawX).toInt()
                    params.y = initialY + (event.rawY - initialRawY).toInt()
                    checkBounds()
                    windowManager.updateViewLayout(view, params)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isInsideCloseButton(event.rawX, event.rawY)) {
                    remove()
                    onClose()
                } else {
                    savePosition()
                    closeOverlay?.closeButton?.visibility = View.GONE
                }
                initialDistance = 0.0
            }
        }
        return true
    }

    private fun resize(width: Int) {
        val params = layoutParams ?: return
        val minimumWidth = context.resources.getDimension(R.dimen.popup_minimum_width).toInt()
        params.width = width.coerceIn(minimumWidth, screenWidth)
        params.height = PlayerHelper.getMinimumVideoHeight(params.width.toFloat()).toInt()
        checkBounds()
        popupView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun checkBounds() {
        layoutParams?.apply {
            x = x.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            y = y.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        }
    }

    private fun savePosition() {
        layoutParams?.let {
            preferences.edit()
                .putFloat(context.getString(R.string.popup_saved_width_key), it.width.toFloat())
                .putInt(context.getString(R.string.popup_saved_x_key), it.x)
                .putInt(context.getString(R.string.popup_saved_y_key), it.y)
                .apply()
        }
    }

    private fun updateScreenSize() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun pointerDistance(event: MotionEvent): Double =
        hypot(
            (event.getX(0) - event.getX(1)).toDouble(),
            (event.getY(0) - event.getY(1)).toDouble()
        )

    private fun isInsideCloseButton(x: Float, y: Float): Boolean {
        val button = closeOverlay?.closeButton ?: return false
        val location = IntArray(2)
        button.getLocationOnScreen(location)
        val centerX = location[0] + button.width / 2f
        val centerY = location[1] + button.height / 2f
        return hypot((x - centerX).toDouble(), (y - centerY).toDouble()) <= button.width
    }
}
