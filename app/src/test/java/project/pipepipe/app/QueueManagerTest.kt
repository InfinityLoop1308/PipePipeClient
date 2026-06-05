package project.pipepipe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import project.pipepipe.app.platform.PlatformMediaItem

class QueueManagerTest {
    private fun item(id: String) = PlatformMediaItem(
        mediaId = id,
        title = id,
        artist = null,
        artworkUrl = null,
        durationMs = null,
        serviceId = null,
        uuid = id
    )

    @Test
    fun queueOperationsKeepExpectedOrder() {
        val manager = QueueManager()
        manager.setQueue(listOf(item("a"), item("b"), item("c")), notifyOnly = true)
        manager.moveItem(2, 1)
        manager.removeItemByUuid("a")
        manager.addItem(item("d"))

        assertEquals(listOf("c", "b", "d"), manager.getCurrentQueue().map { it.uuid })
    }

    @Test
    fun shuffleCanRestoreOriginalOrder() {
        val manager = QueueManager()
        manager.setQueue(listOf(item("a"), item("b"), item("c")), notifyOnly = true)
        manager.shuffle("b")

        assertTrue(manager.isShuffled())
        assertEquals("b", manager.getCurrentQueue().first().uuid)

        manager.unshuffle()

        assertFalse(manager.isShuffled())
        assertEquals(listOf("a", "b", "c"), manager.getCurrentQueue().map { it.uuid })
    }

    @Test
    fun extrasAreMerged() {
        val manager = QueueManager()
        manager.setQueue(
            listOf(item("a").copy(extras = mapOf("first" to 1))),
            notifyOnly = true
        )

        manager.updateItemExtras("a", mapOf("second" to 2))

        assertEquals(mapOf("first" to 1, "second" to 2), manager.getCurrentQueue().single().extras)
    }
}
