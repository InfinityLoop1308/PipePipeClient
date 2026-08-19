package org.schabi.newpipe.info_list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistStreamPresentationTest {
    @Test
    fun `paid playlist stream hides duration and progress`() {
        val presentation = buildPlaylistStreamPresentation(
            isPaid = true,
            durationSeconds = 60,
            progressMillis = 30_000,
            durationText = "1:00",
            paidText = "Paid"
        )

        assertEquals("Paid", presentation.durationText)
        assertTrue(presentation.showPaidBadge)
        assertNull(presentation.progress)
    }
}
