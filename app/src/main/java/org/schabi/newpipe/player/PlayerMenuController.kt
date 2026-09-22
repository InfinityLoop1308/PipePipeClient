package org.schabi.newpipe.player

import android.app.AlertDialog
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuCompat
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.helper.PlaybackParameterDialog
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.mediaitem.MediaItemQuality
import java.util.Locale

/**
 * Owns the player's popup menus: quality, playback speed, captions, audio track and display mode.
 *
 * All the logic that builds those menus and reacts to their items stays here, so that [Player]
 * only exposes a few package-private accessors for the playback state and view primitives it
 * actually needs. It also owns whether a popup menu is currently visible, the display-mode
 * state (the forced and natural aspect ratios) and which quality the current item is played
 * at, so that the gesture listener, [Player] and [PopupWindowController] can query or apply
 * them without [Player] holding that state. This is a view/event helper: it does not hold
 * playback state itself.
 */
class PlayerMenuController(
    private val player: Player
) : PopupMenu.OnMenuItemClickListener, PopupMenu.OnDismissListener {

    private companion object {
        const val TAG = "PlayerMenuController"

        const val POPUP_MENU_ID_QUALITY = 69
        const val POPUP_MENU_ID_PLAYBACK_SPEED = 79
        const val POPUP_MENU_ID_CAPTION = 89
        const val POPUP_MENU_ID_AUDIO_TRACK = 99
        const val POPUP_MENU_ID_DISPLAY_MODE = 109
        const val POPUP_MENU_ID_ASPECT_RATIO = 119
        const val POPUP_MENU_ID_LIVE_QUALITY = 129

        const val RENDERER_UNAVAILABLE = -1

        val PLAYBACK_SPEEDS = floatArrayOf(
            0.1f, 0.3f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f,
            2.0f, 2.25f, 2.5f, 2.75f, 3.0f, 5.0f, 10.0f
        )
    }

    private val qualityPopupMenu: PopupMenu
    private val playbackSpeedPopupMenu: PopupMenu
    private val captionPopupMenu: PopupMenu
    private val audioTrackPopupMenu: PopupMenu
    private val displayModePopupMenu: PopupMenu

    /** Whether any of the popup menus is currently visible. */
    var isSomePopupMenuVisible = false
        private set

    /** Aspect ratio forced by the user, 0 means "auto" (use the video's own aspect ratio). */
    var forcedAspectRatio = 0.0f
        private set

    /** The video's own aspect ratio, 0 until ExoPlayer reports the first video size. */
    var videoNaturalAspectRatio = 0.0f
        private set

    /**
     * The quality of the item being played, read straight from its metadata. It used to be
     * cached on [Player], which kept showing the previous video's resolutions whenever the
     * current metadata carried no quality at all.
     */
    private val quality: MediaItemQuality?
        get() = player.currentMetadata?.maybeQuality?.orElse(null)

    /** The stream the user selected (or the one the resolver picked), null while unknown. */
    val selectedVideoStream: VideoStream?
        get() = quality?.selectedVideoStream

    init {
        val binding = player.binding
        val themeWrapper = ContextThemeWrapper(player.context, R.style.DarkPopupMenu)
        qualityPopupMenu = PopupMenu(themeWrapper, binding.qualityTextView)
        playbackSpeedPopupMenu = PopupMenu(player.context, binding.playbackSpeed)
        captionPopupMenu = PopupMenu(themeWrapper, binding.captionTextView)
        audioTrackPopupMenu = PopupMenu(themeWrapper, binding.audioTrackTextView)
        displayModePopupMenu = PopupMenu(themeWrapper, binding.resizeTextView)
    }

    //////////////////////////////////////////////////////////////////////////
    // Click entry points
    //////////////////////////////////////////////////////////////////////////

    fun onQualityClicked(v: View) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onQualitySelectorClicked() called")
        }

        // the live menu is rebuilt on open: its entries come from the tracks the manifest
        // exposed, which may have changed since the last time the menu was built
        if (player.liveQualityController.isLivePlayback) {
            buildQualityMenu()
        }
        qualityPopupMenu.show()
        isSomePopupMenuVisible = true

        selectedVideoStream?.let { videoStream ->
            player.binding.qualityTextView.text =
                videoStream.codec.uppercase(Locale.getDefault())
                    .split("\\.".toRegex()).toTypedArray()[0] + " " + videoStream.resolution
        }

        player.saveWasPlaying()
        player.manageControlsAfterOnClick(v)
    }

    fun onPlaybackSpeedClicked(v: View) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onPlaybackSpeedClicked() called")
        }

        if (player.videoPlayerSelected()) {
            PlaybackParameterDialog.newInstance(
                player.playbackSpeed.toDouble(),
                player.playbackPitch.toDouble(),
                player.playbackSkipSilence
            ) { speed: Float, pitch: Float, skipSilence: Boolean ->
                player.setPlaybackParameters(speed, pitch, skipSilence)
            }
                .show(player.parentActivity!!.supportFragmentManager, null)
        } else {
            playbackSpeedPopupMenu.show()
            isSomePopupMenuVisible = true
        }

        player.manageControlsAfterOnClick(v)
    }

    fun onCaptionClicked() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onCaptionClicked() called")
        }
        captionPopupMenu.show()
        isSomePopupMenuVisible = true
    }

    fun onAudioTrackClicked() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onAudioTrackClicked() called")
        }
        audioTrackPopupMenu.show()
        isSomePopupMenuVisible = true
    }

    fun onDisplayModeClicked() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onDisplayModeClicked() called")
        }
        // rebuild on every open so the checkmark reflects the current resize mode / forced ratio
        buildDisplayModeMenu()
        displayModePopupMenu.show()
        isSomePopupMenuVisible = true
    }

    //////////////////////////////////////////////////////////////////////////
    // Menu building
    //////////////////////////////////////////////////////////////////////////

    fun buildQualityMenu() {
        qualityPopupMenu.menu.removeGroup(POPUP_MENU_ID_QUALITY)
        qualityPopupMenu.menu.removeGroup(POPUP_MENU_ID_LIVE_QUALITY)

        val liveController = player.liveQualityController
        if (liveController.isLivePlayback) {
            buildLiveQualityMenu(liveController)
            return
        }

        val availableStreams = quality?.sortedVideoStreams ?: return
        for (i in availableStreams.indices) {
            val videoStream = availableStreams[i]
            qualityPopupMenu.menu.add(
                POPUP_MENU_ID_QUALITY, i, Menu.NONE,
                videoStream.codec.uppercase(Locale.getDefault()).split("\\.")[0]
                    + " " + videoStream.resolution
            )
        }
        selectedVideoStream?.let {
            player.binding.qualityTextView.text = it.resolution
        }
        qualityPopupMenu.setOnMenuItemClickListener(this)
        qualityPopupMenu.setOnDismissListener(this)
    }

    /**
     * Builds the quality menu of a live stream from the variants the player has discovered in
     * the manifest. Item 0 is "Adaptive" (no track override), items 1.. are [LiveQualityController.options].
     */
    private fun buildLiveQualityMenu(liveController: LiveQualityController) {
        val menu = qualityPopupMenu.menu
        for (i in 0..liveController.options.size) {
            val label = liveController.labelFor(i) ?: continue
            val item = menu.add(POPUP_MENU_ID_LIVE_QUALITY, i, i, label)
            item.setOnMenuItemClickListener {
                if (i != liveController.currentMenuIndex) {
                    liveController.select(i)
                }
                true
            }
        }
        qualityPopupMenu.setOnDismissListener(this)
    }

    fun buildPlaybackSpeedMenu() {
        playbackSpeedPopupMenu.menu.removeGroup(POPUP_MENU_ID_PLAYBACK_SPEED)

        for (i in PLAYBACK_SPEEDS.indices) {
            playbackSpeedPopupMenu.menu.add(
                POPUP_MENU_ID_PLAYBACK_SPEED, i, Menu.NONE,
                PlayerHelper.formatSpeed(PLAYBACK_SPEEDS[i].toDouble())
            )
        }
        player.binding.playbackSpeed.text =
            PlayerHelper.formatSpeed(player.playbackSpeed.toDouble())
        playbackSpeedPopupMenu.setOnMenuItemClickListener(this)
        playbackSpeedPopupMenu.setOnDismissListener(this)
    }

    fun buildCaptionMenu(availableLanguages: List<String>) {
        captionPopupMenu.menu.removeGroup(POPUP_MENU_ID_CAPTION)
        captionPopupMenu.setOnDismissListener(this)

        // Add option for turning off caption
        val captionOffItem = captionPopupMenu.menu.add(
            POPUP_MENU_ID_CAPTION, 0, Menu.NONE, R.string.caption_none
        )
        captionOffItem.setOnMenuItemClickListener {
            val textRendererIndex = player.getCaptionRendererIndex()
            if (textRendererIndex != RENDERER_UNAVAILABLE) {
                player.getTrackSelector().setParameters(
                    player.getTrackSelector().buildUponParameters()
                        .setRendererDisabled(textRendererIndex, true)
                )
            }
            player.prefs.edit()
                .remove(player.context.getString(R.string.caption_user_set_key)).apply()
            true
        }

        // Add all available captions
        for (i in availableLanguages.indices) {
            val captionLanguage = availableLanguages[i]
            val captionItem = captionPopupMenu.menu.add(
                POPUP_MENU_ID_CAPTION, i + 1, Menu.NONE, captionLanguage
            )
            captionItem.setOnMenuItemClickListener {
                val textRendererIndex = player.getCaptionRendererIndex()
                if (textRendererIndex != RENDERER_UNAVAILABLE) {
                    // DefaultTrackSelector will select for text tracks in the following order.
                    // When multiple tracks share the same rank, a random track will be chosen.
                    // 1. ANY track exactly matching preferred language name
                    // 2. ANY track exactly matching preferred language stem
                    // 3. ROLE_FLAG_CAPTION track matching preferred language stem
                    // 4. ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND track matching preferred language stem
                    // This means if a caption track of preferred language is not available,
                    // then an auto-generated track of that language will be chosen automatically.
                    player.getTrackSelector().setParameters(
                        player.getTrackSelector().buildUponParameters()
                            .setPreferredTextLanguages(
                                captionLanguage,
                                PlayerHelper.captionLanguageStemOf(captionLanguage)
                            )
                            .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
                            .setRendererDisabled(textRendererIndex, false)
                    )
                    player.prefs.edit().putString(
                        player.context.getString(R.string.caption_user_set_key),
                        captionLanguage
                    ).apply()
                }
                true
            }
        }

        // apply caption language from previous user preference
        val textRendererIndex = player.getCaptionRendererIndex()
        if (textRendererIndex == RENDERER_UNAVAILABLE) {
            return
        }

        // If user prefers to show no caption, then disable the renderer.
        // Otherwise, DefaultTrackSelector may automatically find an available caption
        // and display that.
        val userPreferredLanguage = player.prefs.getString(
            player.context.getString(R.string.caption_user_set_key), null
        )
        if (userPreferredLanguage == null) {
            player.getTrackSelector().setParameters(
                player.getTrackSelector().buildUponParameters()
                    .setRendererDisabled(textRendererIndex, true)
            )
            return
        }

        // Only set preferred language if it does not match the user preference,
        // otherwise there might be an infinite cycle at onTextTracksChanged.
        val selectedPreferredLanguages =
            player.getTrackSelector().parameters.preferredTextLanguages
        if (!selectedPreferredLanguages.contains(userPreferredLanguage)) {
            player.getTrackSelector().setParameters(
                player.getTrackSelector().buildUponParameters()
                    .setPreferredTextLanguages(
                        userPreferredLanguage,
                        PlayerHelper.captionLanguageStemOf(userPreferredLanguage)
                    )
                    .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
                    .setRendererDisabled(textRendererIndex, false)
            )
        }
    }

    fun buildAudioTrackMenu(audioStreams: List<AudioStream>) {
        audioTrackPopupMenu.menu.removeGroup(POPUP_MENU_ID_AUDIO_TRACK)
        audioTrackPopupMenu.setOnDismissListener(this)

        for (i in audioStreams.indices) {
            val audioStream = audioStreams[i]
            val trackName = audioStream.audioTrackName
                ?: audioStream.audioLocale
                ?: "Unknown"
            val audioTrackItem = audioTrackPopupMenu.menu.add(
                POPUP_MENU_ID_AUDIO_TRACK, i, Menu.NONE, trackName
            )
            val trackId = audioStream.audioTrackId
            audioTrackItem.setOnMenuItemClickListener {
                player.setAudioTrack(trackId)
                true
            }
        }
    }

    /**
     * Builds the single display-mode menu that combines the resize modes (Fit / Fill / Zoom) with
     * the forced aspect ratios (1:1 / 4:3 / ... / Custom). Picking a resize mode clears any forced
     * aspect ratio; picking an aspect ratio applies it with the resize mode set to Fit.
     */
    private fun buildDisplayModeMenu() {
        val menu = displayModePopupMenu.menu
        menu.removeGroup(POPUP_MENU_ID_DISPLAY_MODE)
        menu.removeGroup(POPUP_MENU_ID_ASPECT_RATIO)
        // draw a divider between the resize-mode group and the aspect-ratio group
        MenuCompat.setGroupDividerEnabled(menu, true)
        displayModePopupMenu.setOnDismissListener(this)

        // a forced aspect ratio takes precedence: when active, no resize mode is the "current" one
        val pinchActive = PlayerHelper.isPinchToZoomEnabled(player.context)
        val ratioActive = forcedAspectRatio > 0 && !pinchActive
        val currentResizeMode = player.binding.surfaceView.resizeMode
        var activeItem: MenuItem? = null

        var order = 0
        for (resizeMode in intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )) {
            val resizeItem = menu.add(
                POPUP_MENU_ID_DISPLAY_MODE, order, order,
                PlayerHelper.resizeTypeOf(player.context, resizeMode)
            )
            resizeItem.setOnMenuItemClickListener {
                onResizeModeSelected(resizeMode)
                true
            }
            if (!ratioActive && !pinchActive && resizeMode == currentResizeMode) {
                activeItem = resizeItem
            }
            order++
        }

        val pinchItem = menu.add(
            POPUP_MENU_ID_DISPLAY_MODE, order, order,
            R.string.resize_pinch
        )
        pinchItem.setOnMenuItemClickListener {
            onPinchModeSelected()
            true
        }
        if (pinchActive) {
            activeItem = pinchItem
        }
        order++

        for (i in PlayerHelper.ASPECT_RATIO_VALUES.indices) {
            val ratio = PlayerHelper.ASPECT_RATIO_VALUES[i]
            val ratioItem = menu.add(
                POPUP_MENU_ID_ASPECT_RATIO, order, order,
                PlayerHelper.ASPECT_RATIO_LABELS[i]
            )
            ratioItem.setOnMenuItemClickListener {
                applyForcedAspectRatio(ratio)
                true
            }
            if (ratioActive && Math.abs(forcedAspectRatio - ratio) < 0.001f) {
                activeItem = ratioItem
            }
            order++
        }

        val customItem = menu.add(
            POPUP_MENU_ID_ASPECT_RATIO, order, order,
            R.string.aspect_ratio_custom
        )
        customItem.setOnMenuItemClickListener {
            openCustomAspectRatioDialog()
            true
        }
        // a forced ratio that matches none of the presets is a custom value
        if (ratioActive && activeItem == null) {
            activeItem = customItem
        }

        menu.setGroupCheckable(POPUP_MENU_ID_DISPLAY_MODE, true, true)
        menu.setGroupCheckable(POPUP_MENU_ID_ASPECT_RATIO, true, true)
        activeItem?.isChecked = true
    }

    //////////////////////////////////////////////////////////////////////////
    // Item and dismiss handling
    //////////////////////////////////////////////////////////////////////////

    /**
     * Called when an item of the quality selector or the playback speed selector is selected.
     */
    override fun onMenuItemClick(menuItem: MenuItem): Boolean {
        if (MainActivity.DEBUG) {
            Log.d(
                TAG, "onMenuItemClick() called with: "
                    + "menuItem = [" + menuItem + "], "
                    + "menuItem.getItemId = [" + menuItem.itemId + "]"
            )
        }

        if (menuItem.groupId == POPUP_MENU_ID_QUALITY) {
            val menuItemIndex = menuItem.itemId
            val selectedQuality = quality ?: return true
            val availableStreams = selectedQuality.sortedVideoStreams
            if (selectedQuality.selectedVideoStreamIndex == menuItemIndex
                || availableStreams.size <= menuItemIndex
            ) {
                return true
            }

            player.saveStreamProgressState() //TODO added, check if good
            player.setRecovery()
            player.setSelectedStream(availableStreams[menuItemIndex])
            player.reloadPlayQueueManager()

            player.binding.qualityTextView.text = menuItem.title
            return true
        } else if (menuItem.groupId == POPUP_MENU_ID_PLAYBACK_SPEED) {
            val speedIndex = menuItem.itemId
            val speed = PLAYBACK_SPEEDS[speedIndex]

            player.setPlaybackSpeed(speed)
            player.binding.playbackSpeed.text = PlayerHelper.formatSpeed(speed.toDouble())
        }

        return false
    }

    /**
     * Called when some popup menu is dismissed.
     */
    override fun onDismiss(menu: PopupMenu?) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onDismiss() called with: menu = [$menu]")
        }
        isSomePopupMenuVisible = false
        selectedVideoStream?.let {
            player.binding.qualityTextView.text = it.resolution
        }
        if (player.isPlaying) {
            player.hideControls(Player.DEFAULT_CONTROLS_DURATION.toLong(), 0L)
            player.hideSystemUIIfNeeded()
        }
    }

    fun closeAllPopupMenus() {
        qualityPopupMenu.dismiss()
        playbackSpeedPopupMenu.dismiss()
        captionPopupMenu.dismiss()
        displayModePopupMenu.dismiss()
        isSomePopupMenuVisible = false
    }

    //////////////////////////////////////////////////////////////////////////
    // Display mode state and actions
    //////////////////////////////////////////////////////////////////////////

    fun setResizeMode(resizeMode: @AspectRatioFrameLayout.ResizeMode Int) {
        player.binding.surfaceView.setResizeMode(resizeMode)
        updateDisplayModeButtonText()
    }

    /**
     * Updates the display-mode button label: the forced aspect ratio takes precedence over the
     * resize mode, since selecting an aspect ratio is what the user sees applied.
     */
    fun updateDisplayModeButtonText() {
        val text = when {
            PlayerHelper.isPinchToZoomEnabled(player.context) ->
                player.context.getString(R.string.resize_pinch)
            forcedAspectRatio > 0 ->
                PlayerHelper.aspectRatioNameOf(forcedAspectRatio)
            else ->
                PlayerHelper.resizeTypeOf(player.context, player.binding.surfaceView.resizeMode)
        }
        player.binding.resizeTextView.setText(text)
    }

    /**
     * Resets the display-mode state for a newly loaded video. A pinch zoom or a forced aspect
     * ratio is a per-video adjustment and must not leak into the next stream; a forced ratio also
     * temporarily forced the resize mode to Fit, so the persisted resize mode is restored.
     */
    fun resetDisplayModeForNewVideo() {
        if (PlayerHelper.isPinchToZoomEnabled(player.context)) {
            forcedAspectRatio = 0.0f
            setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        }
        if (forcedAspectRatio > 0f) {
            forcedAspectRatio = 0.0f
            setResizeMode(PlayerHelper.retrieveResizeModeFromPrefs(player))
        }
    }

    /** The video's own ratio supersedes a forced one when a pinch gesture starts. */
    fun onPinchZoomStart() {
        forcedAspectRatio = 0.0f
        if (videoNaturalAspectRatio > 0.0f) {
            player.binding.surfaceView.setAspectRatio(videoNaturalAspectRatio)
        }
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    }

    /** Records the video's own aspect ratio and re-applies the effective one to the surface. */
    fun onVideoSizeChanged(width: Int, height: Int) {
        videoNaturalAspectRatio = width.toFloat() / height
        player.binding.surfaceView.setAspectRatio(
            if (forcedAspectRatio > 0f) forcedAspectRatio else videoNaturalAspectRatio
        )
    }

    private fun onResizeModeSelected(resizeMode: Int) {
        PlayerHelper.setPinchToZoomEnabled(player.context, false)
        player.gestureController.resetPinchZoom()
        // a resize mode supersedes any forced aspect ratio, which would otherwise have no effect
        forcedAspectRatio = 0.0f
        if (videoNaturalAspectRatio > 0) {
            player.binding.surfaceView.setAspectRatio(videoNaturalAspectRatio)
        }
        setResizeMode(resizeMode)
        PlayerHelper.saveResizeMode(player, resizeMode)
    }

    private fun applyForcedAspectRatio(aspectRatio: Float) {
        PlayerHelper.setPinchToZoomEnabled(player.context, false)
        player.gestureController.resetPinchZoom()
        forcedAspectRatio = aspectRatio
        // a forced aspect ratio is only meaningful with Fit; this resize mode change is per-video
        // and is intentionally not persisted, so the saved resize mode is restored on the next video
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)

        val effectiveRatio = if (aspectRatio > 0) {
            aspectRatio
        } else {
            videoNaturalAspectRatio
        }
        if (effectiveRatio > 0) {
            player.binding.surfaceView.setAspectRatio(effectiveRatio)
        }
    }

    private fun onPinchModeSelected() {
        forcedAspectRatio = 0.0f
        PlayerHelper.setPinchToZoomEnabled(player.context, true)
        if (videoNaturalAspectRatio > 0.0f) {
            player.binding.surfaceView.setAspectRatio(videoNaturalAspectRatio)
        }
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        player.gestureController.resetPinchZoom()
        updateDisplayModeButtonText()
        Toast.makeText(player.context, R.string.pinch_to_zoom_selected, Toast.LENGTH_SHORT).show()
    }

    private fun openCustomAspectRatioDialog() {
        val activity = player.parentActivity ?: return
        val input = EditText(activity)
        input.setHint(R.string.aspect_ratio_custom_hint)
        input.inputType = InputType.TYPE_CLASS_TEXT
        if (forcedAspectRatio > 0) {
            input.setText(PlayerHelper.aspectRatioNameOf(forcedAspectRatio))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.aspect_ratio_custom_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val ratio = PlayerHelper.parseAspectRatio(input.text.toString())
                if (ratio > 0) {
                    applyForcedAspectRatio(ratio)
                } else {
                    Toast.makeText(
                        player.context, R.string.aspect_ratio_invalid, Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
