package org.schabi.newpipe.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.WindowManager
import org.schabi.newpipe.fragments.detail.VideoDetailFragment
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage

/**
 * Owns the broadcast receiver of a [Player] and the last known orientation.
 *
 * The [IntentFilter], the registered [BroadcastReceiver] and the handling of the player actions
 * sent by the notification and the video detail fragment all live here. [Player] only keeps a few
 * package-private primitives this controller cannot own (the seek, the screen size, the
 * fullscreen state), so the playback intents are handled exactly as before.
 */
class PlayerBroadcastReceiver(private val player: Player) {

    private val context: Context = player.context

    private var wasLandscape: Boolean = player.service.isLandscape

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            onBroadcastReceived(intent)
        }
    }

    private val intentFilter = IntentFilter()

    fun setup() {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "setupBroadcastReceiver() called")
        }

        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

        intentFilter.addAction(PlayerService.ACTION_CLOSE)
        intentFilter.addAction(PlayerService.ACTION_PLAY_PAUSE)
        intentFilter.addAction(PlayerService.ACTION_PLAY_PREVIOUS)
        intentFilter.addAction(PlayerService.ACTION_PLAY_NEXT)
        intentFilter.addAction(PlayerService.ACTION_FAST_REWIND)
        intentFilter.addAction(PlayerService.ACTION_FAST_FORWARD)
        intentFilter.addAction(PlayerService.ACTION_REPEAT)
        intentFilter.addAction(PlayerService.ACTION_SHUFFLE)
        intentFilter.addAction(PlayerService.ACTION_RECREATE_NOTIFICATION)

        intentFilter.addAction(VideoDetailFragment.ACTION_SEEK_TO)
        intentFilter.addAction(VideoDetailFragment.ACTION_VIDEO_FRAGMENT_RESUMED)
        intentFilter.addAction(VideoDetailFragment.ACTION_VIDEO_FRAGMENT_STOPPED)

        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        intentFilter.addAction(Intent.ACTION_SCREEN_ON)
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF)
        intentFilter.addAction(Intent.ACTION_HEADSET_PLUG)

        intentFilter.addAction(VideoDetailFragment.ACTION_VIDEO_ERROR)
    }

    private fun onBroadcastReceived(intent: Intent?) {
        val action = intent?.action ?: return

        if (Player.DEBUG) {
            Log.d(Player.TAG, "onBroadcastReceived() called with: intent = [$intent]")
        }

        when (action) {
            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> player.pause()
            PlayerService.ACTION_CLOSE -> player.service.stopService()
            PlayerService.ACTION_PLAY_PAUSE -> {
                player.playPause()
                if (!player.listeners.isFragmentVisible) {
                    // Ensure that we have audio-only stream playing when a user
                    // started to play from notification's play button from outside of the app
                    player.onFragmentStopped()
                }
            }
            PlayerService.ACTION_PLAY_PREVIOUS -> player.playPrevious()
            PlayerService.ACTION_PLAY_NEXT -> player.playNext()
            PlayerService.ACTION_FAST_REWIND -> player.fastRewind()
            PlayerService.ACTION_FAST_FORWARD -> player.fastForward()
            PlayerService.ACTION_REPEAT -> player.onRepeatClicked()
            PlayerService.ACTION_SHUFFLE -> player.onShuffleClicked()
            PlayerService.ACTION_RECREATE_NOTIFICATION ->
                NotificationUtil.getInstance()
                    .createNotificationIfNeededAndUpdate(player, true)
            VideoDetailFragment.ACTION_SEEK_TO -> {
                player.seekTo(intent.getIntExtra("Timestamp", 0) * 1000L)
                if (player.progressController.wasPlaying) {
                    player.simpleExoPlayer.play()
                }
            }
            VideoDetailFragment.ACTION_VIDEO_FRAGMENT_RESUMED -> {
                player.listeners.isFragmentVisible = true
                player.useVideoSource(true)
            }
            VideoDetailFragment.ACTION_VIDEO_FRAGMENT_STOPPED -> {
                player.listeners.isFragmentVisible = false
                player.onFragmentStopped()
            }
            Intent.ACTION_CONFIGURATION_CHANGED -> {
                assureCorrectAppLanguage(player.service.instance)
                if (Player.DEBUG) {
                    Log.d(Player.TAG, "onConfigurationChanged() called")
                }
                if (player.popupPlayerSelected()) {
                    player.updateScreenSize()
                    val params: WindowManager.LayoutParams? = player.popupLayoutParams
                    if (params != null) {
                        player.changePopupSize(params.width)
                    }
                    player.checkPopupPositionBounds()
                }
                val landscape = player.service.isLandscape
                if (wasLandscape != landscape) {
                    wasLandscape = landscape
                    if (player.listeners.isFragmentVisible) {
                        player.uiModeController.onOrientationChanged(landscape)
                    }
                }
                // Close popup menus to prevent crash when view is not attached after rotation
                player.closeAllPopupMenus()
                // Close it because when changing orientation from portrait
                // (in fullscreen mode) the size of queue layout can be larger than the screen size
                player.closeItemsList()
                // When the orientation changed, the screen height might be smaller.
                // If the end screen thumbnail is not re-scaled,
                // it can be larger than the current screen height
                // and thus enlarging the whole player.
                // This causes the seekbar to be ouf the visible area.
                player.updateEndScreenThumbnail()
            }
            Intent.ACTION_SCREEN_ON -> {
                // Interrupt playback only when screen turns on
                // and user is watching video in popup player.
                // Same actions for video player will be handled in ACTION_VIDEO_FRAGMENT_RESUMED
                if (player.popupPlayerSelected() && (player.isPlaying || player.isLoading)) {
                    player.useVideoSource(true)
                }
            }
            Intent.ACTION_SCREEN_OFF -> {
                // Interrupt playback only when screen turns off with popup player working
                if (player.popupPlayerSelected() && (player.isPlaying || player.isLoading)) {
                    player.useVideoSource(false)
                }
            }
            Intent.ACTION_HEADSET_PLUG -> {
                // FIXME
                /*notificationManager.cancel(NOTIFICATION_ID);
                mediaSessionManager.dispose();
                mediaSessionManager.enable(getBaseContext(), basePlayerImpl.simpleExoPlayer);*/
            }
        }
    }

    fun register() {
        // Try to unregister current first
        unregister()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(broadcastReceiver, intentFilter)
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(broadcastReceiver)
        } catch (unregisteredException: IllegalArgumentException) {
            Log.w(
                Player.TAG, "Broadcast receiver already unregistered: "
                    + unregisteredException.message
            )
        }
    }
}
