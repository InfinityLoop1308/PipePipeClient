package org.schabi.newpipe.fragments.list.search.filter

import org.schabi.newpipe.extractor.search.filter.Filter
import org.schabi.newpipe.extractor.search.filter.FilterGroup
import org.schabi.newpipe.extractor.search.filter.FilterItem

/**
 * Content filters of a service may be missing: not every service supports filtering its search,
 * and both the plugin API and [org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory]
 * allow `getAvailableContentFilter()` to return `null` then.
 *
 * The search UI still expects one selectable content filter, so a single "all" filter is used
 * instead of crashing on the missing filter.
 */
object SearchFilterFallback {
    const val ALL_FILTER_ID = 0

    /** The name is translated by [org.schabi.newpipe.util.ServiceHelper]. */
    const val ALL_FILTER_NAME = "all"

    @JvmStatic
    fun allFilterItem(): FilterItem = FilterItem(ALL_FILTER_ID, ALL_FILTER_NAME)

    private fun allFilterGroup(): FilterGroup =
        FilterGroup(ALL_FILTER_ID, null, true, arrayOf(allFilterItem()))

    /**
     * @return the filter groups of the given content filter, or a group holding only an "all"
     * filter when the service declares no content filter
     */
    @JvmStatic
    fun contentFilterGroups(availableContentFilter: Filter?): Array<FilterGroup> {
        val groups = availableContentFilter?.filterGroups
        return if (groups.isNullOrEmpty()) arrayOf(allFilterGroup()) else groups
    }
}
