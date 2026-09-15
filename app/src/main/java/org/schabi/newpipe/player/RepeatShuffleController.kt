package org.schabi.newpipe.player

import android.util.Log
import android.widget.ImageButton
import androidx.appcompat.widget.AppCompatImageButton
import com.google.android.exoplayer2.Player.REPEAT_MODE_ALL
import com.google.android.exoplayer2.Player.REPEAT_MODE_OFF
import com.google.android.exoplayer2.Player.REPEAT_MODE_ONE
import org.schabi.newpipe.R
import org.schabi.newpipe.player.helper.PlayerHelper

/**
 * Owns the repeat and shuffle state of a [Player].
 *
 * The state itself lives in the [PlayerStateHolder]; this controller owns the ExoPlayer
 * translation and the UI of the repeat and shuffle buttons. [Player] only keeps delegate
 * methods with the same names, so existing callers (the notification, the media session, the
 * play queue activity, the ExoPlayer listener callbacks) are unaffected.
 */
class RepeatShuffleController(private val player: Player) {

    fun onRepeatClicked() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onRepeatClicked() called")
        }
        setRepeatMode(PlayerHelper.nextRepeatMode(repeatMode))
    }

    fun onShuffleClicked() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onShuffleClicked() called")
        }

        if (player.exoPlayerIsNull()) {
            return
        }
        val exoPlayer = player.simpleExoPlayer
        exoPlayer.shuffleModeEnabled = !exoPlayer.shuffleModeEnabled
    }

    val repeatMode: RepeatMode
        get() = player.getStateHolder().repeatMode.value

    fun setRepeatMode(repeatMode: RepeatMode) {
        if (!player.exoPlayerIsNull()) {
            player.simpleExoPlayer.repeatMode = toExoPlayerRepeatMode(repeatMode)
        }
    }

    fun onRepeatModeChanged(repeatMode: Int) {
        val mode = fromExoPlayerRepeatMode(repeatMode)
        if (Player.DEBUG) {
            Log.d(Player.TAG, "ExoPlayer - onRepeatModeChanged() called with: "
                + "repeatMode = [$mode]")
        }
        player.getStateHolder().setRepeatMode(mode)
        setRepeatModeButton(player.binding.repeatButton, mode)
        player.onShuffleOrRepeatModeChanged()
    }

    fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "ExoPlayer - onShuffleModeEnabledChanged() called with: "
                + "mode = [$shuffleModeEnabled]")
        }

        player.getStateHolder().setShuffleModeEnabled(shuffleModeEnabled)

        val playQueue = player.playQueue
        if (playQueue != null) {
            if (shuffleModeEnabled) {
                playQueue.shuffle()
            } else {
                playQueue.unshuffle()
            }
        }

        setShuffleButton(player.binding.shuffleButton, shuffleModeEnabled)
        player.onShuffleOrRepeatModeChanged()
    }

    /**
     * Keeps the shuffle button in sync with ExoPlayer, e.g. when the player is re-initialized
     * with a queue whose shuffle mode was already restored.
     */
    fun updateShuffleButton() {
        val exoPlayer = player.simpleExoPlayer ?: return
        setShuffleButton(player.binding.shuffleButton, exoPlayer.shuffleModeEnabled)
    }

    private fun setRepeatModeButton(imageButton: AppCompatImageButton, repeatMode: RepeatMode) {
        when (repeatMode) {
            RepeatMode.OFF ->
                imageButton.setImageResource(R.drawable.exo_controls_repeat_off)
            RepeatMode.ONE ->
                imageButton.setImageResource(R.drawable.exo_controls_repeat_one)
            RepeatMode.ALL ->
                imageButton.setImageResource(R.drawable.exo_controls_repeat_all)
        }
    }

    private fun setShuffleButton(button: ImageButton, shuffled: Boolean) {
        button.imageAlpha = if (shuffled) 255 else 77
    }

    companion object {
        @JvmStatic
        fun fromExoPlayerRepeatMode(repeatMode: Int): RepeatMode = when (repeatMode) {
            REPEAT_MODE_ONE -> RepeatMode.ONE
            REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }

        @JvmStatic
        fun toExoPlayerRepeatMode(repeatMode: RepeatMode): Int = when (repeatMode) {
            RepeatMode.ONE -> REPEAT_MODE_ONE
            RepeatMode.ALL -> REPEAT_MODE_ALL
            else -> REPEAT_MODE_OFF
        }
    }
}
