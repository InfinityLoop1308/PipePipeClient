package project.pipepipe.app.mediasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamInfo

class MediaItemFactoryTest {
    @Test
    fun streamInfoIsRegisteredAndConverted() {
        val streamInfo = StreamInfo(1, "id", "https://example.com/video", "title")

        val item = MediaItemFactory.fromStreamInfo(streamInfo, "uuid")

        assertEquals(streamInfo.url, item.mediaId)
        assertEquals(streamInfo.name, item.title)
        assertEquals("uuid", item.uuid)
        assertSame(streamInfo, StreamInfoRepository.get(streamInfo.url))
    }
}
