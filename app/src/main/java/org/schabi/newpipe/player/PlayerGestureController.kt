package org.schabi.newpipe.player

import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.GestureDetectorCompat
import org.schabi.newpipe.R
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.player.event.DisplayPortion
import org.schabi.newpipe.player.event.PlayerGestureListener
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.views.player.PlayerFastSeekOverlay
import org.schabi.newpipe.views.player.PlayerFastSeekOverlay.PerformListener.FastSeekDirection
import java.util.Locale

/**
 * Owns the touch/keyboard interaction of a [Player].
 *
 * The gesture detector, the gesture listener, the drag-to-seek/volume/brightness/speed overlays,
 * the pinch-to-zoom indicator and the DPAD key handling live here. [Player] keeps only thin
 * delegates for the calls that other components still route through it.
 */
class PlayerGestureController(private val player: Player) {

    companion object {
        /** The gesture length, as a factor of the smaller screen dimension. */
        private const val MAX_GESTURE_LENGTH = 0.75f
    }

    /** True if the up/down swipe to toggle fullscreen is enabled in the preferences. */
    val isFullscreenGestureEnabled: Boolean =
        PlayerHelper.isFullscreenGestureEnabled(player.context)

    /** The playback speed factor applied while long-pressing the player surface. */
    val longPressSpeedingFactor: Float =
        player.prefs.getString(player.context.getString(R.string.speeding_playback_key), "3")
            ?.toFloatOrNull() ?: 3f

    /** True while a long press is speeding up playback. */
    var longPressSpeedingEnabled = false

    /** The maximum length of a volume/brightness swipe, in pixels (scaled). */
    var maxGestureLength = 0 // scaled
        private set

    private lateinit var gestureDetectorInternal: GestureDetectorCompat

    private lateinit var gestureListenerInternal: PlayerGestureListener

    /** The listener interpreting every touch event on the player root. */
    val playerGestureListener: PlayerGestureListener
        get() = gestureListenerInternal

    /** The detector feeding [playerGestureListener]; only valid after [setup]. */
    val gestureDetector: GestureDetectorCompat
        get() = gestureDetectorInternal

    /** True while a multi-double-tap seek is in progress. False before [setup]. */
    val isDoubleTapping: Boolean
        get() = ::gestureListenerInternal.isInitialized && gestureListenerInternal.isDoubleTapping

    //region Volume / brightness / swipe overlays

    val volumeRelativeLayout: RelativeLayout
        get() = player.binding.volumeRelativeLayout
    val volumeProgressBar: ProgressBar
        get() = player.binding.volumeProgressBar
    val volumeImageView: ImageView
        get() = player.binding.volumeImageView
    val brightnessRelativeLayout: RelativeLayout
        get() = player.binding.brightnessRelativeLayout
    val brightnessProgressBar: ProgressBar
        get() = player.binding.brightnessProgressBar
    val brightnessImageView: ImageView
        get() = player.binding.brightnessImageView
    val swipeSeekDisplay: TextView
        get() = player.binding.swipeSeekDisplay
    val swipeSpeedDisplay: TextView
        get() = player.binding.swipeSpeedDisplay
    val fastSeekOverlay: PlayerFastSeekOverlay
        get() = player.binding.fastSeekOverlay

    //endregion

