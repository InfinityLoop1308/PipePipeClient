package org.schabi.newpipe.fragments.detail

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ReCaptchaActivity
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.fragments.BackPressable
import org.schabi.newpipe.fragments.BaseStateFragment
import org.schabi.newpipe.fragments.detail.tabs.DetailTabsController
import org.schabi.newpipe.fragments.list.sponsorblock.SponsorBlockFragment
import org.schabi.newpipe.fragments.list.sponsorblock.SponsorBlockFragmentListener
import org.schabi.newpipe.player.PlaybackStartupTrace
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.PlayerError
import org.schabi.newpipe.player.PlayerPlaybackParameters
import org.schabi.newpipe.player.PlayerPlaybackState
import org.schabi.newpipe.player.PlayerService.PlayerType
import org.schabi.newpipe.player.PlayerUiModeController
import org.schabi.newpipe.player.RepeatMode
import org.schabi.newpipe.player.event.DetailPlayerEvents
import org.schabi.newpipe.player.event.OnKeyDownListener
import org.schabi.newpipe.player.event.PlayerServiceExtendedEventListener
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.mediasession.PlayerServiceInterface
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NO_SERVICE_ID
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.PermissionHelper

/**
 * The video detail page, living in the bottom sheet of the main activity, and the host of the
 * main player. The parts of the page are separate classes; this class wires them together,
 * loads streams and reacts to the player.
 */
