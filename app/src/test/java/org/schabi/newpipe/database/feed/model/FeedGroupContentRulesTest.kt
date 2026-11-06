package org.schabi.newpipe.database.feed.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedGroupContentRulesTest {

    @Test
    fun `membership inherits the group default without an override`() {
        assertEquals(
            FeedContentSelection.VIDEOS,
            FeedGroupContentRules.effectiveSelection(
                FeedContentSelection.VIDEOS,
                null
            )
        )
    }

    @Test
    fun `membership override replaces the group default`() {
        assertEquals(
            FeedContentSelection.LIVE,
            FeedGroupContentRules.effectiveSelection(
                FeedContentSelection.VIDEOS,
                FeedContentSelection.LIVE
            )
        )
    }

    @Test
    fun `override equal to the default is stored as inheritance`() {
        assertNull(
            FeedGroupContentRules.normalizedOverride(
                FeedContentSelection.SHORTS,
                FeedContentSelection.SHORTS
            )
        )
    }

    @Test
    fun `normalization removes overrides for deleted memberships and default collisions`() {
        val normalized = FeedGroupContentRules.normalizeOverrides(
            defaultSelection = FeedContentSelection.VIDEOS,
            subscriptionIds = setOf(1L, 2L, 3L),
            overrides = mapOf(
                1L to FeedContentSelection.LIVE,
                2L to FeedContentSelection.VIDEOS,
                4L to FeedContentSelection.SHORTS
            )
        )

        assertEquals(mapOf(1L to FeedContentSelection.LIVE), normalized)
    }
}
