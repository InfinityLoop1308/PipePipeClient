package org.schabi.newpipe.local.feed.service

import org.schabi.newpipe.database.feed.model.FeedContentSelection
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

data class FeedStreamItem(
    val stream: StreamInfoItem,
    val contentSelection: FeedContentSelection
)

object FeedContentClassifier {

    fun fromChannelTab(channelTab: String, stream: StreamInfoItem): FeedContentSelection {
        return when (channelTab) {
            ChannelTabs.SHORTS -> FeedContentSelection.SHORTS
            ChannelTabs.LIVESTREAMS -> FeedContentSelection.LIVE
            else -> fromStream(stream)
        }
    }

    fun fromStream(stream: StreamInfoItem): FeedContentSelection {
        return when {
            stream.isShortFormContent -> FeedContentSelection.SHORTS
            stream.streamType in LIVE_STREAM_TYPES -> FeedContentSelection.LIVE
            else -> FeedContentSelection.VIDEOS
        }
    }

    fun mergeDuplicates(items: List<FeedStreamItem>): List<FeedStreamItem> {
        val mergedItems = linkedMapOf<StreamKey, FeedStreamItem>()

        items.forEach { item ->
            val key = StreamKey(item.stream.serviceId, item.stream.url)
            val existingItem = mergedItems[key]
            mergedItems[key] = if (existingItem == null) {
                item
            } else {
                existingItem.copy(
                    contentSelection = existingItem.contentSelection.union(item.contentSelection)
                )
            }
        }

        return mergedItems.values.toList()
    }

    private data class StreamKey(val serviceId: Int, val url: String)

    private val LIVE_STREAM_TYPES = setOf(
        StreamType.LIVE_STREAM,
        StreamType.AUDIO_LIVE_STREAM,
        StreamType.POST_LIVE_STREAM,
        StreamType.POST_LIVE_AUDIO_STREAM
    )
}
