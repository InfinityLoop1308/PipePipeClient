package org.schabi.newpipe.player

import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.disposables.SerialDisposable
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.Frameset
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.ktx.AnimationType
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.seekbarpreview.SeekbarPreviewThumbnailHelper
import org.schabi.newpipe.player.seekbarpreview.SeekbarPreviewThumbnailHolder
import org.schabi.newpipe.util.StreamTypeUtil
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.function.IntSupplier

/**
 * Owns the progress loop of a [Player] and the seekbar interaction.
 *
 * The periodic ticker (its [Disposable]), the seekbar preview thumbnails, whether the user was
 * playing before seeking and whether playback was at the live edge all live here. [Player] only
 * keeps delegate methods with the same names, so the ExoPlayer listener callbacks, the playback
 * state machine and [SponsorBlockController] are unaffected, and it keeps implementing
 * [SeekBar.OnSeekBarChangeListener] by forwarding to this controller.
 */
class PlayerProgressController(private val player: Player) {

    private val progressUpdateDisposable = SerialDisposable()

    private val seekbarPreviewThumbnailHolder = SeekbarPreviewThumbnailHolder()

    /** Whether playback was running before the user started dragging the seekbar. */
    var wasPlaying = false

    private var wasAtLiveEdge = false

    fun resetPreviewThumbnails(frames: List<Frameset>) {
        seekbarPreviewThumbnailHolder.resetFrom(player.context, frames)
    }

    fun startProgressLoop() {
        progressUpdateDisposable.set(createProgressUpdateDisposable())
    }

    fun stopProgressLoop() {
        progressUpdateDisposable.set(null)
    }

    fun isProgressLoopRunning(): Boolean = progressUpdateDisposable.get() != null

    fun triggerProgressUpdate() {
        triggerProgressUpdate(false, false, false, false)
    }

    fun triggerProgressUpdate(isRewind: Boolean) {
        triggerProgressUpdate(isRewind, false, false, false)
    }

    fun triggerProgressUpdate(
        isRewind: Boolean,
        isGracedRewind: Boolean,
        bypassSecondaryMode: Boolean,
        isUnSkip: Boolean
    ) {
        if (player.exoPlayerIsNull()) {
            return
        }

        val exoPlayer = player.simpleExoPlayer
        val currentItem = player.currentItem

        // Use duration of currentItem for non-live streams,
        // because HLS streams are fragmented
        // and thus the whole duration is not available to the player
        // TODO: revert #6307 when introducing proper HLS support
        val duration: Int = if (currentItem != null
            && !StreamTypeUtil.isLiveStream(currentItem.streamType)
        ) {
            // convert seconds to milliseconds
            (currentItem.duration * 1000).toInt()
        } else {
            exoPlayer.duration.toInt()
        }
        val currentProgress = maxOf(exoPlayer.currentPosition.toInt(), 0)

        if (player.prefs.getBoolean(
                player.context.getString(R.string.force_end_on_overtime_key), false
            )
            && currentItem != null
            && currentItem.streamType == StreamType.VIDEO_STREAM
            && !player.currentState.isCompleted
            && duration > 0
            && currentProgress > duration + 3000
        ) {
            player.changeState(PlayerPlaybackState.COMPLETED)
            player.saveStreamProgressStateCompleted()
            player.isPrepared = false
            return
        }

        onUpdateProgress(
            currentProgress,
            exoPlayer.duration.toInt(),
            exoPlayer.bufferedPercentage
        )
        if (player.isPrepared) {
            player.sponsorBlockController.onProgress(
                currentProgress, isRewind, isGracedRewind, bypassSecondaryMode, isUnSkip
            )
        }
    }

