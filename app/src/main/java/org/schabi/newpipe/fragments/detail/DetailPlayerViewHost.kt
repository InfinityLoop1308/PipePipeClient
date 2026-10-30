package org.schabi.newpipe.fragments.detail

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.mediasession.PlayerServiceInterface
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.ThemeHelper

/**
 * Places the main player inside the detail page: in the thumbnail, pinned to the top or in
 * fullscreen. Also owns the window state tied to fullscreen (system bars and brightness).
 */
class DetailPlayerViewHost(
    private val fragment: Fragment,
    private val binding: FragmentVideoDetailBinding,
    private val host: Host,
) {
    interface Host {
        val player: Player?
        val playerService: PlayerServiceInterface?
        val currentInfo: StreamInfo?

        /** The last stable state of the bottom sheet, as saved by the detail page. */
        val savedSheetState: Int

        /** The current state of the bottom sheet. */
        val sheetState: Int
    }

    private val activity: AppCompatActivity get() = fragment.requireActivity() as AppCompatActivity
    private val thumbnail get() = binding.detailThumbnailRootLayout
    private var stickyPlayerEnabled = false

    private val preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            fragment.view?.let { view ->
                setThumbnailHeight(windowHeight(), view.resources.displayMetrics)
                view.viewTreeObserver.removeOnPreDrawListener(this)
            }
            return false
        }
    }

    fun addVideoPlayerView() {
        val player = host.player ?: return
        val playerService = host.playerService ?: return
        if (fragment.view == null) {
            return
        }
        // The player view must stay in the same parent when entering or leaving fullscreen:
        // moving it destroys and recreates its video surface, which makes the video stay black
        // or buffer forever on devices with a slow decoder (TV boxes, Fire TV).
        val fullscreen = player.isFullscreen
        val target: ViewGroup = thumbnail.playerPlaceholder

        if (player.rootView.parent !== target) {
            playerService.removeViewFromParent()
        }
        updateThumbnailHeight()

        // Prevent from re-adding a view multiple times
        if (player.rootView.parent == null) {
            target.addView(player.rootView)
            host.currentInfo?.let(player::onMarkSeekbarRequested)
        }

        setPageScrollable(!fullscreen)
        // Keep the DPAD focus from wandering into the hidden detail page
        (binding.detailMainContent as ViewGroup).descendantFocusability =
            if (fullscreen) ViewGroup.FOCUS_BLOCK_DESCENDANTS
            else ViewGroup.FOCUS_AFTER_DESCENDANTS

        if (fullscreen && DeviceUtils.isTv(fragment.context)) {
            // The focus is still on the detail page's thumbnail (drawn as a white frame) or on
            // the overlay button, both underneath the fullscreen player. Hand it to the player.
            thumbnail.root.clearFocus()
            player.rootView.post {
                val current = host.player
                if (current != null && current.isFullscreen) {
                    current.binding.playPauseButton.requestFocus()
                }
            }
        }
    }

    /**
     * The fullscreen player fills the screen in the scrolling app bar. Lock the app bar so the
     * page below cannot be scrolled into view while in fullscreen.
     */
    private fun setPageScrollable(scrollable: Boolean) {
        val params = (binding.detailThumbnailContainer.parent as? View)?.layoutParams
            as? AppBarLayout.LayoutParams ?: return
        val flags = if (scrollable) AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL else 0
        if (params.scrollFlags != flags) {
            if (!scrollable) {
                binding.appBarLayout.setExpanded(true, false)
            }
            params.scrollFlags = flags
            binding.appBarLayout.requestLayout()
        }
    }

    fun removeVideoPlayerView() {
        if (fragment.view != null) {
            thumbnail.playerPlaceholder.layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            thumbnail.playerPlaceholder.requestLayout()
        }
        host.playerService?.removeViewFromParent()
    }

    /**
     * Sets the height of the thumbnail and of the player in it: the whole screen in fullscreen,
     * otherwise 16:9 in portrait and half of the screen in landscape. Zoomed videos may take up
     * to [MAX_PLAYER_HEIGHT] of the screen.
     */
    fun updateThumbnailHeight() {
        val view = fragment.view ?: return
        val metrics = view.resources.displayMetrics
        view.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        if (host.player?.isFullscreen == true) {
            val height = windowHeight()
            // Height is zero when the view is not yet displayed like after orientation change
            if (height != 0) {
                setThumbnailHeight(height, metrics)
            } else {
                view.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            }
        } else {
            val isPortrait = metrics.heightPixels > metrics.widthPixels
            val height = if (isPortrait) metrics.widthPixels / (16f / 9f)
            else metrics.heightPixels / 2f
            setThumbnailHeight(height.toInt(), metrics)
        }
    }

    /** Pins the player to the top of the page, unless disabled or in landscape. */
    fun updateStickyPlayerMode() {
        val context = fragment.requireContext()
        stickyPlayerEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.pin_video_to_top_key), true)
            && !DeviceUtils.isLandscape(context)
        moveThumbnailTo(
            if (stickyPlayerEnabled) binding.stickyPlayerContainer
            else binding.detailThumbnailContainer)
        binding.stickyPlayerContainer.visibility =
            if (stickyPlayerEnabled) View.VISIBLE else View.GONE
        updateStickyPlayerLayout(thumbnail.root.height)
    }

    fun showSystemUi() {
        val window = fragment.activity?.window ?: return
        // Prevent jumping of the player on devices with cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = ThemeHelper.resolveColorFromAttr(
            fragment.requireContext(), android.R.attr.colorPrimary)
    }

    fun hideSystemUiIfNeeded() {
        if (host.player?.isFullscreen == true
            && host.sheetState == BottomSheetBehavior.STATE_EXPANDED) {
            hideSystemUi()
        }
    }

    /** Applies the brightness of the brightness gesture, only kept in fullscreen. */
    fun setupBrightness() {
        val activity = fragment.activity ?: return
        val player = host.player
        if (player == null || !player.videoPlayerSelected() || !player.isFullscreen
            || host.savedSheetState != BottomSheetBehavior.STATE_EXPANDED) {
            restoreDefaultBrightness()
            return
        }
        if (!PlayerHelper.isBrightnessGestureEnabled(activity)) {
            return
        }
        val lp = activity.window.attributes
        val brightness = PlayerHelper.getScreenBrightness(activity)
        if (brightness != lp.screenBrightness) {
            lp.screenBrightness = brightness
            activity.window.attributes = lp
        }
    }

    fun restoreDefaultBrightness() {
        val window = fragment.activity?.window ?: return
        val lp = window.attributes
        if (lp.screenBrightness != -1f) {
            lp.screenBrightness = -1f
            window.attributes = lp
        }
    }

    /** Scrolls the player out of the way, down to the description. */
    fun scrollPastPlayer() {
        val params = binding.appBarLayout.layoutParams as CoordinatorLayout.LayoutParams
        val behavior = params.behavior as? AppBarLayout.Behavior ?: return
        ValueAnimator.ofInt(0, -thumbnail.playerPlaceholder.height).apply {
            interpolator = DecelerateInterpolator()
            duration = 500
            addUpdateListener {
                behavior.topAndBottomOffset = it.animatedValue as Int
                binding.appBarLayout.requestLayout()
            }
            start()
        }
    }

    fun release() {
        fragment.view?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
    }

    private fun hideSystemUi() {
        val activity = fragment.activity as? AppCompatActivity ?: return
        val window = activity.window
        // Prevent jumping of the player on devices with cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        @Suppress("DEPRECATION")
        var visibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        // In multiWindow mode status bar is not transparent for devices with cutout
        // if this flag is included. So without it is better in this case
        val isInMultiWindow = DeviceUtils.isInMultiWindow(activity)
        if (!isInMultiWindow) {
            @Suppress("DEPRECATION")
            visibility = visibility or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = visibility
        if (isInMultiWindow || host.player?.isFullscreen == true) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    private fun windowHeight(): Int =
        (if (DeviceUtils.isInMultiWindow(activity)) fragment.requireView()
        else activity.window.decorView).height

    private fun setThumbnailHeight(newHeight: Int, metrics: DisplayMetrics) {
        thumbnail.detailThumbnailImageView.layoutParams =
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, newHeight)
        thumbnail.detailThumbnailImageView.minimumHeight = newHeight
        updateStickyPlayerLayout(newHeight)
        host.player?.let {
            val maxHeight = (metrics.heightPixels * MAX_PLAYER_HEIGHT).toInt()
            it.surfaceView.setHeights(newHeight, if (it.isFullscreen) newHeight else maxHeight)
        }
    }

    private fun moveThumbnailTo(targetParent: ViewGroup) {
        val root = thumbnail.root
        val currentParent = root.parent as ViewGroup?
        if (currentParent === targetParent) {
            return
        }
        currentParent?.removeView(root)
        targetParent.addView(root)
    }

    /** The pinned player covers the top of the page, so the page starts below it. */
    private fun updateStickyPlayerLayout(playerHeight: Int) {
        val height = maxOf(playerHeight, thumbnail.detailThumbnailImageView.minimumHeight)
        binding.stickyPlayerContainer.layoutParams?.let {
            val stickyHeight = if (stickyPlayerEnabled) height else 0
            if (it.height != stickyHeight) {
                it.height = stickyHeight
                binding.stickyPlayerContainer.layoutParams = it
            }
        }
        val mainContentParams = binding.detailMainContent.layoutParams
        // only on phones the page is drawn below the sticky container, the tablet layout has the
        // page in a row next to the related items
        val topMargin = if (stickyPlayerEnabled && mainContentParams is FrameLayout.LayoutParams)
            height else 0
        if (mainContentParams is ViewGroup.MarginLayoutParams
            && mainContentParams.topMargin != topMargin) {
            mainContentParams.topMargin = topMargin
            binding.detailMainContent.layoutParams = mainContentParams
        }
    }

    private companion object {
        const val MAX_PLAYER_HEIGHT = 0.7f
    }
}
