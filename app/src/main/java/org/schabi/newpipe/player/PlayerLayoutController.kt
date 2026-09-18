package org.schabi.newpipe.player

import android.content.res.Resources
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.text.CueGroup
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.PlayerBinding
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.ktx.animateRotation
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.util.external_communication.KoreUtils

/**
 * Owns how the shared player layout is arranged: popup and main players have a different look,
 * and the stream type of the playing item decides which views are shown at all.
 *
 * Every method here is a pure mapping from player state (type, fullscreen, stream type) to view
 * attributes. [Player] keeps delegate methods with the same names because the whole player
 * surface still talks to it, but no view styling decision is taken there anymore. The view
 * primitives it needs are read through [Player.getBinding], which remains the single owner of
 * the binding.
 */
class PlayerLayoutController(private val player: Player) {

    /** The cues ExoPlayer last reported; see [reapplyCues] for why they are kept. */
    private var lastCueGroup: CueGroup? = null

    /**
     * The constant look of the player views: the seek bar and the loading spinner tints, the
     * marquee of the title rows and the non-scrolling queue list.
     */
    fun initViews(binding: PlayerBinding) {
        binding.playbackSeekBar.thumb
            ?.setColorFilter(PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN))
        binding.playbackSeekBar.progressDrawable
            ?.setColorFilter(PorterDuffColorFilter(Color.RED, PorterDuff.Mode.MULTIPLY))

