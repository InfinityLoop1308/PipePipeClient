package project.pipepipe.app.mediasource

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.schabi.newpipe.extractor.stream.StreamInfo
import project.pipepipe.app.platform.PlatformMediaItem

object MediaItemFactory {
    fun fromStreamInfo(streamInfo: StreamInfo, uuid: String? = null): PlatformMediaItem {
        StreamInfoRepository.put(streamInfo)
        return PlatformMediaItem(
            mediaId = streamInfo.url,
            title = streamInfo.name,
            artist = streamInfo.uploaderName,
            artworkUrl = streamInfo.thumbnailUrl,
            durationMs = streamInfo.duration.takeIf { it > 0 }?.times(1000),
            serviceId = streamInfo.serviceId,
            uuid = uuid ?: java.util.UUID.randomUUID().toString()
        )
    }

    fun toMediaItem(item: PlatformMediaItem): MediaItem {
        val extras = Bundle().apply {
            item.serviceId?.let { putInt(KEY_SERVICE_ID, it) }
            putString(KEY_UUID, item.uuid)
        }
        return MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(Uri.EMPTY)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setArtworkUri(item.artworkUrl?.let(Uri::parse))
                    .setDurationMs(item.durationMs)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    fun fromMediaItem(item: MediaItem): PlatformMediaItem = PlatformMediaItem(
        mediaId = item.mediaId,
        title = item.mediaMetadata.title?.toString(),
        artist = item.mediaMetadata.artist?.toString(),
        artworkUrl = item.mediaMetadata.artworkUri?.toString(),
        durationMs = item.mediaMetadata.durationMs,
        serviceId = item.mediaMetadata.extras?.getInt(KEY_SERVICE_ID),
        uuid = item.mediaMetadata.extras?.getString(KEY_UUID)
            ?: java.util.UUID.randomUUID().toString()
    )

    const val KEY_SERVICE_ID = "KEY_SERVICE_ID"
    const val KEY_UUID = "KEY_UUID"
}
