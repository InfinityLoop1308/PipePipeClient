package org.schabi.newpipe.player

import android.util.Log
import com.google.android.exoplayer2.Timeline
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.util.StreamTypeUtil
import java.util.Date

/**
 * Owns the transport surface of the player: play/pause, previous/next, fast-forward/rewind,
 * seeking, the live-edge and playback-edge checks, the queue/window synchronization performed
 * when [org.schabi.newpipe.player.playback.MediaSourceManager] rewinds, and the playback
 * position/duration readouts pushed into the control binding.
 *
 * [Player] keeps only the public API, delegating here, because the transport commands are the
 * contract used by the gestures, the notification, the media session and the queue activity.
 */
class PlayerTransportController(private val player: Player) {

    fun play() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "play() called")
        }
        val playQueue = player.playQueue
        val audioReactor = player.audioReactor
        if (audioReactor == null || playQueue == null || player.exoPlayerIsNull()) {
            return
        }

        audioReactor.requestAudioFocus()

        if (player.isLiveIdle()) {
            // The live source was released while paused: rebuild it and jump to the live edge
            // instead of resuming from the position the stream was paused at.
            player.exitLiveIdle()
            player.simpleExoPlayer.play()
            return
        }

        val item = playQueue.getItem()
        if (player.currentState.isCompleted && item != null
            && playQueue.getRecoveryPosition(item) / 1000 >= item.duration - 5
        ) {
            if (playQueue.index == 0) {
                seekToDefault()
            } else {
                playQueue.setIndex(0)
            }
        }

        player.simpleExoPlayer.play()
        player.saveStreamProgressState()
    }

    fun pause() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "pause() called")
        }
        if (player.audioReactor == null || player.exoPlayerIsNull()) {
            return
        }

        player.audioReactor.abandonAudioFocus()
        player.simpleExoPlayer.pause()
        player.saveStreamProgressState()
    }

    fun playPause() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onPlayPause() called")
        }

        if (player.getPlayWhenReady()
                // When state is completed (replay button is shown) then (re)play and do not pause
            && !player.currentState.isCompleted
        ) {
            pause()
        } else {
            play()
        }
    }

    fun playPrevious() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onPlayPrevious() called")
        }
        val playQueue = player.playQueue
        if (player.exoPlayerIsNull() || playQueue == null) {
            return
        }

        /* If current playback has run for PLAY_PREV_ACTIVATION_LIMIT_MILLIS milliseconds,
         * restart current track. Also restart the track if the current track
         * is the first in a queue.*/
        if (player.simpleExoPlayer.currentPosition > Player.PLAY_PREV_ACTIVATION_LIMIT_MILLIS
            || playQueue.index == 0
        ) {
            seekToDefault()
            playQueue.offsetIndex(0)
        } else {
            player.saveStreamProgressState()
            playQueue.offsetIndex(-1)
        }
        player.triggerProgressUpdate()
    }

    fun playNext() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onPlayNext() called")
        }
        val playQueue = player.playQueue
        if (playQueue == null) {
            return
        }

        player.saveStreamProgressState()
        playQueue.offsetIndex(+1)
        player.triggerProgressUpdate()
    }

    fun fastForward() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "fastRewind() called")
        }
        seekBy(PlayerHelper.retrieveSeekDurationFromPreferences(player).toLong())
        player.triggerProgressUpdate(true)
    }

    fun fastRewind() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "fastRewind() called")
        }
        seekBy(-PlayerHelper.retrieveSeekDurationFromPreferences(player).toLong())
        if (player.prefs.getBoolean(
                player.context.getString(R.string.sponsor_block_graced_rewind_key), false
            )
        ) {
            player.triggerProgressUpdate(true, true, false, false)
            return
        }

        player.sponsorBlockController.onNonGracedRewind() // else rewind into segment won't skip
        player.triggerProgressUpdate(true)
    }

    fun seekTo(positionMillis: Long) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "seekBy() called with: position = [$positionMillis]")
        }
        if (player.exoPlayerIsNull()) {
            return
        }
        // prevent invalid positions when fast-forwarding/-rewinding
        var normalizedPositionMillis = positionMillis
        if (normalizedPositionMillis < 0) {
            normalizedPositionMillis = 0
        } else if (normalizedPositionMillis > player.simpleExoPlayer.duration) {
            normalizedPositionMillis = player.simpleExoPlayer.duration
        }

        player.simpleExoPlayer.seekTo(normalizedPositionMillis)
    }

    private fun seekBy(offsetMillis: Long) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "seekBy() called with: offsetMillis = [$offsetMillis]")
        }
        seekTo(player.simpleExoPlayer.currentPosition + offsetMillis)
    }

    fun seekToDefault() {
        if (!player.exoPlayerIsNull()) {
            player.simpleExoPlayer.seekToDefaultPosition()
        }
    }

    fun isApproachingPlaybackEdge(timeToEndMillis: Long): Boolean {
        // If live, then not near playback edge
        // If not playing, then not approaching playback edge
        if (player.exoPlayerIsNull() || player.isLive() || !player.isPlaying()) {
            return false
        }

        val currentPositionMillis = player.simpleExoPlayer.currentPosition
        val currentDurationMillis = player.simpleExoPlayer.duration
        return currentDurationMillis - currentPositionMillis < timeToEndMillis
    }

    /**
     * Checks if the current playback is a livestream AND is playing at or beyond the live edge.
     *
     * @return whether the livestream is playing at or beyond the edge
     */
    fun isLiveEdge(): Boolean {
        if (player.exoPlayerIsNull() || !player.isLive()) {
            return false
        }

        val currentTimeline = player.simpleExoPlayer.currentTimeline
        val currentWindowIndex = player.simpleExoPlayer.currentMediaItemIndex
        if (currentTimeline.isEmpty || currentWindowIndex < 0
            || currentWindowIndex >= currentTimeline.windowCount
        ) {
            return false
        }

        val timelineWindow = Timeline.Window()
        currentTimeline.getWindow(currentWindowIndex, timelineWindow)
        return timelineWindow.defaultPositionMs <= player.simpleExoPlayer.currentPosition
    }

    fun onPlaybackSynchronize(item: PlayerMediaItem, wasBlocked: Boolean) {
        if (Player.DEBUG) {
            Log.d(
                Player.TAG, "Playback - onPlaybackSynchronize(was blocked: " + wasBlocked
                        + ") called with item=[" + item.title + "], url=[" + item.url + "]"
            )
        }
        val playQueue = player.playQueue
        if (player.exoPlayerIsNull() || playQueue == null) {
            return
        }

        val currentItem = player.currentItem
        val hasPlayQueueItemChanged = currentItem == null
                || item.uuid != currentItem.uuid

        val currentPlayQueueIndex = playQueue.indexOf(item)
        val currentPlaylistIndex = player.simpleExoPlayer.currentMediaItemIndex
        val currentPlaylistSize = player.simpleExoPlayer.currentTimeline.windowCount

        // If nothing to synchronize
        if (!hasPlayQueueItemChanged) {
            return
        }
        player.setCurrentItem(item)

        // Check if on wrong window
        if (currentPlayQueueIndex != playQueue.index) {
            Log.e(
                Player.TAG, "Playback - Play Queue may be desynchronized: item "
                        + "index=[" + currentPlayQueueIndex + "], "
                        + "queue index=[" + playQueue.index + "]"
            )

            // Check if bad seek position
        } else if ((currentPlaylistSize > 0 && currentPlayQueueIndex >= currentPlaylistSize)
            || currentPlayQueueIndex < 0
        ) {
            Log.e(
                Player.TAG, "Playback - Trying to seek to invalid "
                        + "index=[" + currentPlayQueueIndex + "] with "
                        + "playlist length=[" + currentPlaylistSize + "]"
            )
        } else if (wasBlocked || currentPlaylistIndex != currentPlayQueueIndex
            || !player.isPlaying()
        ) {
            if (Player.DEBUG) {
                Log.d(
                    Player.TAG, "Playback - Rewinding to correct "
                            + "index=[" + currentPlayQueueIndex + "], "
                            + "from=[" + currentPlaylistIndex + "], "
                            + "size=[" + currentPlaylistSize + "]."
                )
            }

            if (playQueue.getRecoveryPosition(item) != PlayQueue.RECOVERY_UNSET
                && player.shouldSeek()
            ) {
                player.simpleExoPlayer.seekTo(
                    currentPlayQueueIndex, playQueue.getRecoveryPosition(item)
                )
                playQueue.unsetRecovery(currentPlayQueueIndex)
            } else {
                player.simpleExoPlayer.seekToDefaultPosition(currentPlayQueueIndex)
            }
        }
    }

    /**
     * Sets the current duration time into the corresponding elements.
     * @param currentProgress the current playback position, in milliseconds
     */
    fun updatePlayBackElementsCurrentDuration(currentProgress: Int) {
        val binding = player.binding
        if (!player.currentState.isPausedSeek) {
            binding.playbackSeekBar.progress = currentProgress
        }
        // YouTube livestreams use DASH and getCurrentPosition() works correctly
        // Other services (HLS) need startAt hack to show correct time
        val currentItem = player.currentItem
        val currentMetadata = player.currentMetadata
        if (currentItem != null
            && StreamTypeUtil.isLiveStream(currentItem.streamType)
            && currentMetadata != null
            && currentMetadata.serviceId != ServiceList.YouTube.serviceId
            && currentItem.startAt != -1L
        ) {
            binding.playbackCurrentTime.text = PlayerHelper.getTimeString(
                (Date().time - currentItem.startAt).toInt()
            )
        } else {
            binding.playbackCurrentTime.text = PlayerHelper.getTimeString(currentProgress)
        }
    }

    /**
     * Sets the video duration time into all control components (e.g. seekbar).
     * @param duration the stream duration, in milliseconds
     */
    fun setVideoDurationToControls(duration: Int) {
        val binding = player.binding
        binding.playbackEndTime.text = PlayerHelper.getTimeString(duration)

        binding.playbackSeekBar.setMax(duration)
        // This is important for Android TVs otherwise it would apply the default from
        // setMax/Min methods which is (max - min) / 20
        binding.playbackSeekBar.setKeyProgressIncrement(
            PlayerHelper.retrieveSeekDurationFromPreferences(player)
        )
    }
}
