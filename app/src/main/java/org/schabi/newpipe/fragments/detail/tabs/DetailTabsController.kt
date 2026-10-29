package org.schabi.newpipe.fragments.detail.tabs

import android.content.SharedPreferences
import android.graphics.Point
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentOnAttachListener
import androidx.preference.PreferenceManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.bilibili.utils.isFirstP
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.fragments.BackPressable
import org.schabi.newpipe.fragments.EmptyFragment
import org.schabi.newpipe.fragments.detail.ChatFragment
import org.schabi.newpipe.fragments.detail.DescriptionFragment
import org.schabi.newpipe.fragments.list.comments.CommentsFragmentContainer
import org.schabi.newpipe.fragments.list.sponsorblock.SponsorBlockFragment
import org.schabi.newpipe.fragments.list.sponsorblock.SponsorBlockFragmentListener
import org.schabi.newpipe.fragments.list.videos.RelatedItemsFragment
import org.schabi.newpipe.player.chat.ChatStore
import org.schabi.newpipe.util.SponsorBlockHelper

/**
 * Owns the tabs of the video detail page: which tabs exist for a stream, the pager showing them,
 * the tab layout, the remembered tab and the related items side panel of tablets and TV.
 *
 * The tabs are always derived from the settings and the stream as a whole list, see [buildTabs];
 * nothing is patched in place.
 */
