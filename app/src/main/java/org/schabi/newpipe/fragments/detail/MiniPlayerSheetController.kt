package org.schabi.newpipe.fragments.detail

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.fragments.list.comments.CommentReplyFragment
import org.schabi.newpipe.fragments.list.comments.CommentsFragment
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.PicassoHelper

/**
 * The bottom sheet holding the detail page, and the mini player bar shown on top of the page
 * while the sheet is collapsed.
 *
 * The sheet itself belongs to the activity: this class only exists while the page has views and
 * removes all of its callbacks in [release].
 */
class MiniPlayerSheetController(
    private val fragment: Fragment,
    private val binding: FragmentVideoDetailBinding,
    private val playerView: DetailPlayerViewHost,
    private val host: Host,
) {
    interface Host {
        val player: Player?
        val currentInfo: StreamInfo?

        /** The last stable state of the sheet, saved by the detail page across recreations. */
        var savedSheetState: Int

        /** The page was swiped away. */
        fun onSheetHidden()

        fun onPlayPauseClicked()

        fun openUploaderChannel()
    }

    private val activity get() = fragment.requireActivity()
    private val overlay get() = binding.overlayLayout
    private val peekHeight =
        fragment.resources.getDimensionPixelSize(R.dimen.mini_player_height)
    private val behavior: BottomSheetBehavior<FrameLayout> =
        BottomSheetBehavior.from(activity.findViewById(R.id.fragment_player_holder))
    private var pendingLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val sheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onStateChanged(bottomSheet: View, newState: Int) = onSheetStateChanged(newState)

        override fun onSlide(bottomSheet: View, slideOffset: Float) =
            setOverlayLook(slideOffset)
    }

    /** Collapses the page when the user opens another page from the detail page. */
    private val backStackListener = FragmentManager.OnBackStackChangedListener {
        val top = activity.supportFragmentManager.fragments.lastOrNull()
            ?: return@OnBackStackChangedListener
        // comments and replies are opened inside the detail page
        if (top !is CommentsFragment && top !is CommentReplyFragment
            && behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    val state: Int get() = behavior.state

    init {
        val restoredState = sanitizeSheetState(host.savedSheetState)
        host.savedSheetState = restoredState
        // The player may already be in fullscreen when this view is (re)created
        // (e.g. after a rotation while playing fullscreen): keep the drag disabled in that case.
        updateDraggableForFullscreen()
        if (restoredState != BottomSheetBehavior.STATE_HIDDEN) {
            manageSpaceAtTheBottom(false)
            // Set the peek height before the state: the collapsed offset is computed from it.
            behavior.peekHeight = peekHeight
        }
        behavior.state = restoredState
        applyOverlayForState(restoredState)
        syncStateAfterLayout(restoredState)

        behavior.addBottomSheetCallback(sheetCallback)
        activity.supportFragmentManager.addOnBackStackChangedListener(backStackListener)
        setupOverlayClicks()
    }

    fun expand() {
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun hide() {
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    /**
     * While the player is in fullscreen the sheet drag (which collapses the whole page into the
     * mini player) must never start: a vertical swipe in fullscreen belongs to the player's own
     * gestures. The exception is swipe-down-to-minimize in fullscreen, which is exactly that drag.
     */
    fun updateDraggableForFullscreen() {
        val inFullscreen = host.player?.isFullscreen == true
        val minimizeBySheet = inFullscreen && PlayerHelper.getMinimizeGestureMode(activity) ==
            PlayerHelper.MinimizeGestureMode.MINIMIZE_GESTURE_FULLSCREEN_AND_NON_FULLSCREEN
        behavior.isDraggable = !inFullscreen || minimizeBySheet
    }

    fun updateOverlayData(title: String?, uploader: String?, thumbnailUrl: String?) {
        overlay.overlayTitleTextView.text = title.orEmpty()
        overlay.overlayChannelTextView.text = uploader.orEmpty()
        overlay.overlayThumbnail.setImageResource(R.drawable.dummy_thumbnail_dark)
        PicassoHelper.loadScaledDownThumbnail(fragment.context, thumbnailUrl)
            .tag(PICASSO_VIDEO_DETAILS_TAG)
            .into(overlay.overlayThumbnail)
    }

    fun setPlayPauseImage(playing: Boolean) {
        overlay.overlayPlayPauseButton.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    }

    /**
     * For Android TV: moves the focus to the main fragment, or to the player otherwise.
     */
    fun moveFocusToMainFragment(toMain: Boolean) {
        playerView.setupBrightness()
        val mainFragment = activity.findViewById<ViewGroup>(R.id.fragment_holder)
        // Hamburger button steals the focus even under the bottom sheet
        val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
        val page = fragment.requireView() as ViewGroup
        if (toMain) {
            mainFragment.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            toolbar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // Only focus the main fragment if neither it (e.g. search results) nor the toolbar
            // (e.g. the search field) has the focus, see #7490
            if (!mainFragment.hasFocus() && !toolbar.hasFocus()) {
                mainFragment.requestFocus()
            }
        } else {
            mainFragment.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            toolbar.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            if (!binding.root.hasFocus()) {
                binding.detailThumbnailRootLayout.root.requestFocus()
            }
        }
    }

    fun release() {
        behavior.removeBottomSheetCallback(sheetCallback)
        activity.supportFragmentManager.removeOnBackStackChangedListener(backStackListener)
        pendingLayoutListener?.let {
            binding.root.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
        pendingLayoutListener = null
    }

    private fun onSheetStateChanged(newState: Int) {
        host.savedSheetState = newState
        val player = host.player
        when (newState) {
            BottomSheetBehavior.STATE_HIDDEN -> {
                moveFocusToMainFragment(true)
                manageSpaceAtTheBottom(true)
                behavior.peekHeight = 0
                host.onSheetHidden()
                applyOverlayForState(newState)
            }
            BottomSheetBehavior.STATE_EXPANDED -> {
                moveFocusToMainFragment(false)
                manageSpaceAtTheBottom(false)
                behavior.peekHeight = peekHeight
                // The overlay buttons are on top of the player buttons: disable them
                applyOverlayForState(newState)
                playerView.hideSystemUiIfNeeded()
                if (player != null && player.isPlaying && player.videoPlayerSelected()) {
                    player.startBCPlayer()
                }
            }
            BottomSheetBehavior.STATE_COLLAPSED -> {
                moveFocusToMainFragment(true)
                manageSpaceAtTheBottom(false)
                behavior.peekHeight = peekHeight
                applyOverlayForState(newState)
                if (player != null) {
                    if (player.isFullscreen) {
                        // Minimized by swiping down inside fullscreen: the player view must leave
                        // fullscreen too, otherwise the full-size layout is squeezed into the
                        // mini player. Keep the orientation: rotating back is unwanted.
                        player.exitFullscreenLayoutOnly()
                    }
                    player.closeItemsList()
                    player.pauseBCPlayer()
                }
            }
            BottomSheetBehavior.STATE_DRAGGING, BottomSheetBehavior.STATE_SETTLING -> {
                overlay.root.visibility = View.VISIBLE
                if (player?.isFullscreen == true) {
                    playerView.showSystemUi()
                }
                if (player?.isControlsVisible == true) {
                    player.hideControls(0, 0)
                }
            }
        }
    }

    private fun setupOverlayClicks() {
        val expand = View.OnClickListener { expand() }
        overlay.overlayThumbnail.setOnClickListener(expand)
        overlay.overlayMetadataLayout.setOnClickListener(expand)
        overlay.overlayButtonsLayout.setOnClickListener(expand)
        val openChannel = View.OnLongClickListener {
            if (host.currentInfo == null) {
                return@OnLongClickListener false
            }
            host.openUploaderChannel()
            true
        }
        overlay.overlayThumbnail.setOnLongClickListener(openChannel)
        overlay.overlayMetadataLayout.setOnLongClickListener(openChannel)
        overlay.overlayPlayPauseButton.setOnClickListener { host.onPlayPauseClicked() }
        overlay.overlayCloseButton.setOnClickListener { hide() }
        overlay.overlayPlayQueueButton.setOnClickListener {
            if (host.player != null) {
                activity.startActivity(NavigationHelper.getPlayQueueActivityIntent(activity))
            }
        }
    }

    /**
     * While the mini player exists the view underneath it is not touchable, so the main
     * fragment gets a bottom padding as high as the mini player.
     */
    private fun manageSpaceAtTheBottom(showMore: Boolean) {
        val holder = activity.findViewById<ViewGroup>(R.id.fragment_holder)
        val newBottomPadding = if (showMore) 0 else peekHeight
        if (holder.paddingBottom != newBottomPadding) {
            holder.setPadding(holder.paddingLeft, holder.paddingTop, holder.paddingRight,
                newBottomPadding)
        }
    }

    private fun appBarBehavior(): AppBarLayout.Behavior? =
        (binding.appBarLayout.layoutParams as CoordinatorLayout.LayoutParams).behavior
            as? AppBarLayout.Behavior

    private fun setOverlayLook(slideOffset: Float) {
        val appBarBehavior = appBarBehavior()
        // slideOffset < 0 when the mini player is about to be closed by a swipe
        if (appBarBehavior == null || slideOffset < 0) {
            return
        }
        if (slideOffset < 1) {
            overlay.root.visibility = View.VISIBLE
        }
        overlay.root.alpha = minOf(MAX_OVERLAY_ALPHA, 1 - slideOffset)
        // These numbers are not special. They just do a cool transition
        appBarBehavior.topAndBottomOffset =
            (-binding.detailThumbnailRootLayout.detailThumbnailImageView.height
                    * 2 * (1 - slideOffset) / 3).toInt()
        binding.appBarLayout.requestLayout()
        if (slideOffset >= 1) {
            overlay.root.visibility = View.INVISIBLE
        }
    }

    /**
     * Shows or hides the mini player bar to match [state]. This must not rely on
     * [BottomSheetBehavior.BottomSheetCallback.onStateChanged], since the behavior restores its
     * state without calling it.
     */
    private fun applyOverlayForState(state: Int) {
        if (state == BottomSheetBehavior.STATE_COLLAPSED) {
            overlay.root.visibility = View.VISIBLE
            overlay.root.alpha = MAX_OVERLAY_ALPHA
            setOverlayElementsClickable(true)
            setOverlayLook(0f)
        } else {
            // The translucent bar must not stay over the page in the other states
            overlay.root.alpha = 0f
            overlay.root.visibility = View.INVISIBLE
            setOverlayElementsClickable(false)
            setOverlayLook(1f)
        }
    }

    /**
     * The behavior restores its own state silently when the activity's views are restored, which
     * can happen after this class was set up, leaving the bar visible over an expanded page.
     * Re-apply the saved state once the views have been laid out.
     */
    private fun syncStateAfterLayout(state: Int) {
        val observer = binding.root.viewTreeObserver
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                pendingLayoutListener = null
                host.savedSheetState = state
                if (state != BottomSheetBehavior.STATE_HIDDEN) {
                    manageSpaceAtTheBottom(false)
                    behavior.peekHeight = peekHeight
                }
                // Force the saved state: the behavior may already claim to be in that state while
                // its view is still laid out at the opposite offset.
                behavior.state = state
                applyOverlayForState(state)
            }
        }
        pendingLayoutListener = listener
        observer.addOnGlobalLayoutListener(listener)
    }

    private fun setOverlayElementsClickable(enable: Boolean) {
        overlay.root.descendantFocusability =
            if (enable) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay.root.importantForAccessibility =
            if (enable) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        overlay.overlayThumbnail.isClickable = enable
        overlay.overlayThumbnail.isLongClickable = enable
        overlay.overlayMetadataLayout.isClickable = enable
        overlay.overlayMetadataLayout.isLongClickable = enable
        overlay.overlayButtonsLayout.isClickable = enable
        overlay.overlayPlayPauseButton.isClickable = enable
        overlay.overlayCloseButton.isClickable = enable
        overlay.overlayPlayQueueButton.isClickable = enable
    }

    companion object {
        private const val MAX_OVERLAY_ALPHA = 0.9f

        /** Settling or dragging states can't be restored: the target state is unknown. */
        @JvmStatic
        fun sanitizeSheetState(state: Int): Int = when (state) {
            BottomSheetBehavior.STATE_COLLAPSED, BottomSheetBehavior.STATE_EXPANDED,
            BottomSheetBehavior.STATE_HALF_EXPANDED, BottomSheetBehavior.STATE_HIDDEN -> state
            else -> BottomSheetBehavior.STATE_COLLAPSED
        }
    }
}
