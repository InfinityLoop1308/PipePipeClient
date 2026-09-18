package org.schabi.newpipe.player

import android.os.Handler
import android.view.View
import org.schabi.newpipe.R

/**
 * Owns the SABR backoff countdown of a [Player].
 *
 * The [Handler] that refreshes the on-screen countdown while playback is blocked or buffering
 * lives here. [Player] only keeps delegate methods with the same names, so the playback state
 * machine is unaffected.
 */
class PlayerSabrBackoffCountdown(private val player: Player) {

    private val handler = Handler()

    private val update = object : Runnable {
        override fun run() {
            updateCountdown()
            if (player.currentState.isBlocked
                || (!player.exoPlayerIsNull()
                    && player.simpleExoPlayer.playbackState
                    == com.google.android.exoplayer2.Player.STATE_BUFFERING)
            ) {
                handler.postDelayed(this, 250L)
            }
        }
    }

    fun start() {
        SabrBackoffCoordinator.getInstance().setPlayerBuffering(player.context, true)
        handler.removeCallbacks(update)
        update.run()
    }

    fun stop() {
        SabrBackoffCoordinator.getInstance().setPlayerBuffering(player.context, false)
        handler.removeCallbacks(update)
        player.binding?.sabrBackoffCountdown?.visibility = View.GONE
    }

    private fun updateCountdown() {
        val binding = player.binding ?: return
        val remainingMs = SabrBackoffCoordinator.getInstance().remainingMs
        if (!player.listeners.isFragmentVisible || remainingMs <= 0L) {
            binding.sabrBackoffCountdown.visibility = View.GONE
            return
        }
        val seconds = SabrBackoffCoordinator.remainingSeconds(remainingMs)
        binding.sabrBackoffCountdown.text = player.context.getString(
            R.string.sabr_backoff_notification_content, seconds
        )
        binding.sabrBackoffCountdown.visibility = View.VISIBLE
    }
}
