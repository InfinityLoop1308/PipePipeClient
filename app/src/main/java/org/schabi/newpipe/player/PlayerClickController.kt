package org.schabi.newpipe.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.fragments.detail.VideoDetailFragment
import org.schabi.newpipe.ktx.AnimationType
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.ktx.animateRotation
import org.schabi.newpipe.local.dialog.PlaylistDialog
import org.schabi.newpipe.sleep.SleepTimerService
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.external_communication.KoreUtils
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * Owns everything that hangs off a player control button: the click/long-click routing, the
 * more-options drawer animation, the share / open-in-browser / Kodi / sleep-timer actions, the
 * mute toggle and the "add to playlist" dialog.
 *
 * [Player] keeps only the public entry points that other components call
 * ([Player.onMuteUnmuteButtonClicked], [Player.onAddToPlaylistClicked],
 * [Player.manageControlsAfterOnClick]) and delegates them here.
 */
class PlayerClickController(private val player: Player) :
    View.OnClickListener,
    View.OnLongClickListener {

    /** Wires every control button of the player binding to this controller. */
    fun setup() {
        val binding = player.binding

        binding.qualityTextView.setOnClickListener { v ->
            player.menuController.onQualityClicked(v)
        }
        binding.playbackSpeed.setOnClickListener { v ->
            player.menuController.onPlaybackSpeedClicked(v)
        }

        binding.playbackSeekBar.setOnSeekBarChangeListener(player.progressController)
        binding.captionTextView.setOnClickListener(this)
        binding.audioTrackTextView.setOnClickListener(this)
        binding.resizeTextView.setOnClickListener(this)
        binding.playbackLiveSync.setOnClickListener(this)

        binding.queueButton.setOnClickListener { player.queueController.onQueueClicked() }
        binding.segmentsButton.setOnClickListener { player.queueController.onSegmentsClicked() }
        binding.repeatButton.setOnClickListener { player.onRepeatClicked() }
        binding.shuffleButton.setOnClickListener { player.onShuffleClicked() }
        binding.addToPlaylistButton.setOnClickListener {
            val activity = player.parentActivity
            if (activity != null) {
                player.onAddToPlaylistClicked(activity.supportFragmentManager)
            }
        }

        binding.playPauseButton.setOnClickListener(this)
        binding.playPreviousButton.setOnClickListener(this)
        binding.playNextButton.setOnClickListener(this)

        binding.moreOptionsButton.setOnClickListener(this)
        binding.moreOptionsButton.setOnLongClickListener(this)
        binding.share.setOnClickListener(this)
        binding.share.setOnLongClickListener(this)
        binding.fullScreenButton.setOnClickListener(this)
        binding.screenRotationButton.setOnClickListener(this)
        binding.switchCommentsVisibility.setOnClickListener(this)
        binding.playWithKodi.setOnClickListener(this)
        binding.openInBrowser.setOnClickListener(this)
        binding.playerCloseButton.setOnClickListener(this)
        binding.switchMute.setOnClickListener(this)
        binding.sleepTimer.setOnClickListener(this)
        binding.sleepTimer.setOnLongClickListener(this)
        binding.skipButton.setOnClickListener(this)
        binding.unskipButton.setOnClickListener(this)
    }

    override fun onClick(v: View) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onClick() called with: v = [$v]")
        }
        val binding = player.binding
        if (v.id == binding.resizeTextView.id) {
            player.menuController.onDisplayModeClicked()
        } else if (v.id == binding.captionTextView.id) {
            player.menuController.onCaptionClicked()
        } else if (v.id == binding.audioTrackTextView.id) {
            player.menuController.onAudioTrackClicked()
        } else if (v.id == binding.playbackLiveSync.id) {
            player.seekToDefault()
        } else if (v.id == binding.playPauseButton.id) {
            player.playPause()
        } else if (v.id == binding.playPreviousButton.id) {
            player.playPrevious()
        } else if (v.id == binding.playNextButton.id) {
            player.playNext()
        } else if (v.id == binding.moreOptionsButton.id) {
            onMoreOptionsClicked()
        } else if (v.id == binding.share.id) {
            ShareUtils.shareText(
                player.context, player.videoTitle, player.getVideoUrlAtCurrentTime(),
                player.currentItem!!.thumbnailUrl
            )
        } else if (v.id == binding.switchCommentsVisibility.id) {
            player.bulletCommentsController.toggleVisibility()
        } else if (v.id == binding.playWithKodi.id) {
            onPlayWithKodiClicked()
        } else if (v.id == binding.openInBrowser.id) {
            onOpenInBrowserClicked()
        } else if (v.id == binding.sleepTimer.id) {
            onSleepTimerClicked()
        } else if (v.id == binding.fullScreenButton.id) {
            player.setRecovery()
            if (player.popupPlayerSelected()) {
                // Clean up popup properly before switching to main player
                player.service.stopService()
            }
            NavigationHelper.playOnMainPlayer(player.context, player.playQueue!!, true)
            return
        } else if (v.id == binding.screenRotationButton.id) {
            player.changeFullscreen(!player.isFullscreen)
        } else if (v.id == binding.switchMute.id) {
            onMuteUnmuteButtonClicked()
        } else if (v.id == binding.playerCloseButton.id) {
            player.context.sendBroadcast(Intent(VideoDetailFragment.ACTION_HIDE_MAIN_PLAYER))
            player.service.stopService()
        } else if (v.id == binding.skipButton.id) {
            player.sponsorBlockController.onSkipClicked()
        } else if (v.id == binding.unskipButton.id) {
            player.sponsorBlockController.onUnskipClicked()
        }

        manageControlsAfterOnClick(v)
    }

    /**
     * Manages the controls after a click occurred on the player UI.
     * @param v - The view that was clicked
     */
    fun manageControlsAfterOnClick(v: View) {
        player.controlsVisibilityController.manageControlsAfterOnClick(v)
    }

    override fun onLongClick(v: View): Boolean {
        val binding = player.binding
        if (v.id == binding.moreOptionsButton.id && player.isFullscreen) {
            player.listeners.onMoreOptionsLongClicked()
            player.hideControls(0L, 0L)
            player.hideSystemUIIfNeeded()
        } else if (v.id == binding.share.id) {
            ShareUtils.copyToClipboard(player.context, player.getVideoUrlAtCurrentTime())
        } else if (v.id == binding.sleepTimer.id) {
            onSleepTimerLongClicked()
        }
        return true
    }

    fun onAddToPlaylistClicked(fragmentManager: FragmentManager) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onAddToPlaylistClicked() called")
        }

        val playQueue = player.playQueue ?: return
        PlaylistDialog.createCorrespondingDialog(
            player.context,
            playQueue.streams.map { StreamEntity(it) },
        ) { dialog -> dialog.show(fragmentManager, Player.TAG) }
    }

    fun onMuteUnmuteButtonClicked() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onMuteUnmuteButtonClicked() called")
        }
        player.simpleExoPlayer.setVolume(if (player.isMuted()) 1f else 0f)
        player.notifyPlaybackUpdateToListeners()
        setMuteButton(player.binding.switchMute, player.isMuted())
    }

    fun setMuteButton(button: ImageButton, isMuted: Boolean) {
        button.setImageDrawable(
            AppCompatResources.getDrawable(
                player.context,
                if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            )
        )
    }

    private fun onMoreOptionsClicked() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "onMoreOptionsClicked() called")
        }
        val binding = player.binding

        val isMoreControlsVisible = binding.secondaryControls.visibility == View.VISIBLE

        binding.moreOptionsButton.animateRotation(
            Player.DEFAULT_CONTROLS_DURATION.toLong(),
            if (isMoreControlsVisible) 0 else 180
        )
        binding.secondaryControls.animate(
            !isMoreControlsVisible,
            Player.DEFAULT_CONTROLS_DURATION.toLong(),
            AnimationType.SLIDE_AND_ALPHA,
            0L,
        ) {
            // Fix for a ripple effect on background drawable.
            // When view returns from GONE state it takes more milliseconds than returning
            // from INVISIBLE state. And the delay makes ripple background end to fast
            if (isMoreControlsVisible) {
                binding.secondaryControls.visibility = View.INVISIBLE
            }
        }
        player.showControls(Player.DEFAULT_CONTROLS_DURATION.toLong())
    }

    private fun onPlayWithKodiClicked() {
        if (player.currentMetadata != null) {
            player.pause()
            try {
                NavigationHelper.playWithKore(player.context, Uri.parse(player.getVideoUrl()))
            } catch (e: Exception) {
                if (Player.DEBUG) {
                    Log.i(Player.TAG, "Failed to start kore", e)
                }
                KoreUtils.showInstallKoreDialog(player.parentActivity!!)
            }
        }
    }

    private fun onOpenInBrowserClicked() {
        player.currentStreamInfo
            .map { info -> info.originalUrl }
            .ifPresent { originalUrl ->
                ShareUtils.openUrlInBrowser(player.parentActivity!!, originalUrl)
            }
    }

    private fun onSleepTimerClicked() {
        val activity: AppCompatActivity = player.parentActivity!!

        val serviceIntent = Intent(activity, SleepTimerService::class.java)
        serviceIntent.action = SleepTimerService.ACTION_START_TIMER
        // get time from shared preferences
        val time = Integer.parseInt(
            PreferenceManager.getDefaultSharedPreferences(activity).getString(
                activity.getString(R.string.sleep_timer_length_key), "15"
            )!!
        )
        serviceIntent.putExtra("timeInMillis", time * 60000) // 60 seconds
        activity.startService(serviceIntent)
        player.binding.sleepTimer.setImageDrawable(
            AppCompatResources.getDrawable(player.context, R.drawable.ic_timer)
        )
    }

    private fun onSleepTimerLongClicked() {
        val activity: Activity = player.parentActivity!!

        val serviceIntent = Intent(activity, SleepTimerService::class.java)
        serviceIntent.action = SleepTimerService.ACTION_STOP_TIMER
        activity.startService(serviceIntent)
        player.binding.sleepTimer.setImageDrawable(
            AppCompatResources.getDrawable(player.context, R.drawable.ic_timer_off)
        )
    }
}
