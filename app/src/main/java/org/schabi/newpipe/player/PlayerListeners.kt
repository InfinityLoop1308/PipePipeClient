package org.schabi.newpipe.player

import org.schabi.newpipe.player.event.PlayerEventListener
import org.schabi.newpipe.player.event.PlayerServiceEventListener

/**
 * Owns the external listeners of a [Player] and the "fragment is visible" flag.
 *
 * The fragment and activity listeners, whether the video detail fragment is currently resumed,
 * and the push notifications towards those listeners all live here. [Player] only keeps delegate
 * methods with the same names, so [org.schabi.newpipe.player.helper.PlayerHolder],
 * [PlayQueueActivity] and the broadcast receiver are unaffected. [PlayerErrorHandler] and the
 * fullscreen / long-press handling reach the fragment listener through this controller instead of
 * a public field.
 */
class PlayerListeners(private val player: Player) {

    private var fragmentListener: PlayerServiceEventListener? = null
    private var activityListener: PlayerEventListener? = null

    var isFragmentVisible = false

    fun hasFragmentListener(): Boolean = fragmentListener != null

    fun setFragmentListener(listener: PlayerServiceEventListener) {
        fragmentListener = listener
        isFragmentVisible = true
        // Apply window insets because Android will not do it when orientation changes
        // from landscape to portrait
        if (!player.isFullscreen) {
            player.binding.playbackControlRoot.setPadding(0, 0, 0, 0)
        }
        player.binding.itemsListPanel.setPadding(0, 0, 0, 0)
        notifyQueueUpdateToListeners()
        notifyMetadataUpdateToListeners()
        notifyPlaybackUpdateToListeners()
        player.triggerProgressUpdate()
        // The fragment can react to a fullscreen again from here on, so a request that was made
        // while it could not is honored now, if the player is already set up (#2928).
        player.uiModeController.applyPendingFullscreen()
    }

    fun removeFragmentListener(listener: PlayerServiceEventListener) {
        if (fragmentListener === listener) {
            fragmentListener = null
        }
    }

    fun setActivityListener(listener: PlayerEventListener) {
        activityListener = listener
        // TODO why not queue update?
        notifyMetadataUpdateToListeners()
        notifyPlaybackUpdateToListeners()
        player.triggerProgressUpdate()
    }

    fun removeActivityListener(listener: PlayerEventListener) {
        if (activityListener === listener) {
            activityListener = null
        }
    }

    fun stopActivityBinding() {
        fragmentListener?.onServiceStopped()
        fragmentListener = null
        activityListener?.onServiceStopped()
        activityListener = null
    }

    fun onFullscreenStateChanged(fullscreen: Boolean) {
        fragmentListener?.onFullscreenStateChanged(fullscreen)
    }

    fun onMoreOptionsLongClicked() {
        fragmentListener?.onMoreOptionsLongClicked()
    }

    fun hideSystemUIIfNeeded() {
        fragmentListener?.hideSystemUiIfNeeded()
    }

    fun onPlayerError(error: PlayerError, isCatchableException: Boolean) {
        fragmentListener?.onPlayerError(error, isCatchableException)
    }

    fun notifyQueueUpdateToListeners() {
        val queue = player.playQueue ?: return
        fragmentListener?.onQueueUpdate(queue)
        activityListener?.onQueueUpdate(queue)
    }

    fun notifyMetadataUpdateToListeners() {
        player.currentStreamInfo.ifPresent { info ->
            fragmentListener?.onMetadataUpdate(info, player.playQueue)
            activityListener?.onMetadataUpdate(info, player.playQueue)
        }
    }

    fun notifyPlaybackUpdateToListeners() {
        if (player.exoPlayerIsNull() || player.playQueue == null) {
            return
        }
        val state = player.currentState
        val repeatMode = player.repeatMode
        val shuffled = player.playQueue!!.isShuffled
        val parameters = player.playbackParameters
        fragmentListener?.onPlaybackUpdate(state, repeatMode, shuffled, parameters)
        activityListener?.onPlaybackUpdate(state, repeatMode, shuffled, parameters)
    }

    fun notifyProgressUpdateToListeners(
        currentProgress: Int,
        duration: Int,
        bufferPercent: Int
    ) {
        fragmentListener?.onProgressUpdate(currentProgress, duration, bufferPercent)
        activityListener?.onProgressUpdate(currentProgress, duration, bufferPercent)
    }
}