        binding.progressBarLoadingPanel.indeterminateDrawable
            ?.setColorFilter(PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.MULTIPLY))

        binding.titleTextView.isSelected = true
        binding.channelTextView.isSelected = true

        // Prevent hiding of bottom sheet via swipe inside queue
        binding.itemsList.isNestedScrollingEnabled = false
    }

    /**
     * Passes the window insets of the control root on to the overlays, and the display cutout on
     * to the queue panel. The overlays are offset with negative padding/margins so that they stay
     * centered and still reach under the system UI.
     */
    fun setupWindowInsets() {
        val binding = player.binding

        ViewCompat.setOnApplyWindowInsetsListener(binding.itemsListPanel) { view, windowInsets ->
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            if (cutout != Insets.NONE) {
                view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
            }
            windowInsets
        }

        // PlaybackControlRoot already consumed window insets but we should pass them to
        // player_overlays and fast_seek_overlay too. Without it they will be off-centered.
        binding.playbackControlRoot.addOnLayoutChangeListener(
            { v, _, _, _, _, _, _, _, _ ->
                binding.playerOverlays.setPadding(
                    v.paddingLeft,
                    v.paddingTop,
                    v.paddingRight,
                    v.paddingBottom
                )
                if (v.paddingLeft != 0 || v.paddingTop != 0
                    || v.paddingRight != 0 || v.paddingBottom != 0
                ) {
                    binding.playButtons.setPadding(
                        -v.paddingLeft, -v.paddingTop, -v.paddingRight, -v.paddingBottom)
                    binding.loadingPanelWrapper.setPadding(
                        -v.paddingLeft, -v.paddingTop, -v.paddingRight, -v.paddingBottom)
                } else {
                    binding.playButtons.setPadding(0, 0, 0, 0)
                    binding.loadingPanelWrapper.setPadding(0, 0, 0, 0)
                }

                // If we added padding to the fast seek overlay, too, it would not go under the
                // system ui. Instead we apply negative margins equal to the window insets of
                // the opposite side, so that the view covers all of the player (overflowing on
                // some sides) and its center coincides with the center of other controls.
                val fastSeekParams = binding.fastSeekOverlay.layoutParams as RelativeLayout.LayoutParams
                fastSeekParams.leftMargin = -v.paddingRight
                fastSeekParams.topMargin = -v.paddingBottom
                fastSeekParams.rightMargin = -v.paddingLeft
                fastSeekParams.bottomMargin = -v.paddingTop
            }
        )
    }

    /**
     * This method ensures that popup and main players have different look.
     * We use one layout for both players and need to decide what to show and what to hide.
     * Additional measuring should be done inside [setupElementsSize].
     */
    fun setupElementsVisibility() {
        val binding = player.binding

        if (player.popupPlayerSelected()) {
            binding.fullScreenButton.visibility = View.VISIBLE
            binding.screenRotationButton.visibility = View.GONE
            binding.resizeTextView.visibility = View.GONE
            binding.root.findViewById<View>(R.id.metadataView).visibility = View.GONE
            binding.queueButton.visibility = View.GONE
            binding.segmentsButton.visibility = View.GONE
            binding.moreOptionsButton.visibility = View.GONE
            binding.topControls.orientation = LinearLayout.HORIZONTAL
            binding.primaryControls.layoutParams.width = LinearLayout.LayoutParams.WRAP_CONTENT
            binding.secondaryControls.alpha = 1.0f
            binding.secondaryControls.visibility = View.VISIBLE
            binding.secondaryControls.translationY = 0f
            binding.share.visibility = View.GONE
            binding.switchCommentsVisibility.visibility = View.GONE
            binding.playWithKodi.visibility = View.GONE
            binding.openInBrowser.visibility = View.GONE
            binding.sleepTimer.visibility = View.GONE
            binding.switchMute.visibility = View.GONE
            binding.playerCloseButton.visibility = View.GONE
            binding.topControls.bringToFront()
            binding.topControls.isClickable = false
            binding.topControls.isFocusable = false
            binding.bottomControls.bringToFront()
            player.closeItemsList()
        } else if (player.videoPlayerSelected()) {
            binding.fullScreenButton.visibility = View.GONE
            player.uiModeController.setupScreenRotationButton()
            binding.resizeTextView.visibility = View.VISIBLE
            binding.root.findViewById<View>(R.id.metadataView).visibility = View.VISIBLE
            binding.moreOptionsButton.visibility = View.VISIBLE
            binding.topControls.orientation = LinearLayout.VERTICAL
            binding.primaryControls.layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            binding.secondaryControls.visibility = View.INVISIBLE
            binding.moreOptionsButton.setImageDrawable(
                AppCompatResources.getDrawable(player.context, R.drawable.ic_expand_more)
            )
            binding.share.visibility = View.VISIBLE
            binding.switchCommentsVisibility.visibility = View.VISIBLE
            binding.openInBrowser.visibility = View.VISIBLE
            binding.sleepTimer.visibility = View.VISIBLE
            binding.switchMute.visibility = View.VISIBLE
            binding.playerCloseButton.visibility =
                if (player.isFullscreen) View.GONE else View.VISIBLE
            // Top controls have a large minHeight which is allows to drag the player
            // down in fullscreen mode (just larger area to make easy to locate by finger)
            binding.topControls.isClickable = true
            binding.topControls.isFocusable = true
        }
        showHideKodiButton()

        if (player.isFullscreen) {
            binding.titleTextView.visibility = View.VISIBLE
            binding.channelTextView.visibility = View.VISIBLE
            binding.sleepTimer.visibility = View.VISIBLE
        } else {
            binding.titleTextView.visibility = View.GONE
            binding.channelTextView.visibility = View.GONE
            binding.sleepTimer.visibility = View.GONE
        }
        player.clickController.setMuteButton(binding.switchMute, player.isMuted())

        binding.moreOptionsButton.animateRotation(
            Player.DEFAULT_CONTROLS_DURATION.toLong(), 0)
    }

    /**
     * Changes padding, size of elements based on player selected right now.
     * Popup player has small padding in comparison with the main player
     */
    fun setupElementsSize() {
        val binding = player.binding
        val res: Resources = player.context.resources
        val buttonsMinWidth: Int
        val playerTopPad: Int
        val controlsPad: Int
        val buttonsPad: Int

        if (player.popupPlayerSelected()) {
            buttonsMinWidth = 0
            playerTopPad = 0
            controlsPad = res.getDimensionPixelSize(R.dimen.player_popup_controls_padding)
            buttonsPad = res.getDimensionPixelSize(R.dimen.player_popup_buttons_padding)
        } else if (player.videoPlayerSelected()) {
            buttonsMinWidth = res.getDimensionPixelSize(R.dimen.player_main_buttons_min_width)
            playerTopPad = res.getDimensionPixelSize(R.dimen.player_main_top_padding)
            controlsPad = res.getDimensionPixelSize(R.dimen.player_main_controls_padding)
            buttonsPad = res.getDimensionPixelSize(R.dimen.player_main_buttons_padding)
        } else {
            return
        }

        binding.topControls.setPaddingRelative(controlsPad, playerTopPad, controlsPad, 0)
        binding.bottomControls.setPaddingRelative(controlsPad, 0, controlsPad, 0)
        binding.qualityTextView.setPadding(buttonsPad, buttonsPad, buttonsPad, buttonsPad)
        binding.playbackSpeed.setPadding(buttonsPad, buttonsPad, buttonsPad, buttonsPad)
        binding.playbackSpeed.minimumWidth = buttonsMinWidth
        binding.captionTextView.setPadding(buttonsPad, buttonsPad, buttonsPad, buttonsPad)
    }

    /**
     * The two rows that say what is playing. Everything else a new [StreamInfo] implies is
     * decided by [PlayerMetadataController], which calls this along with the rest.
     */
    fun updateMetadataViews(info: StreamInfo) {
        player.binding.titleTextView.text = info.name
        player.binding.channelTextView.text = info.uploaderName
    }

    fun showHideKodiButton() {
        // show kodi button if it supports the current service and it is enabled in settings
        val queueItem = player.playQueue?.item
        player.binding.playWithKodi.visibility =
            if (player.videoPlayerSelected()
                && queueItem != null
                && KoreUtils.shouldShowPlayWithKodi(player.context, queueItem.serviceId)
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    /**
     * <p>Switches the views that depend on what is being played: an audio stream shows the
     * end screen instead of the surface, a live stream offers the live sync button and only a
     * video with a known quality offers the quality menu.</p>
     *
     * <p>The two odd sides of the original switch are kept on purpose: a video whose quality is
     * still unknown shows neither the end screen nor the duration, and a video that does have a
     * quality falls through to the "duration is known" look.</p>
     */
    fun updateStreamRelatedViews() {
        val binding = player.binding
        val info = player.currentStreamInfo.orElse(null) ?: return

        binding.qualityTextView.visibility = View.GONE
        binding.playbackSpeed.visibility = View.GONE

        binding.playbackEndTime.visibility = View.GONE
        binding.playbackLiveSync.visibility = View.GONE

        when (info.streamType) {
            StreamType.AUDIO_STREAM -> {
                binding.surfaceView.visibility = View.GONE
                binding.endScreen.visibility = View.VISIBLE
                binding.playbackEndTime.visibility = View.VISIBLE
            }

            StreamType.AUDIO_LIVE_STREAM -> {
                binding.surfaceView.visibility = View.GONE
                binding.endScreen.visibility = View.VISIBLE
                binding.playbackLiveSync.visibility = View.VISIBLE
            }

            StreamType.LIVE_STREAM -> {
                binding.surfaceView.visibility = View.VISIBLE
                binding.endScreen.visibility = View.GONE
                binding.playbackLiveSync.visibility = View.VISIBLE
            }

            StreamType.VIDEO_STREAM, StreamType.POST_LIVE_STREAM -> {
                val quality = player.currentMetadata?.maybeQuality?.orElse(null)
                if (quality != null
                    && (info.videoStreams.isNotEmpty() || info.videoOnlyStreams.isNotEmpty())
                ) {
                    player.menuController.buildQualityMenu()

                    binding.qualityTextView.visibility = View.VISIBLE
                    binding.surfaceView.visibility = View.VISIBLE
                    // The original switch fell through to the default branch from here on.
                    binding.endScreen.visibility = View.GONE
                    binding.playbackEndTime.visibility = View.VISIBLE
                }
                // Without a quality to offer, the original switch broke out of the whole
                // statement here, leaving the end screen and the duration hidden.
            }

            else -> {
                binding.endScreen.visibility = View.GONE
                binding.playbackEndTime.visibility = View.VISIBLE
            }
        }

        player.menuController.buildPlaybackSpeedMenu()
        binding.playbackSpeed.visibility = View.VISIBLE
    }

    //////////////////////////////////////////////////////////////////////////
    // ExoPlayer callbacks that only touch the views
    //////////////////////////////////////////////////////////////////////////

    fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        if (Player.DEBUG) {
            Log.d(
                Player.TAG, "ExoPlayer - playbackParameters(), speed = ["
                    + playbackParameters.speed + "], pitch = [" + playbackParameters.pitch + "]"
            )
        }
        player.binding.playbackSpeed.text =
            PlayerHelper.formatSpeed(playbackParameters.speed.toDouble())
    }

    fun onRenderedFirstFrame() {
        //TODO check if this causes black screen when switching to fullscreen
        player.binding.surfaceForeground.animate(false, Player.DEFAULT_CONTROLS_DURATION.toLong())
        reapplyCues()
    }

    fun onCues(cueGroup: CueGroup) {
        lastCueGroup = cueGroup
        player.binding.subtitleView.setCues(cueGroup.cues)
    }

    /**
     * Hands the current cues back to the subtitle view so that it draws them again.
     *
     * Entering or leaving fullscreen moves the player layout to another parent and gives the
     * video a new surface, and the cue that is on screen at that moment does not survive it.
     * The text renderer only emits a cue list when its content changes, so without this the
     * subtitles stay missing until the next line or until an unrelated tap redraws the view
     * (#2944).
     */
    fun reapplyCues() {
        lastCueGroup?.let { player.binding.subtitleView.setCues(it.cues) }
    }
}