class VideoDetailFragment : BaseStateFragment<StreamInfo>(), BackPressable,
    PlayerServiceExtendedEventListener, OnKeyDownListener {

    companion object {
        const val KEY_SWITCHING_PLAYERS = "switching_players"

        private const val STATE_SERVICE_ID = "serviceId"
        private const val STATE_TITLE = "title"
        private const val STATE_URL = "url"
        private const val STATE_SHEET = "bottomSheetState"
        private const val STATE_AUTO_PLAY = "autoPlayEnabled"

        /** Delay of redrawing the page for a stream started by the player, see onMetadataUpdate. */
        private const val PLAYER_STREAM_RENDER_DELAY = 200L

        private fun isDpadKey(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER -> true
            else -> false
        }

        @JvmStatic
        fun getInstance(serviceId: Int, videoUrl: String?, name: String,
                        queue: PlayQueue?): VideoDetailFragment =
            VideoDetailFragment().apply { setInitialData(serviceId, videoUrl, name, queue) }

        @JvmStatic
        fun getInstanceInCollapsedState(): VideoDetailFragment =
            VideoDetailFragment().apply { sheetStateToSave = BottomSheetBehavior.STATE_COLLAPSED }
    }

    private class InitialData(val serviceId: Int, val url: String?, val title: String,
                              val queue: PlayQueue?)

    /** Data set before the view model exists (or restored from the saved state). */
    private var pendingInitialData: InitialData? = null
    private lateinit var vm: VideoDetailViewModel

    private var autoPlayEnabled = true

    /** The last stable state of the bottom sheet, restored when the views are recreated. */
    private var sheetStateToSave = BottomSheetBehavior.STATE_EXPANDED
    private var pendingStartupTraceId = 0L

    private var player: Player? = null
    private var playerService: PlayerServiceInterface? = null
    private val playerHolder = PlayerHolder.getInstance()

    private var _binding: FragmentVideoDetailBinding? = null
    private val binding get() = _binding!!
    private var playerView: DetailPlayerViewHost? = null
    private var header: DetailHeaderBinder? = null
    private var actions: DetailActions? = null
    private var tabs: DetailTabsController? = null
    private var sheet: MiniPlayerSheetController? = null

    private val handler = Handler(Looper.getMainLooper())
    private val detailEventListener = DetailPlayerEvents.Listener(::onDetailEvent)
    private val rotationObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (!isAdded) {
                return
            }
            val activity = requireActivity()
            if (!PlayerHelper.globalScreenOrientationLocked(activity)) {
                PlayerUiModeController.setOrientation(activity,
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            }
        }
    }

    // Public API

    fun selectAndLoadVideo(newServiceId: Int, newUrl: String?, newTitle: String,
                           newQueue: PlayQueue?) {
        val currentItem = vm.playQueue?.item
        if (newQueue != null && currentItem != null && currentItem.url != newUrl) {
            // Preloading can be disabled since playback is surely being replaced.
            player?.disablePreloadingOfCurrentTrack()
        }
        setInitialData(newServiceId, newUrl, newTitle, newQueue)
        load(forceLoad = false, addToBackStack = true)
    }

    fun setAutoPlay(autoPlay: Boolean) {
        autoPlayEnabled = autoPlay
    }

    /**
     * Opens the main player, asking for fullscreen if [directlyFullscreenIfApplicable].
     */
    fun openVideoPlayer(directlyFullscreenIfApplicable: Boolean) {
        if (directlyFullscreenIfApplicable) {
            // The bottom sheet may not be fully expanded yet (STATE_SETTLING). If the activity
            // is recreated now, the sheet would forget where it was settling to and collapse:
            // make sure the state restored is the expanded one.
            sheetStateToSave = BottomSheetBehavior.STATE_EXPANDED
            // Without a connected player onServiceConnected() issues the same request once the
            // service started for this playback connects.
            player?.changeFullscreen(true)
        }
        openMainPlayer()
    }

    fun openVideoPlayerAutoFullscreen() {
        openVideoPlayer(PlayerHelper.isStartMainPlayerFullscreenEnabled(requireContext()))
    }

    fun scrollToTop() {
        val binding = _binding ?: return
        binding.appBarLayout.setExpanded(true, true)
        tabs?.updateTabLayoutVisibility()
    }

    fun openDownloadDialog() {
        actions?.openDownloadDialog()
    }

    private fun setInitialData(newServiceId: Int, newUrl: String?, newTitle: String,
                               newQueue: PlayQueue?) {
        if (::vm.isInitialized) {
            vm.setInitialData(newServiceId, newUrl, newTitle, newQueue)
        } else {
            pendingInitialData = InitialData(newServiceId, newUrl, newTitle, newQueue)
        }
    }

    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[VideoDetailViewModel::class.java]
        val initialData = pendingInitialData
        if (!vm.initialized && initialData != null) {
            vm.setInitialData(initialData.serviceId, initialData.url, initialData.title,
                initialData.queue)
        }
        pendingInitialData = null
        DetailPlayerEvents.addListener(detailEventListener)
        requireContext().contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false,
            rotationObserver)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentVideoDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        val playerView = DetailPlayerViewHost(this, binding, hosts)
        this.playerView = playerView
        val header = DetailHeaderBinder(this, binding,
            onHeightChanged = { tabs?.updateTabLayoutVisibility() },
            onError = ::showSnackBarError)
        this.header = header
        actions = DetailActions(this, binding, header, hosts).also { it.setup() }
        tabs = DetailTabsController(this, binding.viewPager, binding.tabLayout,
            binding.relatedItemsLayout, vm.tabsState, hosts)
        playerView.updateStickyPlayerMode()
        binding.detailThumbnailRootLayout.root.requestFocus()
        vm.results.observe(viewLifecycleOwner) { it.consume()?.let(::onLoadResult) }
    }

    override fun initListeners() {
        super.initListeners()
        var lastAppBarOffset = Int.MAX_VALUE
        binding.appBarLayout.addOnOffsetChangedListener { _, offset ->
            // the page was scrolled: move the tab layout
            if (offset != lastAppBarOffset) {
                lastAppBarOffset = offset
                tabs?.updateTabLayoutVisibility()
            }
        }
        sheet = MiniPlayerSheetController(this, binding, playerView!!, hosts)
        if (playerHolder.isBound) {
            playerHolder.startService(false, this)
        } else {
            playerView?.updateThumbnailHeight()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SERVICE_ID, vm.serviceId)
        outState.putString(STATE_TITLE, vm.title)
        outState.putString(STATE_URL, vm.url)
        outState.putInt(STATE_SHEET, MiniPlayerSheetController.sanitizeSheetState(sheetStateToSave))
        outState.putBoolean(STATE_AUTO_PLAY, autoPlayEnabled)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        pendingInitialData = InitialData(
            savedInstanceState.getInt(STATE_SERVICE_ID, NO_SERVICE_ID),
            savedInstanceState.getString(STATE_URL),
            savedInstanceState.getString(STATE_TITLE, ""),
            null)
        sheetStateToSave = MiniPlayerSheetController.sanitizeSheetState(
            savedInstanceState.getInt(STATE_SHEET, BottomSheetBehavior.STATE_EXPANDED))
        autoPlayEnabled = savedInstanceState.getBoolean(STATE_AUTO_PLAY, true)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // The setExpanded() done by scrollToTop() only schedules the expand for the next layout,
        // which the configuration change caused by entering fullscreen cancels. The player
        // service keeps isFullscreen, so the fullscreen event is not re-emitted to this new
        // fragment and the restored collapsed offset would leave the player off-screen.
        if (player?.isFullscreen == true) {
            _binding?.appBarLayout?.setExpanded(true, false)
        }
        tabs?.onViewStateRestored()
    }

    override fun onResume() {
        super.onResume()
        DetailPlayerEvents.detailResumed()
        playerView?.setupBrightness()
        tabs?.onResume()
        playerView?.updateStickyPlayerMode()
    }

    override fun onPause() {
        super.onPause()
        playerView?.restoreDefaultBrightness()
        tabs?.saveSelectedTab()
    }

    override fun onStop() {
        super.onStop()
        if (!requireActivity().isChangingConfigurations) {
            DetailPlayerEvents.detailStopped()
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        sheet?.release()
        tabs?.release()
        header?.release()
        actions?.release()
        playerView?.release()
        sheet = null
        tabs = null
        header = null
        actions = null
        playerView = null
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        val activity = requireActivity()
        // Stop the service when the user leaves the app with a double back press while the
        // video player is selected. Otherwise just unbind.
        if (activity.isFinishing && player?.videoPlayerSelected() == true) {
            playerHolder.stopService()
        } else {
            playerHolder.setListener(null)
        }
        DetailPlayerEvents.removeListener(detailEventListener)
        activity.contentResolver.unregisterContentObserver(rotationObserver)
        sponsorBlock.dispose()
        if (activity.isFinishing) {
            DetailHistory.clear()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ReCaptchaActivity.RECAPTCHA_REQUEST) {
            Log.e(TAG, "Request code from activity not supported [$requestCode]")
        } else if (resultCode == Activity.RESULT_OK) {
            NavigationHelper.openVideoDetailFragment(requireContext(), parentFragmentManager,
                vm.serviceId, vm.url, vm.title, null, false)
        } else {
            Log.e(TAG, "ReCaptcha failed")
        }
    }

    // the pager first: ViewPager handled DPAD left/right itself before they reached the player
    override fun onKeyDown(keyCode: Int): Boolean {
        if (tabs?.onKeyDown(keyCode) == true) {
            return true
        }
        val player = player ?: return false
        // The activity gets the keys before they move the focus: while the controls are hidden
        // the player would take any DPAD key to show them, pulling the focus out of the page.
        if (isDpadKey(keyCode) && !player.isFullscreen && isFocusOutsidePlayer(player)) {
            return false
        }
        return player.onKeyDown(keyCode)
    }

    /** Whether a view of the page other than the player (or its thumbnail) has the focus. */
    private fun isFocusOutsidePlayer(player: Player): Boolean {
        val page = view ?: return false
        return page.hasFocus()
            && !binding.detailThumbnailRootLayout.root.hasFocus()
            && !player.binding.root.hasFocus()
    }

    // Loading

    override fun doInitialLoadLogic() {
        if (vm.wasCleared) {
            return
        }
        val info = vm.currentInfo
        when {
            // the result is delivered to this view once loaded
            vm.isLoading || vm.hasPendingResult -> showLoading()
            // the views were recreated, e.g. on rotation: show the stream again, as is
            info != null -> {
                render(info, rebuildTabs = tabs?.isShowing(info) != true)
                header?.showContent()
                if (isAutoplayEnabled()) {
                    openVideoPlayerAutoFullscreen()
                }
            }
            else -> {
                scrollToTop()
                startLoading(false)
            }
        }
    }

    override fun startLoading(forceLoad: Boolean) {
        load(forceLoad, addToBackStack = DetailHistory.isEmpty())
    }

    private fun load(forceLoad: Boolean, addToBackStack: Boolean) {
        vm.currentInfo = null
        super.startLoading(forceLoad)
        vm.load(forceLoad, addToBackStack)
    }

    override fun showLoading() {
        super.showLoading()
        header?.showLoading(vm.serviceId, vm.url, vm.title)
        tabs?.showLoading()
    }

    private fun onLoadResult(result: VideoDetailViewModel.LoadResult) {
        when (result) {
            is VideoDetailViewModel.LoadResult.Success -> {
                isLoading.set(false)
                hideMainPlayerOnLoadingNewStream()
                render(result.info, rebuildTabs = true)
                header?.showContent()
                if (result.addToBackStack) {
                    pushToHistory(result.info)
                }
                if (isAutoplayEnabled()) {
                    openVideoPlayerAutoFullscreen()
                }
            }
            is VideoDetailViewModel.LoadResult.Failure -> showError(ErrorInfo(result.error,
                UserAction.REQUESTED_STREAM, result.url ?: "no url", result.serviceId))
        }
    }

    override fun handleResult(result: StreamInfo) {
        render(result, rebuildTabs = true)
    }

    private fun render(info: StreamInfo, rebuildTabs: Boolean) {
        super.handleResult(info)
        vm.currentInfo = info
        vm.setInitialData(info.serviceId, info.originalUrl, info.name, vm.playQueue)
        if (rebuildTabs) {
            tabs?.showInfo(info)
        }
        header?.showInfo(info, vm.playQueue)
        if (player?.isStopped != false) {
            sheet?.updateOverlayData(info.name, info.uploaderName, info.thumbnailUrl)
        }
    }

    override fun handleError() {
        super.handleError()
        header?.showError()
        tabs?.showError()
    }

    private fun pushToHistory(info: StreamInfo) {
        val queue = vm.playQueue ?: SinglePlayQueue(info).also { vm.playQueue = it }
        if (DetailHistory.peek()?.playQueue != queue) {
            DetailHistory.push(StackItem(vm.serviceId, vm.url, vm.title, queue))
        }
    }

    /**
     * Redraws the page for a stream the player moved to. Delayed, since drawing freezes the main
     * thread, which shows as glitches in the player while it switches streams.
     */
    private fun renderPlayerStreamDelayed(info: StreamInfo) {
        handler.postDelayed({
            if (_binding == null || vm.currentInfo !== info
                || (header?.shownUrl == info.url && tabs?.isShowing(info) == true)) {
                return@postDelayed
            }
            scrollToTop()
            showLoading()
            render(info, rebuildTabs = true)
            header?.showContent()
        }, PLAYER_STREAM_RENDER_DELAY)
    }

    // History

    override fun onBackPressed(): Boolean {
        if (tabs?.onBackPressed() == true) {
            return true
        }
        val player = player
        // In fullscreen the first back press only leaves fullscreen
        if (player != null && player.isFullscreen) {
            if (!DeviceUtils.isTablet(requireContext())) {
                player.pause()
            }
            player.changeFullscreen(false)
            setAutoPlay(false)
            return true
        }
        // Replay the previous item of the queue, if any
        if (player != null && player.videoPlayerSelected()
            && player.playQueue?.previous() == true) {
            return true
        }
        if (DetailHistory.size <= 1) {
            PlayerUiModeController.setOrientation(requireActivity(),
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            // let MainActivity handle it, e.g. to minimize the mini player
            return false
        }
        DetailHistory.pop()
        setupFromHistoryItem(DetailHistory.peek()!!)
        return true
    }

    private fun setupFromHistoryItem(item: StackItem) {
        setAutoPlay(false)
        hideMainPlayerOnLoadingNewStream()
        setInitialData(item.serviceId, item.url, item.title.orEmpty(), item.playQueue)
        startLoading(false)
        // The item may have been removed from the queue in the background
        val queueItem = item.playQueue.item ?: return
        if (player?.isStopped != false) {
            sheet?.updateOverlayData(queueItem.title, queueItem.uploader, queueItem.thumbnailUrl)
        }
    }

    // Players

    private fun openBackgroundPlayer(append: Boolean) {
        player?.changeFullscreen(false)
        // FIXME Workaround #7427
        player?.setRecovery()
        if (player == null) {
            playerHolder.startService(false, this)
        }
        val queue = playQueueForIntent(append) ?: return
        if (append) {
            NavigationHelper.enqueueOnPlayer(requireActivity(), queue, PlayerType.AUDIO)
        } else {
            NavigationHelper.playOnBackgroundPlayer(requireActivity(), queue, true)
        }
    }

    private fun openPopupPlayer(append: Boolean) {
        val activity = requireActivity()
        if (!PermissionHelper.isPopupEnabled(activity)) {
            PermissionHelper.showPopupEnablementToast(activity)
            return
        }
        // See UI changes while the remote play queue changes
        val player = player
        if (player == null) {
            playerHolder.startService(false, this)
        } else {
            // FIXME Workaround #7427
            player.setRecovery()
            player.changeFullscreen(false)
        }
        val queue = playQueueForIntent(append) ?: return
        if (append) {
            NavigationHelper.enqueueOnPlayer(activity, queue, PlayerType.POPUP)
        } else {
            NavigationHelper.playOnPopupPlayer(activity, queue, true)
        }
    }

    private fun openMainPlayer() {
        val playerService = playerService
        if (playerService == null || playerHolder.listener == null) {
            PlaybackStartupTrace.mark(pendingStartupTraceId, "waiting_for_player_service")
            playerHolder.startService(autoPlayEnabled, this)
            return
        }
        val queue = playQueueForIntent(false) ?: return
        PlaybackStartupTrace.mark(pendingStartupTraceId, "play_queue_ready")
        // The video view can still show parts of the popup. It is hidden until the player
        // shows it again once ready.
        playerService.view?.visibility = View.GONE
        playerView?.addVideoPlayerView()
        val context = requireContext()
        val intent = NavigationHelper.getPlayerIntent(context,
            DeviceUtils.getPlayerServiceClass(), queue, true, autoPlayEnabled)
        PlaybackStartupTrace.attach(intent, pendingStartupTraceId)
        ContextCompat.startForegroundService(context, intent)
    }

    /** The queue to play: the current queue, or the current stream alone if [append]. */
    private fun playQueueForIntent(append: Boolean): PlayQueue? {
        val queue = vm.playQueue
        if (!append && queue != null && !queue.isEmpty) {
            return queue
        }
        // the queue may be empty, as it removes failing streams by itself
        return vm.currentInfo?.let(::SinglePlayQueue)
    }

    /**
     * Hides the main player when the page changes to another stream. With autoplay the player
     * is not stopped, to be reused for the new stream without flickering.
     */
    private fun hideMainPlayerOnLoadingNewStream() {
        val playerService = playerService ?: return
        val view = playerService.view ?: return
        if (player?.videoPlayerSelected() != true) {
            return
        }
        playerView?.removeVideoPlayerView()
        if (isAutoplayEnabled()) {
            playerService.stopForImmediateReusing()
            view.visibility = View.GONE
        } else {
            playerHolder.stopService()
        }
    }

    /** Autoplay, unless an external player is selected, the page is hidden or it is disabled. */
    private fun isAutoplayEnabled(): Boolean =
        autoPlayEnabled
            && player?.videoPlayerSelected() != false
            && sheetStateToSave != BottomSheetBehavior.STATE_HIDDEN
            && PlayerHelper.isAutoplayAllowedByUser(requireContext())

    private fun beginStartupTrace() {
        vm.currentInfo?.let { pendingStartupTraceId = PlaybackStartupTrace.begin(it.id, it.url) }
    }

    // Player service

    override fun onServiceConnected(connectedPlayer: Player,
                                    connectedPlayerService: PlayerServiceInterface,
                                    playAfterConnect: Boolean) {
        player = connectedPlayer
        playerService = connectedPlayerService
        sheet?.updateDraggableForFullscreen()
        // does nothing if the player is not in fullscreen
        hideSystemUiIfNeeded()
        if (!connectedPlayer.videoPlayerSelected() && !playAfterConnect) {
            return
        }
        if (!connectedPlayer.isStopped && connectedPlayer.videoPlayerSelected()) {
            playerView?.addVideoPlayerView()
        }
        if (playAfterConnect || (vm.currentInfo != null && isAutoplayEnabled()
                    && connectedPlayer.parentActivity == null)) {
            autoPlayEnabled = true // forcefully start playing
            openVideoPlayerAutoFullscreen()
        }
    }

    override fun onServiceDisconnected() {
        playerService = null
        player = null
        playerView?.restoreDefaultBrightness()
    }

    override fun onQueueUpdate(queue: PlayQueue) {
        vm.playQueue = queue
        // The only place where the history grows while playing: it holds the live queue, so it
        // knows about the items added or removed in the background.
        val top = DetailHistory.peek()
        val item = queue.item
        if (top != null && top.playQueue != queue && item != null) {
            DetailHistory.push(StackItem(item.serviceId, item.url, item.title, queue))
            return
        }
        // The queue of the player service is disposed with the service. Keep the new one, which
        // has the same history, to have up to date recovery positions.
        DetailHistory.find(queue)?.playQueue = queue
    }

    override fun onPlaybackUpdate(state: PlayerPlaybackState, repeatMode: RepeatMode,
                                  shuffled: Boolean, parameters: PlayerPlaybackParameters) {
        sheet?.setPlayPauseImage(player?.isPlaying == true)
        if (state == PlayerPlaybackState.PLAYING
            && player?.playQueue?.item?.url == vm.url) {
            header?.showPositionIfHidden()
        }
    }

    override fun onProgressUpdate(currentProgress: Int, duration: Int, bufferPercent: Int) {
        // Updates come every second even while paused, they are useless until playing
        val player = player ?: return
        if (!player.isPlaying || vm.playQueue == null) {
            return
        }
        sponsorBlock.onProgress(currentProgress)
        if (player.playQueue?.item?.url == vm.url) {
            header?.showPlaybackProgress(currentProgress.toLong(), duration.toLong())
        }
    }

    override fun onMetadataUpdate(info: StreamInfo, queue: PlayQueue) {
        if (player != null && isAdded) {
            sponsorBlock.onMetadataUpdate()
        }
        // A queue of several streams plays a new stream: the history entry follows it
        DetailHistory.find(queue)?.let {
            it.title = info.name
            it.url = info.url
        }
        // Different when the user plays something in the popup while browsing here, and then
        // rotates: the new page registers as listener and gets this first call.
        if (queue != vm.playQueue) {
            return
        }
        sheet?.updateOverlayData(info.name, info.uploaderName, info.thumbnailUrl)
        if (vm.currentInfo?.url == info.url) {
            return
        }
        vm.currentInfo = info
        vm.setInitialData(info.serviceId, info.url, info.name, queue)
        setAutoPlay(false)
        renderPlayerStreamDelayed(info)
    }

    override fun onPlayerError(error: PlayerError, isCatchableException: Boolean) {
        if (!isCatchableException) {
            // Leave fullscreen properly
            player?.changeFullscreen(false)
            hideMainPlayerOnLoadingNewStream()
        }
    }

    override fun onServiceStopped() {
        sheet?.setPlayPauseImage(false)
        vm.currentInfo?.let { sheet?.updateOverlayData(it.name, it.uploaderName, it.thumbnailUrl) }
    }

    override fun onFullscreenStateChanged(fullscreen: Boolean) {
        sheet?.updateDraggableForFullscreen()
        playerView?.setupBrightness()
        val view = playerService?.view
        if (player?.parentActivity == null || view?.parent == null || _binding == null) {
            return
        }
        if (fullscreen) {
            hideSystemUiIfNeeded()
        } else {
            playerView?.showSystemUi()
        }
        tabs?.onFullscreenChanged(fullscreen)
        scrollToTop()
        playerView?.addVideoPlayerView()
    }

    /** Scrolls down to the description after a long click on the "more options" button. */
    override fun onMoreOptionsLongClicked() {
        playerView?.scrollPastPlayer()
    }

    override fun hideSystemUiIfNeeded() {
        playerView?.hideSystemUiIfNeeded()
    }

    // Bottom sheet

    /** The sheet belongs to the activity and is there even while this page has no views. */
    private fun sheetBehavior(): BottomSheetBehavior<FrameLayout>? =
        activity?.findViewById<FrameLayout>(R.id.fragment_player_holder)
            ?.let { BottomSheetBehavior.from(it) }

    private fun onDetailEvent(event: DetailPlayerEvents.Event) {
        val behavior = sheetBehavior() ?: return
        when (event) {
            DetailPlayerEvents.Event.ShowMainPlayer ->
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            DetailPlayerEvents.Event.HideMainPlayer ->
                behavior.state = BottomSheetBehavior.STATE_HIDDEN
            DetailPlayerEvents.Event.PlayerStarted -> {
                // Only show the mini player if the sheet is hidden
                if (behavior.state == BottomSheetBehavior.STATE_HIDDEN) {
                    behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                }
                // Bind again if the service was closed from the notification or mini player
                if (!playerHolder.isBound) {
                    playerHolder.startService(false, this)
                }
            }
            else -> Unit
        }
    }

    /** The page was swiped away: empty it, ready for the next stream. */
    private fun cleanUp() {
        DetailHistory.clear()
        vm.cancelLoading()
        playerHolder.stopService()
        vm.setInitialData(0, null, "", null)
        vm.currentInfo = null
        sheet?.updateOverlayData(null, null, null)
    }

    // Parts of the page

    private val sponsorBlock = DetailSponsorBlock(this, object : DetailSponsorBlock.Host {
        override val player get() = this@VideoDetailFragment.player
        override val currentInfo get() = vm.currentInfo
        override val sponsorBlockFragment: SponsorBlockFragment?
            get() = tabs?.sponsorBlockFragment()
    })

    /** What the parts of the page need to know about the page and the player. */
    private val hosts = object : DetailPlayerViewHost.Host, MiniPlayerSheetController.Host,
        DetailActions.Host, DetailTabsController.Host {

        override val player get() = this@VideoDetailFragment.player
        override val playerService get() = this@VideoDetailFragment.playerService
        override val currentInfo get() = vm.currentInfo
        override val serviceId get() = vm.serviceId
        override val isLoading get() = this@VideoDetailFragment.isLoading.get()
        override val isFullscreen get() = player?.isFullscreen == true
        override val sponsorBlockListener: SponsorBlockFragmentListener get() = sponsorBlock

        override var savedSheetState: Int
            get() = sheetStateToSave
            set(value) {
                sheetStateToSave = value
            }

        override val sheetState: Int
            get() = sheetBehavior()?.state ?: sheetStateToSave

        override fun onSheetHidden() = cleanUp()

        override fun onPlayPauseClicked() {
            val player = player
            if (player != null && !player.isStopped) {
                player.playPause()
                player.hideControls(0, 0)
                playerView?.showSystemUi()
            } else {
                beginStartupTrace()
                autoPlayEnabled = true // forcefully start playing
                openVideoPlayer(false)
            }
            sheet?.setPlayPauseImage(this@VideoDetailFragment.player?.isPlaying == true)
        }

        override fun openUploaderChannel() {
            vm.currentInfo?.let { actions?.openChannel(it.uploaderUrl, it.uploaderName) }
        }

        override fun openBackgroundPlayer(append: Boolean) =
            this@VideoDetailFragment.openBackgroundPlayer(append)

        override fun openPopupPlayer(append: Boolean) =
            this@VideoDetailFragment.openPopupPlayer(append)

        override fun playInMainPlayer() {
            beginStartupTrace()
            autoPlayEnabled = true // forcefully start playing
            // FIXME Workaround #7427
            player?.setRecovery()
            openVideoPlayerAutoFullscreen()
        }
    }
}
