package org.schabi.newpipe.local.feed.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.database.feed.model.FeedContentSelection
import org.schabi.newpipe.database.feed.model.FeedContentType
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class FeedContentClassifierTest {

    @Test
    fun `channel tabs retain their content provenance`() {
        assertEquals(
            FeedContentSelection.VIDEOS,
            FeedContentClassifier.fromChannelTab(ChannelTabs.VIDEOS,
                stream("tab", StreamType.VIDEO_STREAM))
        )
        assertEquals(
            FeedContentSelection.VIDEOS,
            FeedContentClassifier.fromChannelTab(ChannelTabs.TRACKS,
                stream("tab", StreamType.VIDEO_STREAM))
        )
        assertEquals(
            FeedContentSelection.SHORTS,
            FeedContentClassifier.fromChannelTab(ChannelTabs.SHORTS,
                stream("tab", StreamType.VIDEO_STREAM))
        )
        assertEquals(
            FeedContentSelection.LIVE,
            FeedContentClassifier.fromChannelTab(ChannelTabs.LIVESTREAMS,
                stream("tab", StreamType.VIDEO_STREAM))
        )
    }

    @Test
    fun `generic channel tabs classify live metadata but explicit tabs retain provenance`() {
        val live = stream("live-in-videos", StreamType.LIVE_STREAM)
        for (tab in listOf(ChannelTabs.VIDEOS, ChannelTabs.TRACKS)) {
            assertEquals(FeedContentSelection.LIVE,
                FeedContentClassifier.fromChannelTab(tab, live))
        }
        assertEquals(FeedContentSelection.SHORTS,
            FeedContentClassifier.fromChannelTab(ChannelTabs.SHORTS, live))
        assertEquals(FeedContentSelection.LIVE,
            FeedContentClassifier.fromChannelTab(ChannelTabs.LIVESTREAMS,
                stream("archived-live", StreamType.VIDEO_STREAM)))
    }

    @Test
    fun `dedicated feed classifies short form before stream type`() {
        val shortLive = stream("short-live", StreamType.LIVE_STREAM).apply {
            isShortFormContent = true
        }

        assertEquals(
            FeedContentSelection.SHORTS,
            FeedContentClassifier.fromStream(shortLive)
        )
    }

    @Test
    fun `dedicated feed recognizes current and archived live types`() {
        val liveTypes = listOf(
            StreamType.LIVE_STREAM,
            StreamType.AUDIO_LIVE_STREAM,
            StreamType.POST_LIVE_STREAM,
            StreamType.POST_LIVE_AUDIO_STREAM
        )

        liveTypes.forEach { streamType ->
            assertEquals(
                FeedContentSelection.LIVE,
                FeedContentClassifier.fromStream(stream(streamType.name, streamType))
            )
        }
    }

    @Test
    fun `dedicated feed treats remaining streams as videos`() {
        assertEquals(
            FeedContentSelection.VIDEOS,
            FeedContentClassifier.fromStream(stream("video", StreamType.VIDEO_STREAM))
        )
    }

    @Test
    fun `duplicate streams merge all observed content provenance`() {
        val original = stream("same", StreamType.VIDEO_STREAM)
        val duplicate = stream("same", StreamType.VIDEO_STREAM)
        val other = stream("other", StreamType.VIDEO_STREAM)

        val merged = FeedContentClassifier.mergeDuplicates(
            listOf(
                FeedStreamItem(original, FeedContentSelection.VIDEOS),
                FeedStreamItem(duplicate, FeedContentSelection.SHORTS),
                FeedStreamItem(other, FeedContentSelection.LIVE)
            )
        )

        assertEquals(2, merged.size)
        assertEquals(original, merged[0].stream)
        assertEquals(
            FeedContentSelection.of(
                FeedContentType.VIDEOS,
                FeedContentType.SHORTS
            ),
            merged[0].contentSelection
        )
        assertEquals(FeedContentSelection.LIVE, merged[1].contentSelection)
    }

    private fun stream(url: String, streamType: StreamType): StreamInfoItem {
        return StreamInfoItem(0, url, url, streamType)
    }
}
