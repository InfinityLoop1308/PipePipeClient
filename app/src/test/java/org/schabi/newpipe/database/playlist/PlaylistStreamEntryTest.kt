package org.schabi.newpipe.database.playlist

import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

class PlaylistStreamEntryTest {
    @Test
    fun `toStreamInfoItem preserves paid membership flag`() {
        val stream = StreamEntity(
            serviceId = 0,
            url = "https://example.com/watch?v=paid",
            title = "Paid video",
            streamType = StreamType.VIDEO_STREAM,
            duration = 60,
            uploader = "Channel",
            isPaid = true
        )
        val entry = PlaylistStreamEntry(
            streamEntity = stream,
            progressMillis = 0,
            streamId = 1,
            joinIndex = 0
        )

        assertTrue(entry.toStreamInfoItem().requiresMembership())
    }
}