class DetailTabsController(
    private val fragment: Fragment,
    private val pager: ViewPager2,
    private val tabLayout: TabLayout,
    /** The related items panel of tablets and TV, null on phones where related items are a tab. */
    private val relatedPanel: View?,
    private val state: DetailTabsState,
    private val host: Host,
) {
    interface Host {
        val serviceId: Int
        val currentInfo: StreamInfo?
        val isFullscreen: Boolean
        val sponsorBlockListener: SponsorBlockFragmentListener
    }

    private val context get() = fragment.requireContext()
    private val prefs: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val childFm get() = fragment.childFragmentManager

    private val adapter = DetailTabsAdapter(childFm, fragment.viewLifecycleOwner.lifecycle)
    private val mediator = TabLayoutMediator(tabLayout, pager) { tab, position ->
        val detailTab = adapter.specs[position].tab
        if (detailTab.icon != 0) {
            tab.setIcon(detailTab.icon)
        }
        if (detailTab.description != 0) {
            tab.contentDescription = context.getString(detailTab.description)
        }
    }

    /** True while this class changes the pages or the selected page. */
    private var applying = false

    /** The tab this class selected last; selecting any other tab is the user's choice. */
    private var selectedByCode: DetailTab? = null

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val tab = adapter.specs.getOrNull(position)?.tab ?: return
            // Page changes caused by rebuilding the tabs are reported after the fact, but always
            // for the page selected by applySelection(): those are not the user's choice.
            if (applying || tab == selectedByCode) {
                return
            }
            selectedByCode = null
            if (tab != DetailTab.EMPTY) {
                state.preferredTag = tab.tag
            }
        }
    }

    private val attachListener = FragmentOnAttachListener { _, child ->
        // also covers SponsorBlock fragments restored by the fragment manager
        if (child is SponsorBlockFragment) {
            child.setListener(host.sponsorBlockListener)
        }
    }

    init {
        if (state.preferredTag == null) {
            state.preferredTag = prefs.getString(
                context.getString(R.string.stream_info_selected_tab_key), DetailTab.COMMENTS.tag)
        }
        pager.adapter = adapter
        // keep every tab alive, so that switching tabs never reloads one
        pager.offscreenPageLimit = DetailTab.entries.size
        pager.registerOnPageChangeCallback(pageCallback)
        mediator.attach()
        childFm.addFragmentOnAttachListener(attachListener)
        childFm.fragments.filterIsInstance<SponsorBlockFragment>()
            .forEach { it.setListener(host.sponsorBlockListener) }
        ChatStore.available.observe(fragment.viewLifecycleOwner) { syncChatTab() }

        // the views were recreated: show the very same tabs again
        if (state.tabs.isNotEmpty()) {
            submit()
        }
    }

    /** Shows the placeholders of the tabs while a stream loads. */
    fun showLoading() {
        state.generation++
        state.settings = currentSettings()
        state.tabs = buildTabs(null)
        submit()
        relatedPanel?.visibility = when {
            DetailTab.RELATED.prefValue !in state.settings.orEmpty() -> View.GONE
            host.isFullscreen -> View.GONE
            else -> View.INVISIBLE
        }
    }

    /** Builds the tabs for [info]. */
    fun showInfo(info: StreamInfo) {
        state.generation++
        state.settings = currentSettings()
        state.tabs = buildTabs(info)
        submit()
        pager.visibility = View.VISIBLE
        updateRelatedPanel(info)
        updateTabLayoutVisibility()
    }

    fun showError() {
        relatedPanel?.visibility = View.INVISIBLE
        pager.visibility = View.GONE
        tabLayout.visibility = View.GONE
    }

    /** Whether the tabs show [info] and are up to date. */
    fun isShowing(info: StreamInfo): Boolean =
        state.infoUrl == info.url && !state.pendingSubmit && state.settings == currentSettings()

    /** Applies what changed while the page was not visible. */
    fun onResume() {
        val info = host.currentInfo
        if (info != null && state.settings != currentSettings()) {
            // the tab settings changed
            showInfo(info)
        } else if (state.pendingSubmit) {
            submit()
        }
    }

    /** Selects the remembered tab again, overriding the page restored by the pager itself. */
    fun onViewStateRestored() {
        applySelection(null)
    }

    fun onFullscreenChanged(fullscreen: Boolean) {
        relatedPanel?.visibility = if (fullscreen) View.GONE else View.VISIBLE
    }

    /** Lets the comments tab handle back presses, as it owns the comment replies. */
    fun onBackPressed(): Boolean {
        val current = adapter.specs.getOrNull(pager.currentItem)?.tab
        return current == DetailTab.COMMENTS
            && (adapter.fragmentOf(DetailTab.COMMENTS) as? BackPressable)?.onBackPressed() == true
    }

    fun sponsorBlockFragment(): SponsorBlockFragment? =
        adapter.fragmentOf(DetailTab.SPONSOR_BLOCK) as? SponsorBlockFragment

    fun saveSelectedTab() {
        prefs.edit()
            .putString(context.getString(R.string.stream_info_selected_tab_key),
                state.preferredTag)
            .apply()
    }

    fun release() {
        mediator.detach()
        pager.unregisterOnPageChangeCallback(pageCallback)
        childFm.removeFragmentOnAttachListener(attachListener)
        pager.adapter = null
    }

    /**
     * Hides the tab layout if there is only one tab, or if the pager is (almost) scrolled out of
     * the screen, and moves it up as the pager scrolls in.
     */
    fun updateTabLayoutVisibility() {
        if (adapter.itemCount < 2 || pager.visibility != View.VISIBLE) {
            tabLayout.visibility = View.GONE
            return
        }
        // post() so that the hit rect of the pager is up to date
        tabLayout.post {
            val ctx = fragment.context ?: return@post
            val pagerHitRect = Rect()
            pager.getHitRect(pagerHitRect)
            val displaySize = Point()
            @Suppress("DEPRECATION")
            ContextCompat.getSystemService(ctx, WindowManager::class.java)!!
                .defaultDisplay.getSize(displaySize)
            val pagerVisibleHeight = displaySize.y - pagerHitRect.top
            // see TabLayout.DEFAULT_HEIGHT, which is equal to 48dp
            val tabLayoutHeight = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 48f, ctx.resources.displayMetrics)
            if (pagerVisibleHeight > tabLayoutHeight * 2) {
                // no translation at all when pagerVisibleHeight > tabLayoutHeight * 3
                tabLayout.translationY = maxOf(0f, tabLayoutHeight * 3 - pagerVisibleHeight)
                tabLayout.visibility = View.VISIBLE
            } else {
                tabLayout.visibility = View.GONE
            }
        }
    }

    /** Adds or removes the chat tab, whose availability is only known after loading the chat. */
    private fun syncChatTab() {
        val hasChat = adapter.positionOf(DetailTab.CHAT) != -1
        if (hasChat != shouldShowChat()) {
            submit()
        }
    }

    private fun submit() {
        if (childFm.isStateSaved) {
            // fragment transactions are not possible anymore, apply this once visible again
            state.pendingSubmit = true
            return
        }
        state.pendingSubmit = false
        val specs = buildList {
            if (shouldShowChat()) {
                add(TabSpec(DetailTab.CHAT, itemIdOf(DetailTab.CHAT, 0)) { ChatFragment() })
            }
            addAll(state.tabs)
            if (isEmpty()) {
                add(TabSpec(DetailTab.EMPTY, itemIdOf(DetailTab.EMPTY, state.generation)) {
                    EmptyFragment.newInstance(true)
                })
            }
        }
        val currentTab = adapter.specs.getOrNull(pager.currentItem)?.tab
        applying = true
        try {
            adapter.submit(specs)
        } finally {
            applying = false
        }
        applySelection(currentTab)
        updateTabLayoutVisibility()
    }

    /** Selects the remembered tab, or else [fallback], or else the first one. */
    private fun applySelection(fallback: DetailTab?) {
        if (adapter.itemCount == 0) {
            return
        }
        val target = sequenceOf(DetailTab.fromTag(state.preferredTag), fallback)
            .filterNotNull()
            .map { adapter.positionOf(it) }
            .firstOrNull { it != -1 } ?: 0
        applying = true
        try {
            pager.setCurrentItem(target, false)
            selectedByCode = adapter.specs[target].tab
        } finally {
            applying = false
        }
    }

    private fun buildTabs(info: StreamInfo?): List<TabSpec> {
        val enabled = state.settings.orEmpty()
        val capabilities = capabilitiesOf(info?.serviceId ?: host.serviceId)
        val generation = state.generation
        state.infoUrl = info?.url
        val tabs = mutableListOf<TabSpec>()
        fun add(tab: DetailTab, create: (StreamInfo) -> Fragment) {
            tabs += TabSpec(tab, itemIdOf(tab, generation)) {
                // placeholders until the stream is loaded
                info?.let(create) ?: EmptyFragment.newInstance(false)
            }
        }

        if (DetailTab.COMMENTS.prefValue in enabled
            && MediaCapability.COMMENTS in capabilities
            && (info == null || info.isSupportComments)) {
            add(DetailTab.COMMENTS) {
                CommentsFragmentContainer.getInstance(it.serviceId, it.originalUrl, it.name)
            }
        }
        if (DetailTab.RELATED.prefValue in enabled && relatedPanel == null
            && (info == null || info.isSupportRelatedItems)) {
            add(DetailTab.RELATED) { RelatedItemsFragment.getInstance(it) }
        }
        if (DetailTab.DESCRIPTION.prefValue in enabled) {
            add(DetailTab.DESCRIPTION) { DescriptionFragment(it) }
        }
        if (DetailTab.SPONSOR_BLOCK.prefValue in enabled
            && MediaCapability.SPONSORBLOCK in capabilities
            && (info == null || supportsSponsorBlock(info))) {
            add(DetailTab.SPONSOR_BLOCK) { streamInfo ->
                SponsorBlockFragment(streamInfo).also {
                    it.setListener(host.sponsorBlockListener)
                    it.setSponsorBlockMode(SponsorBlockHelper.getSkippingMode(context))
                }
            }
        }
        return tabs
    }

    private fun updateRelatedPanel(info: StreamInfo) {
        val panel = relatedPanel ?: return
        if (info.isRoundPlayStream
            || (DetailTab.RELATED.prefValue in state.settings.orEmpty()
                    && info.isSupportRelatedItems)) {
            childFm.beginTransaction()
                .replace(R.id.relatedItemsLayout, RelatedItemsFragment.getInstance(info))
                .commitAllowingStateLoss()
            panel.visibility = if (host.isFullscreen) View.GONE else View.VISIBLE
        }
    }

    private fun shouldShowChat(): Boolean {
        val info = host.currentInfo ?: return false
        return info.isSupportBulletComments
            && MediaCapability.BULLET_COMMENTS in capabilitiesOf(info.serviceId)
            // loading the chat found nothing for this stream
            && ChatStore.available.value != false
            // the video tabs option only controls whether the tab is added, the fullscreen chat
            // is unaffected
            && DetailTab.CHAT.prefValue in currentSettings()
    }

    /** The enabled tabs, also reflecting the settings which turn tabs off elsewhere. */
    private fun currentSettings(): Set<String> {
        val prefs = prefs
        val enabled = prefs.getStringSet(context.getString(R.string.video_tabs_key),
            DetailTab.DEFAULT_PREF_VALUES)!!.toMutableSet()
        if (!prefs.getBoolean(context.getString(R.string.sponsor_block_enable_key), true)) {
            enabled.remove(DetailTab.SPONSOR_BLOCK.prefValue)
        }
        if (!prefs.getBoolean(context.getString(R.string.bullet_comments_chat_mode_key), true)) {
            enabled.remove(DetailTab.CHAT.prefValue)
        }
        return enabled
    }

    private companion object {
        fun capabilitiesOf(serviceId: Int): List<MediaCapability> = try {
            NewPipe.getService(serviceId).serviceInfo.mediaCapabilities
        } catch (_: ExtractionException) {
            emptyList()
        }

        /** No SponsorBlock for live streams and for parts of BiliBili videos but the first. */
        fun supportsSponsorBlock(info: StreamInfo): Boolean =
            info.streamType != StreamType.LIVE_STREAM
                && (info.serviceId != ServiceList.BiliBili.serviceId || isFirstP(info.id))
    }
}
