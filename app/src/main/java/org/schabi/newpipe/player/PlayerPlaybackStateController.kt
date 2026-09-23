package org.schabi.newpipe.player

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.util.Log
import android.view.View
import com.google.android.exoplayer2.source.MediaSource
import org.schabi.newpipe.R
import org.schabi.newpipe.ktx.AnimationType
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.player.helper.PlayerHelper

/**
 * Owns the playback state machine of a [Player].
 *
 * The PipePipe-level state (see [PlayerPlaybackState]) and everything a transition triggers live
 * here: the reactions to ExoPlayer's four states, the per-state UI, the progress loop start/stop,
 * the SABR countdown and the bullet-comments transitions. [Player] only keeps delegate methods
 * with the same names, so [ExoPlayerEventAdapter], [PlaybackListenerAdapter],
 * [PlayerProgressController], [PlayerErrorHandler] and the video detail fragment are unaffected.
 */
class PlayerPlaybackStateController(private val player: Player) {

    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (Player.DEBUG) {
            Log.d(
                Player.TAG, "ExoPlayer - onPlayWhenReadyChanged() called with: " +
                    "playWhenReady = [$playWhenReady], reason = [$reason]"
            )
        }
        val playbackState = if (player.exoPlayerIsNull()) {
            com.google.android.exoplayer2.Player.STATE_IDLE
        } else {
            player.simpleExoPlayer.playbackState
        }
        updatePlaybackState(playWhenReady, playbackState)
    }

    fun onPlaybackStateChanged(playbackState: Int) {
        if (Player.DEBUG) {
            Log.d(
                Player.TAG, "ExoPlayer - onPlaybackStateChanged() called with: " +
                    "playbackState = [$playbackState]"
            )
        }
        updatePlaybackState(player.playWhenReady, playbackState)
    }

    private fun updatePlaybackState(playWhenReady: Boolean, playbackState: Int) {
        if (Player.DEBUG) {
            Log.d(
                Player.TAG, "ExoPlayer - onPlayerStateChanged() called with: " +
                    "playWhenReady = [$playWhenReady], playbackState = [$playbackState]"
            )
        }

        if (player.currentState.isPausedSeek) {
            if (Player.DEBUG) {
                Log.d(Player.TAG, "updatePlaybackState() is currently blocked")
            }
            return
        }

        when (playbackState) {
            com.google.android.exoplayer2.Player.STATE_IDLE -> // 1
                player.isPrepared = false

            com.google.android.exoplayer2.Player.STATE_BUFFERING -> // 2
                if (player.isPrepared) {
                    changeState(PlayerPlaybackState.BUFFERING)
                }

            com.google.android.exoplayer2.Player.STATE_READY -> { // 3
                PlaybackStartupTrace.mark(player.startupTraceId, "player_ready")
                if (!player.isPrepared) {
                    player.isPrepared = true
                    onPrepared(playWhenReady)
                    // The item can be shown from here on, so a fullscreen request that was made
                    // while it was still being resolved is honored now (#2928).
                    player.uiModeController.applyPendingFullscreen()
                }
                changeState(
                    if (playWhenReady) {
                        PlayerPlaybackState.PLAYING
                    } else {
                        PlayerPlaybackState.PAUSED
                    }
                )
                if (Build.VERSION.SDK_INT >= 37) {
                    NotificationUtil.getInstance()
                        .createNotificationAndStartForeground(player, player.service.instance)
                }
            }

            com.google.android.exoplayer2.Player.STATE_ENDED -> { // 4
                changeState(PlayerPlaybackState.COMPLETED)
                player.saveStreamProgressStateCompleted()
                player.isPrepared = false
            }
        }
    }

    fun onIsLoadingChanged(isLoading: Boolean) {
        if (!isLoading) {
            if (player.currentState.isPaused && player.progressController.isProgressLoopRunning()) {
                player.progressController.stopProgressLoop()
            }
        } else {
            if (!player.progressController.isProgressLoopRunning()) {
                player.progressController.startProgressLoop()
            }
        }
    }

    fun onPlaybackBlock() {
        if (player.exoPlayerIsNull()) {
            return
        }
        if (Player.DEBUG) {
            Log.d(Player.TAG, "Playback - onPlaybackBlock() called")
        }

        player.clearCurrentMediaItems()
        player.simpleExoPlayer.stop()
        player.isPrepared = false

        changeState(PlayerPlaybackState.BLOCKED)
    }

    fun onPlaybackUnblock(mediaSource: MediaSource) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "Playback - onPlaybackUnblock() called")
        }

        if (player.exoPlayerIsNull()) {
            return
        }
        if (player.currentState.isBlocked) {
            changeState(PlayerPlaybackState.BUFFERING)
        }
        PlaybackStartupTrace.mark(player.startupTraceId, "media_source_attached")
        player.simpleExoPlayer.setMediaSource(mediaSource, false)
        player.simpleExoPlayer.prepare()
    }

    fun changeState(state: PlayerPlaybackState) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "changeState() called with: state = [$state]")
        }
        player.currentState = state
        when (state) {
            PlayerPlaybackState.BLOCKED -> onBlocked()

            PlayerPlaybackState.PLAYING -> {
                onPlaying()
                player.bulletCommentsController.init()
                player.bulletCommentsController.start()
            }

            PlayerPlaybackState.BUFFERING -> onBuffering()

            PlayerPlaybackState.PAUSED -> {
                player.autoQueueController.cancelEnqueueTimer()
                onPaused()
                player.bulletCommentsController.pause()
                // A live stream has no paused position worth keeping: release the source so the
                // manifest refresh, the segment downloads and the live chat connections stop.
                player.enterLiveIdle()
            }

            PlayerPlaybackState.PAUSED_SEEK -> {
                player.autoQueueController.cancelEnqueueTimer()
                onPausedSeek()
                player.bulletCommentsController.pause()
            }

            PlayerPlaybackState.COMPLETED -> {
                onCompleted()
                player.bulletCommentsController.complete()
            }

            PlayerPlaybackState.PREFLIGHT -> Unit
        }
        player.notifyPlaybackUpdateToListeners()
    }

    fun startBCPlayer() {
        player.bulletCommentsController.start()
    }

    fun pauseBCPlayer() {
        player.bulletCommentsController.pause()
    }

    private fun onPrepared(playWhenReady: Boolean) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onPrepared() called with: playWhenReady = [$playWhenReady]")
        }

        player.setVideoDurationToControls(player.simpleExoPlayer.duration.toInt())

        player.binding.playbackSpeed.text =
            PlayerHelper.formatSpeed(player.playbackSpeed.toDouble())
        if (playWhenReady) {
            player.audioReactor.requestAudioFocus()
        }
    }

    private fun onBlocked() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onBlocked() called")
        }
        player.startSabrBackoffCountdown()
        if (!player.progressController.isProgressLoopRunning()) {
            player.progressController.startProgressLoop()
        }

        // if we are e.g. switching players, hide controls
        player.hideControls(Player.DEFAULT_CONTROLS_DURATION.toLong(), 0L)

        player.binding.playbackSeekBar.isEnabled = false
        player.binding.playbackSeekBar.thumb.colorFilter =
            PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)

        player.binding.loadingPanel.setBackgroundColor(Color.BLACK)
        player.binding.loadingPanel.animate(true, 0L)
        player.binding.surfaceForeground.animate(true, 100L)

        player.binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
        animatePlayButtons(false, 100)
        player.binding.root.keepScreenOn = false

        NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(player, false)
    }

    private fun onPlaying() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onPlaying() called")
        }
        player.stopSabrBackoffCountdown()
        if (!player.progressController.isProgressLoopRunning()) {
            player.progressController.startProgressLoop()
        }

        player.updateStreamRelatedViews()

        player.currentStreamInfo.orElse(null)?.let { streamInfo ->
            if (streamInfo.isRoundPlayStream()) {
                player.autoQueueController.scheduleRoundPlayAutoQueue(streamInfo)
            }
        }

        player.binding.playbackSeekBar.isEnabled = true
        player.binding.playbackSeekBar.thumb.colorFilter =
            PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)

        player.binding.loadingPanel.visibility = View.GONE

        player.binding.currentDisplaySeek.animate(false, 200L, AnimationType.SCALE_AND_ALPHA)

        player.binding.playPauseButton.animate(
            false, 80L, AnimationType.SCALE_AND_ALPHA, 0L
        ) {
            player.binding.playPauseButton.setImageResource(R.drawable.ic_pause)
            animatePlayButtons(true, 200)
            if (!player.isQueueVisible) {
                player.binding.playPauseButton.requestFocus()
            }
        }

        player.changePopupWindowFlags(Player.ONGOING_PLAYBACK_WINDOW_FLAGS)
        player.binding.root.keepScreenOn = true

        NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(player, false)
    }

    fun onBuffering() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onBuffering() called")
        }
        player.binding.loadingPanel.setBackgroundColor(Color.TRANSPARENT)
        player.binding.loadingPanel.visibility = View.VISIBLE
        player.startSabrBackoffCountdown()

        player.binding.root.keepScreenOn = true
        if (NotificationUtil.getInstance().shouldUpdateBufferingSlot()) {
            NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(player, false)
        }
    }

    private fun onPaused() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onPaused() called")
        }
        player.stopSabrBackoffCountdown()
        if (player.progressController.isProgressLoopRunning()) {
            player.progressController.stopProgressLoop()
        }

        // Don't let UI elements popup during double tap seeking. This state is entered sometimes
        // during seeking/loading. This if-else check ensures that the controls aren't popping up.
        if (!player.gestureController.isDoubleTapping) {
            player.showControls(400L)
            player.binding.loadingPanel.visibility = View.GONE

            player.binding.playPauseButton.animate(
                false, 80L, AnimationType.SCALE_AND_ALPHA, 0L
            ) {
                player.binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
                animatePlayButtons(true, 200)
                if (!player.isQueueVisible) {
                    player.binding.playPauseButton.requestFocus()
                }
            }
        }
        player.changePopupWindowFlags(Player.IDLE_WINDOW_FLAGS)

        // Remove running notification when user does not want minimization to background or popup
        if (PlayerHelper.getMinimizeOnExitAction(player.context)
            == PlayerHelper.MinimizeMode.MINIMIZE_ON_EXIT_MODE_NONE
            && player.videoPlayerSelected()
        ) {
            NotificationUtil.getInstance()
                .cancelNotificationAndStopForeground(player.service.instance)
        } else {
            NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(player, false)
        }

        player.binding.root.keepScreenOn = false
    }

    private fun onPausedSeek() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onPausedSeek() called")
        }

        player.stopSabrBackoffCountdown()
        animatePlayButtons(false, 100)
        player.binding.root.keepScreenOn = true

        NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(player, false)
    }

    private fun onCompleted() {
        if (Player.DEBUG) {
            Log.d(
                Player.TAG, "onCompleted() called" +
                    if (player.playQueue == null) ". playQueue is null" else ""
            )
        }
        player.stopSabrBackoffCountdown()
        val playQueue = player.playQueue ?: return

        player.binding.playPauseButton.animate(false, 0L, AnimationType.SCALE_AND_ALPHA, 0L) {
            player.binding.playPauseButton.setImageResource(R.drawable.ic_replay)
            animatePlayButtons(true, Player.DEFAULT_CONTROLS_DURATION)
        }

        player.binding.root.keepScreenOn = false
        player.changePopupWindowFlags(Player.IDLE_WINDOW_FLAGS)

        NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(player, false)

        if (playQueue.index < playQueue.size() - 1) {
            playQueue.offsetIndex(+1)
        }
        if (player.progressController.isProgressLoopRunning()) {
            player.progressController.stopProgressLoop()
        }

        // When a (short) video ends the elements have to display the correct values - see #6180
        player.updatePlayBackElementsCurrentDuration(player.binding.playbackSeekBar.max)

        player.showControls(500L)
        player.binding.currentDisplaySeek.animate(false, 200L, AnimationType.SCALE_AND_ALPHA)
        player.binding.loadingPanel.visibility = View.GONE
        player.binding.surfaceForeground.animate(true, 100L)
    }

    private fun animatePlayButtons(show: Boolean, duration: Int) {
        player.binding.playPauseButton.animate(
            show, duration.toLong(), AnimationType.SCALE_AND_ALPHA
        )

        val playQueue = player.playQueue
        val showQueueButtons = show && playQueue != null

        if (playQueue == null) {
            player.binding.playPreviousButton.animate(
                false, duration.toLong(), AnimationType.SCALE_AND_ALPHA
            )
            player.binding.playNextButton.animate(
                false, duration.toLong(), AnimationType.SCALE_AND_ALPHA
            )
            return
        }

        if (!showQueueButtons || playQueue.index > 0) {
            player.binding.playPreviousButton.animate(
                showQueueButtons, duration.toLong(), AnimationType.SCALE_AND_ALPHA
            )
        }
        if (!showQueueButtons || playQueue.index + 1 < playQueue.streams.size) {
            player.binding.playNextButton.animate(
                showQueueButtons, duration.toLong(), AnimationType.SCALE_AND_ALPHA
            )
        }
    }
}
