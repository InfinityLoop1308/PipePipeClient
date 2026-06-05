package project.pipepipe.app

import org.schabi.newpipe.player.playqueue.PlayQueue
import project.pipepipe.app.platform.PlatformMediaItem

object LegacyPlayQueueAdapter {
    fun convert(queue: PlayQueue): List<PlatformMediaItem> = queue.streams.map {
        PlatformMediaItem(
            mediaId = it.url,
            title = it.title,
            artist = it.uploader,
            artworkUrl = it.thumbnailUrl,
            durationMs = it.duration.takeIf { duration -> duration > 0 }?.times(1000),
            serviceId = it.serviceId
        )
    }
}
