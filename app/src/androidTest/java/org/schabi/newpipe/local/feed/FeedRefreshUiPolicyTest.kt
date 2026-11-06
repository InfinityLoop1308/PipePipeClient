package org.schabi.newpipe.local.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRefreshUiPolicyTest {
    @Test
    fun existingContentUsesNonBlockingRefresh() {
        assertTrue(
            FeedRefreshUiPolicy.shouldKeepContentVisible(
                displayedItemCount = 10,
                previousItemCount = 0
            )
        )
        assertTrue(
            FeedRefreshUiPolicy.shouldKeepContentVisible(
                displayedItemCount = 0,
                previousItemCount = 10
            )
        )
    }

    @Test
    fun emptyFeedUsesBlockingRefresh() {
        assertFalse(
            FeedRefreshUiPolicy.shouldKeepContentVisible(
                displayedItemCount = 0,
                previousItemCount = 0
            )
        )
        assertNull(FeedRefreshStateCache().progress(-1, -1, 0).previousContent)
    }

    @Test
    fun progressIsDeterminateWhenMaximumIsKnown() {
        assertTrue(FeedRefreshUiPolicy.isIndeterminate(-1, -1))
        assertFalse(FeedRefreshUiPolicy.isIndeterminate(0, 10))
        assertTrue(FeedRefreshUiPolicy.isIndeterminate(1, 0))
        assertFalse(FeedRefreshUiPolicy.isIndeterminate(1, 10))
    }

    @Test
    fun successfulLoadIsRestoredForProgressAndFailure() {
        val previousContent = FeedState.LoadedState(
            items = emptyList(),
            notLoadedCount = 0
        )
        val cache = FeedRefreshStateCache()

        assertTrue(cache.needsSnapshot(showPlayedItems = true))
        cache.remember(previousContent, showPlayedItems = true)
        assertFalse(cache.needsSnapshot(showPlayedItems = true))

        assertSame(
            previousContent,
            cache.progress(-1, -1, 0).previousContent
        )
        assertSame(
            previousContent,
            cache.error(IllegalStateException()).previousContent
        )
    }

    @Test
    fun changingPlayedItemsFilterRequiresOneNewSnapshot() {
        val cache = FeedRefreshStateCache()
        cache.remember(
            FeedState.LoadedState(items = emptyList(), notLoadedCount = 0),
            showPlayedItems = true
        )

        assertTrue(cache.needsSnapshot(showPlayedItems = false))
    }
}
