package org.schabi.newpipe.player

import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Observer
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem
import org.schabi.newpipe.fragments.detail.ChatListView
import org.schabi.newpipe.fragments.detail.PinnedScView
import org.schabi.newpipe.player.chat.ChatStore
import org.schabi.newpipe.player.chat.PinnedSc

/**
 * Chat side panel of the fullscreen (landscape) player, shown when the chat tab of the detail
 * page is not visible. Opened with the former bullet comments switch of the player controls.
 */
class ChatPanelController(private val player: Player) {

    private var chatList: ChatListView? = null
    private var pinnedView: PinnedScView? = null
    /** Whether the panel is currently on screen. */
    private var open = false
    private var observing = false

    /** The persisted choice of the user, which outlives the panel being hidden by the state of the player. */
    private var wanted: Boolean
        get() = player.prefs.getBoolean(PANEL_OPEN_PREF_KEY, false)
        set(value) = player.prefs.edit().putBoolean(PANEL_OPEN_PREF_KEY, value).apply()

    private val itemsObserver = Observer<List<BulletCommentsInfoItem>> { items ->
        chatList?.submit(items)
    }
    private val pinnedObserver = Observer<List<PinnedSc>> { pinnedView?.submit(it) }
    private val availableObserver = Observer<Boolean?> { update() }

    private fun canShow(): Boolean =
        player.bulletCommentsController.isChatMode &&
            player.videoPlayerSelected() &&
            player.isFullscreen &&
            player.context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            ChatStore.url != null &&
            ChatStore.available.value != false

    /** Visibility of the player's switch button while in chat mode. */
    fun buttonVisibility(): Int = if (canShow()) View.VISIBLE else View.GONE

    /** Brings the button and the panel in line with the current state of the player. */
    fun update() {
        val binding = player.binding
        val show = canShow()
        if (player.bulletCommentsController.isChatMode) {
            binding.switchCommentsVisibility.visibility = buttonVisibility()
            binding.switchCommentsVisibility.setImageDrawable(
                AppCompatResources.getDrawable(
                    player.context,
                    if (wanted) R.drawable.ic_bullet_comment_enabled
                    else R.drawable.ic_bullet_comment_disabled,
                ),
            )
        }
        if (show) {
            startObserving()
            if (wanted != open) {
                setOpen(wanted)
            }
        } else {
            stopObserving()
            if (open) {
                setOpen(false)
            }
        }
    }

    fun toggle() {
        if (canShow()) {
            wanted = !wanted
            update()
        }
    }

    fun release() {
        stopObserving()
        open = false
        player.binding.chatPanel.visibility = View.GONE
    }

    private fun setOpen(value: Boolean) {
        open = value
        val binding = player.binding
        if (value && chatList == null) {
            chatList = ChatListView(binding.chatPanelList, null, forceLight = true)
            val pinnedRoot = binding.chatPanelPinned.root
            pinnedRoot.setBackgroundColor(0xE6000000.toInt())
            pinnedView = PinnedScView(pinnedRoot)
            pinnedView?.submit(ChatStore.pinned.value.orEmpty())
            // Never pass scrolls on to the AppBarLayout: it would scroll the fullscreen player away
            binding.chatPanelList.isNestedScrollingEnabled = false
            // Keep the touch stream of the list away from the bottom sheet / player gestures
            binding.chatPanelList.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        if (value) {
            // Bound the panel to the screen: an unbounded list would otherwise grow the player
            // layout and push the video out of view.
            val metrics = player.context.resources.displayMetrics
            val rootHeight = binding.root.height
            val height = if (rootHeight > 0) minOf(rootHeight, metrics.heightPixels)
            else metrics.heightPixels
            val width = minOf((300 * metrics.density).toInt(), (metrics.widthPixels * 0.45f).toInt())
            binding.chatPanel.layoutParams = binding.chatPanel.layoutParams.apply {
                this.height = height
                this.width = width
            }
        }
        binding.chatPanel.visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            chatList?.submit(ChatStore.items.value.orEmpty())
            chatList?.scrollToEnd()
        }
    }

    private fun startObserving() {
        if (!observing) {
            observing = true
            ChatStore.items.observeForever(itemsObserver)
            ChatStore.available.observeForever(availableObserver)
            ChatStore.pinned.observeForever(pinnedObserver)
        }
    }

    private fun stopObserving() {
        if (observing) {
            observing = false
            ChatStore.items.removeObserver(itemsObserver)
            ChatStore.available.removeObserver(availableObserver)
            ChatStore.pinned.removeObserver(pinnedObserver)
        }
    }

    private companion object {
        const val PANEL_OPEN_PREF_KEY = "isChatPanelOpen"
    }
}
