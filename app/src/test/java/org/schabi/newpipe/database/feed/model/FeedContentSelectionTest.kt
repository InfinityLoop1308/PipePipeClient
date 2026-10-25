package org.schabi.newpipe.database.feed.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedContentSelectionTest {

    @Test
    fun `stable masks represent the three feed categories`() {
        assertEquals(1, FeedContentType.VIDEOS.mask)
        assertEquals(2, FeedContentType.SHORTS.mask)
        assertEquals(4, FeedContentType.LIVE.mask)
        assertEquals(7, FeedContentSelection.ALL.mask)
    }

    @Test
    fun `every nonempty known mask can be restored`() {
        for (mask in 1..7) {
            assertEquals(mask, FeedContentSelection.fromMask(mask).mask)
        }
    }

    @Test
    fun `empty and unknown masks are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FeedContentSelection.fromMask(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FeedContentSelection.fromMask(8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FeedContentSelection.fromMask(9)
        }
    }

    @Test
    fun `category selections can be combined and compared`() {
        val videosAndShorts = FeedContentSelection.of(
            FeedContentType.VIDEOS,
            FeedContentType.SHORTS
        )

        assertTrue(videosAndShorts.contains(FeedContentType.VIDEOS))
        assertTrue(videosAndShorts.contains(FeedContentType.SHORTS))
        assertFalse(videosAndShorts.contains(FeedContentType.LIVE))
        assertTrue(videosAndShorts.intersects(FeedContentSelection.SHORTS))
        assertFalse(videosAndShorts.intersects(FeedContentSelection.LIVE))
        assertEquals(
            FeedContentSelection.ALL,
            videosAndShorts.union(FeedContentSelection.LIVE)
        )
    }
}
