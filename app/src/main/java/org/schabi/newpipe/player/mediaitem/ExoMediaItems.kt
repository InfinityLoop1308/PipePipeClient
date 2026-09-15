package org.schabi.newpipe.player.mediaitem

import android.net.Uri
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaItem.RequestMetadata
import com.google.android.exoplayer2.MediaMetadata
import java.util.Optional

/**
 * Adapter between the portable [PlayerMediaItem] and ExoPlayer's [MediaItem].
 *
 * This is the only file of the media item model that depends on ExoPlayer. The [PlayerMediaItem]
 * itself is embedded as the platform tag, so `fromMediaItem` can recover it without any wrapper.
 */
object ExoMediaItems {
    @JvmStatic
    fun asExoMediaItem(item: PlayerMediaItem): MediaItem {
        val mediaMetadata = MediaMetadata.Builder()
            .setArtist(item.uploaderName)
            .setDescription(item.title)
            .setDisplayTitle(item.title)
            .setTitle(item.title)
        item.thumbnailUrl?.let { mediaMetadata.setArtworkUri(Uri.parse(it)) }

        val requestMetadata = RequestMetadata.Builder()
            .setMediaUri(Uri.parse(item.url))
            .build()

        return MediaItem.fromUri(item.url)
            .buildUpon()
            // Media ids must be unique within a playlist, so the instance uuid is used, not the
            // content mediaId.
            .setMediaId(item.uuid)
            .setMediaMetadata(mediaMetadata.build())
            .setRequestMetadata(requestMetadata)
            .setTag(item)
            .build()
    }

    @JvmStatic
    fun fromMediaItem(mediaItem: MediaItem?): Optional<PlayerMediaItem> {
        val tag = mediaItem?.localConfiguration?.tag
        return if (tag is PlayerMediaItem) Optional.of(tag) else Optional.empty()
    }
}