    private fun createProgressUpdateDisposable(): Disposable =
        Observable.interval(
            Player.PROGRESS_LOOP_INTERVAL_MILLIS.toLong(), MILLISECONDS,
            AndroidSchedulers.mainThread()
        )
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { triggerProgressUpdate(false) },
                { error -> Log.e(Player.TAG, "Progress update failure: ", error) }
            )

    private fun onUpdateProgress(
        currentProgress: Int,
        duration: Int,
        bufferPercent: Int
    ) {
        if (!player.isPrepared) {
            return
        }

        val binding = player.binding
        if (duration != binding.playbackSeekBar.max) {
            player.setVideoDurationToControls(duration)
        }
        if (!player.currentState.isPaused) {
            player.updatePlayBackElementsCurrentDuration(currentProgress)
        }
        if (player.simpleExoPlayer.isLoading || bufferPercent > 90) {
            binding.playbackSeekBar.secondaryProgress =
                (binding.playbackSeekBar.max * (bufferPercent / 100f)).toInt()
        }
        if (Player.DEBUG && bufferPercent % 20 == 0) { // Limit log
            Log.d(
                Player.TAG, "notifyProgressUpdateToListeners() called with: " +
                    "isVisible = " + player.isControlsVisible + ", " +
                    "currentProgress = [$currentProgress], " +
                    "duration = [$duration], bufferPercent = [$bufferPercent]"
            )
        }
        binding.playbackLiveSync.isClickable = !player.isLiveEdge

        val isCurrentlyAtLiveEdge = player.isLiveEdge
        if (isCurrentlyAtLiveEdge && !wasAtLiveEdge && player.playbackSpeed != 1.0f) {
            player.playbackSpeed = 1.0f
        }
        wasAtLiveEdge = isCurrentlyAtLiveEdge

        player.listeners.notifyProgressUpdateToListeners(
            currentProgress, duration, bufferPercent
        )

        if (player.areSegmentsVisible()) {
            player.segmentAdapter.selectSegmentAt(
                player.getNearestStreamSegmentPosition(currentProgress.toLong())
            )
        }

        if (player.isQueueVisible) {
            player.updateQueueTime(currentProgress)
        }
    }

    // Seekbar listener //

    fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        // Currently we don't need method execution when fromUser is false
        if (!fromUser) {
            return
        }
        if (Player.DEBUG) {
            Log.d(
                Player.TAG, "onProgressChanged() called with: " +
                    "seekBar = [$seekBar], progress = [$progress]"
            )
        }

        val binding = player.binding
        binding.currentDisplaySeek.text = PlayerHelper.getTimeString(progress)

        // Seekbar Preview Thumbnail
        val previewThumbnail = seekbarPreviewThumbnailHolder.getBitmapAt(progress)
        if (previewThumbnail.isPresent) {
            SeekbarPreviewThumbnailHelper.tryResizeAndSetSeekbarPreviewThumbnail(
                player.context,
                previewThumbnail.get(),
                binding.currentSeekbarPreviewThumbnail,
                IntSupplier { binding.subtitleView.width }
            )
        }

        adjustSeekbarPreviewContainer()
    }

    private fun adjustSeekbarPreviewContainer() {
        val binding = player.binding
        try {
            // Should only be required when an error occurred before
            // and the layout was positioned in the center
            binding.bottomSeekbarPreviewLayout.gravity = Gravity.NO_GRAVITY

            // Calculate the current left position of seekbar progress in px
            // More info: https://stackoverflow.com/q/20493577
            val currentSeekbarLeft = binding.playbackSeekBar.left +
                binding.playbackSeekBar.paddingLeft +
                binding.playbackSeekBar.thumb.bounds.left

            // Calculate the (unchecked) left position of the container
            val uncheckedContainerLeft =
                currentSeekbarLeft - binding.seekbarPreviewContainer.width / 2

            // Fix the position so it's within the boundaries
            val checkedContainerLeft = maxOf(
                minOf(
                    uncheckedContainerLeft,
                    // Max left
                    binding.playbackWindowRoot.width - binding.seekbarPreviewContainer.width
                ),
                0 // Min left
            )

            // See also: https://stackoverflow.com/a/23249734
            val params = LinearLayout.LayoutParams(
                binding.seekbarPreviewContainer.layoutParams
            )
            params.setMarginStart(checkedContainerLeft)
            binding.seekbarPreviewContainer.layoutParams = params
        } catch (ex: Exception) {
            Log.e(Player.TAG, "Failed to adjust seekbarPreviewContainer", ex)
            // Fallback - position in the middle
            binding.bottomSeekbarPreviewLayout.gravity = Gravity.CENTER
        }
    }

    fun onStartTrackingTouch(seekBar: SeekBar) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onStartTrackingTouch() called with: seekBar = [$seekBar]")
        }
        if (!player.currentState.isPausedSeek) {
            player.changeState(PlayerPlaybackState.PAUSED_SEEK)
        }

        saveWasPlaying()
        if (player.isPlaying) {
            player.simpleExoPlayer.pause()
        }

        player.showControls(0)
        player.binding.currentDisplaySeek.animate(
            true, Player.DEFAULT_CONTROLS_DURATION.toLong(), AnimationType.SCALE_AND_ALPHA
        )
        player.binding.currentSeekbarPreviewThumbnail.animate(
            true, Player.DEFAULT_CONTROLS_DURATION.toLong(), AnimationType.SCALE_AND_ALPHA
        )
    }

    fun onStopTrackingTouch(seekBar: SeekBar) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onStopTrackingTouch() called with: seekBar = [$seekBar]")
        }

        player.seekTo(seekBar.progress.toLong())
        if (wasPlaying || player.simpleExoPlayer.duration == seekBar.progress.toLong()) {
            player.simpleExoPlayer.play()
        }

        val binding = player.binding
        binding.playbackCurrentTime.text = PlayerHelper.getTimeString(seekBar.progress)
        binding.currentDisplaySeek.animate(false, 200, AnimationType.SCALE_AND_ALPHA)
        binding.currentSeekbarPreviewThumbnail.animate(
            false, 200, AnimationType.SCALE_AND_ALPHA
        )

        if (player.currentState.isPausedSeek) {
            player.changeState(PlayerPlaybackState.BUFFERING)
        }
        if (!isProgressLoopRunning()) {
            startProgressLoop()
        }
        if (wasPlaying) {
            player.showControlsThenHide()
        }
    }

    fun saveWasPlaying() {
        wasPlaying = player.playWhenReady
    }
}