    /**
     * Creates the gesture listener and detector and attaches them to the player root.
     * Must be called when the views are set up (see [Player.setupFromView]).
     */
    fun setup() {
        val binding = player.binding
        gestureListenerInternal = PlayerGestureListener(player, player.service)
        gestureDetectorInternal = GestureDetectorCompat(player.context, gestureListenerInternal)
        binding.root.setOnTouchListener(gestureListenerInternal)
        binding.root.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            onLayoutChange(v, l, t, r, b, ol, ot, or, ob)
        }
    }

    /** Initializes the fast-forward/rewind overlay. */
    fun setupSeekOverlay() {
        val listener = gestureListenerInternal
        player.binding.fastSeekOverlay
            .seekSecondsSupplier {
                PlayerHelper.retrieveSeekDurationFromPreferences(player) / 1000
            }
            .performListener(object : PlayerFastSeekOverlay.PerformListener {
                override fun onDoubleTap() {
                    player.binding.fastSeekOverlay
                        .animate(true, Player.SEEK_OVERLAY_DURATION.toLong())
                }

                override fun onDoubleTapEnd() {
                    player.binding.fastSeekOverlay
                        .animate(false, Player.SEEK_OVERLAY_DURATION.toLong())
                }

                override fun getFastSeekDirection(portion: DisplayPortion): FastSeekDirection {
                    if (player.exoPlayerIsNull()) {
                        // Abort seeking
                        listener.endMultiDoubleTap()
                        return FastSeekDirection.NONE
                    }
                    val exoPlayer = player.simpleExoPlayer
                    if (portion == DisplayPortion.LEFT) {
                        // Check if it's possible to rewind
                        // Small puffer to eliminate infinite rewind seeking
                        return if (exoPlayer.currentPosition < 500L) {
                            FastSeekDirection.NONE
                        } else {
                            FastSeekDirection.BACKWARD
                        }
                    } else if (portion == DisplayPortion.RIGHT) {
                        // Check if it's possible to fast-forward
                        return if (player.currentState.isCompleted
                            || exoPlayer.currentPosition >= exoPlayer.duration
                        ) {
                            FastSeekDirection.NONE
                        } else {
                            FastSeekDirection.FORWARD
                        }
                    }
                    /* portion == DisplayPortion.MIDDLE */
                    return FastSeekDirection.NONE
                }

                override fun seek(forward: Boolean) {
                    listener.keepInDoubleTapMode()
                    if (forward) {
                        player.fastForward()
                    } else {
                        player.fastRewind()
                    }
                }
            })
        listener.doubleTapControls(player.binding.fastSeekOverlay)
    }

    /**
     * Handles [Player.onKeyDown] from the parent activity (remote/DPAD keys).
     */
    fun onKeyDown(keyCode: Int): Boolean {
        val binding = player.binding
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> if (player.isFullscreen) {
                player.playPause()
                if (player.isPlaying) {
                    player.hideControls(0L, 0L)
                }
                return true
            }

            KeyEvent.KEYCODE_BACK -> if (DeviceUtils.isTv(player.context)
                && player.isControlsVisible
            ) {
                player.hideControls(0L, 0L)
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if ((binding.root.hasFocus() && !binding.playbackControlRoot.hasFocus())
                    || player.isQueueVisible
                ) {
                    // do not interfere with focus in playlist and play queue etc.
                    return false
                }

                if (player.currentState.isBlocked) {
                    return true
                }

                if (player.isControlsVisible) {
                    player.hideControls(
                        Player.DEFAULT_CONTROLS_DURATION.toLong(),
                        Player.DPAD_CONTROLS_HIDE_TIME.toLong()
                    )
                } else {
                    binding.playPauseButton.requestFocus()
                    player.showControlsThenHide()
                    player.showSystemUIPartially()
                    return true
                }
            }

            else -> return false
        }
        return false
    }

    //region Pinch to zoom

    val isPinchToZoomEnabled: Boolean
        get() = player.isFullscreen && PlayerHelper.isPinchToZoomEnabled(player.context)

    fun resetPinchZoom() {
        val binding = player.binding
        binding.surfaceView.resetPinchScale()
        binding.pinchZoomIndicator.animate().cancel()
        binding.pinchZoomIndicator.visibility = View.GONE
    }

    fun onPinchZoomStart(focusX: Float, focusY: Float) {
        val binding = player.binding
        player.menuController.onPinchZoomStart()
        binding.surfaceView.beginPinchGesture(
            focusX - binding.surfaceView.left,
            focusY - binding.surfaceView.top
        )
        binding.pinchZoomIndicator.animate().cancel()
        binding.pinchZoomIndicator.alpha = 1.0f
        binding.pinchZoomIndicator.text =
            String.format(Locale.US, "%.1f×", binding.surfaceView.pinchScale)
        binding.pinchZoomIndicator.visibility = View.VISIBLE
    }

    fun onPinchZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        if (!scaleFactor.isFinite()) {
            return
        }
        val binding = player.binding
        binding.surfaceView.setPinchScale(
            binding.surfaceView.pinchScale * scaleFactor,
            focusX - binding.surfaceView.left,
            focusY - binding.surfaceView.top
        )
        binding.pinchZoomIndicator.text =
            String.format(Locale.US, "%.1f×", binding.surfaceView.pinchScale)
    }

    fun onPinchZoomEnd() {
        val binding = player.binding
        binding.pinchZoomIndicator.animate().cancel()
        binding.pinchZoomIndicator.animate().alpha(0.0f).setStartDelay(250L).setDuration(180L)
            .withEndAction { binding.pinchZoomIndicator.visibility = View.GONE }.start()
    }

    //endregion

    private fun onLayoutChange(
        view: View, l: Int, t: Int, r: Int, b: Int,
        ol: Int, ot: Int, or: Int, ob: Int
    ) {
        if (l != ol || t != ot || r != or || b != ob) {
            // Use smaller value to be consistent between screen orientations
            // (and to make usage easier)
            val width = r - l
            val height = b - t
            val min = minOf(width, height)
            maxGestureLength = (min * MAX_GESTURE_LENGTH).toInt()

            if (Player.DEBUG) {
                Log.d(Player.TAG, "maxGestureLength = $maxGestureLength")
            }

            player.binding.volumeProgressBar.max = maxGestureLength
            player.binding.brightnessProgressBar.max = maxGestureLength

            setInitialGestureValues()
            player.binding.itemsListPanel.layoutParams.height =
                height - player.binding.itemsListPanel.top
        }
    }

    private fun setInitialGestureValues() {
        player.audioReactor?.let { audioReactor ->
            val currentVolumeNormalized =
                audioReactor.volume.toFloat() / audioReactor.maxVolume
            player.binding.volumeProgressBar.progress =
                (player.binding.volumeProgressBar.max * currentVolumeNormalized).toInt()
        }
    }
}
