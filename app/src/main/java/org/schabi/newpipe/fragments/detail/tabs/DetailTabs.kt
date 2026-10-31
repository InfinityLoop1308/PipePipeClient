package org.schabi.newpipe.fragments.detail.tabs

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import org.schabi.newpipe.R

/**
 * A tab of the video detail page.
 *
 * [tag] is persisted in the "selected tab" preference, keep the values stable.
 * [prefValue] is the entry of the "video tabs" setting that enables the tab.
 */
enum class DetailTab(
    val tag: String,
    val prefValue: String?,
    @DrawableRes val icon: Int,
    @StringRes val description: Int,
    /** Whether the tab is recreated for every stream instead of being updated in place. */
    val perStream: Boolean,
) {
    CHAT("CHAT TAB", "chat", R.drawable.ic_bullet_comment_enabled,
        R.string.chat_tab_description, false),
    COMMENTS("COMMENTS", "comments", R.drawable.ic_comment,
        R.string.comments_tab_description, true),
    RELATED("NEXT VIDEO", "related", R.drawable.ic_art_track,
        R.string.related_items_tab_description, true),
    DESCRIPTION("DESCRIPTION TAB", "description", R.drawable.ic_description,
        R.string.description_tab_description, true),
    SPONSOR_BLOCK("SPONSOR_BLOCK TAB", "sponsorblock", R.drawable.ic_sponsor_block_enable,
        R.string.sponsor_block, true),
    EMPTY("EMPTY TAB", null, 0, 0, true);

    companion object {
        /** Value of the "video tabs" setting when the user never changed it. */
        val DEFAULT_PREF_VALUES: Set<String> = entries.mapNotNull { it.prefValue }.toSet()

        fun fromTag(tag: String?): DetailTab? = entries.firstOrNull { it.tag == tag }
    }
}

/** One page of the pager. [create] is called whenever the page's fragment has to be built. */
class TabSpec(
    val tab: DetailTab,
    val itemId: Long,
    val create: () -> Fragment,
)

/**
 * The tabs state which must outlive the views of the detail page, so that recreating the views
 * (e.g. on rotation) shows the very same tab fragments again instead of rebuilding them.
 */
class DetailTabsState {
    /** Bumped whenever the per-stream tabs have to be rebuilt. Part of the pages' item ids. */
    var generation = 0L

    /** The tabs last shown, without the chat tab, which comes and goes on its own. */
    var tabs: List<TabSpec> = emptyList()

    /** URL of the stream the tabs were built for, null for the loading placeholders. */
    var infoUrl: String? = null

    /** The settings the tabs were built with, to detect changes when coming back. */
    var settings: Set<String>? = null

    /** Tag of the tab the user selected last. */
    var preferredTag: String? = null

    /** Whether [tabs] were built for the layout with the related items side panel. */
    var withSidePanel = false

    /** Whether a submit was skipped because the fragment state was already saved. */
    var pendingSubmit = false
}

internal fun itemIdOf(tab: DetailTab, generation: Long): Long =
    if (tab.perStream) (generation shl 8) or tab.ordinal.toLong() else tab.ordinal.toLong()

/**
 * Pager adapter keyed by [TabSpec.itemId]. A new item id means a new fragment: the old one is
 * removed by [FragmentStateAdapter] together with its saved state.
 */
class DetailTabsAdapter(
    private val fragmentManager: FragmentManager,
    lifecycle: Lifecycle,
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    var specs: List<TabSpec> = emptyList()
        private set

    fun submit(newSpecs: List<TabSpec>) {
        specs = newSpecs
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = specs.size

    override fun getItemId(position: Int): Long = specs[position].itemId

    override fun containsItem(itemId: Long): Boolean = specs.any { it.itemId == itemId }

    override fun createFragment(position: Int): Fragment = specs[position].create()

    fun positionOf(tab: DetailTab): Int = specs.indexOfFirst { it.tab == tab }

    /** The fragment currently shown for [tab], if any. */
    fun fragmentOf(tab: DetailTab): Fragment? {
        val spec = specs.firstOrNull { it.tab == tab } ?: return null
        // FragmentStateAdapter tags its fragments with "f" + item id
        return fragmentManager.findFragmentByTag("f${spec.itemId}")
    }
}
