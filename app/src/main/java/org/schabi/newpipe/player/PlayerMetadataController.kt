package org.schabi.newpipe.player

import android.util.Log
import com.google.android.exoplayer2.Player.Events
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.mediaitem.ExoMediaItems
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import java.util.Optional

/**
 * Owns what is being played right now: the media item the play queue is at and the metadata that
 * was resolved for it, plus everything that has to happen when that metadata arrives.
 *
 * [onEvents] is the ExoPlayer entry point and [updateMetadataWith] the one the playback listeners
 * use; [onMetadataChanged] is what fans a new [StreamInfo] out to the rest of the player: the
 * zoom and the display mode that belonged to the previous video, the thumbnail, the quality and
 * speed menus, the bullet comments, the notification, the media session, the listeners, the queue
 * and the SponsorBlock segments. [Player] keeps a delegate for each of those names and still
 * exposes the two items, since most of the other controllers read them.
 */
class PlayerMetadataController(private val player: Player) {

    /** The item the play queue is currently at, as reported by the playback synchronization. */
    var currentItem: PlayerMediaItem? = null

    /**
     * The metadata ExoPlayer is playing. It lags behind [currentItem] while the stream info of
     * the new item is still being resolved, which is why the two are kept apart.
     */
    var currentMetadata: PlayerMediaItem? = null

    fun clearCurrentMediaItems() {
        currentItem = null
        currentMetadata = null
    }

    //////////////////////////////////////////////////////////////////////////
    // ExoPlayer entry point
    //////////////////////////////////////////////////////////////////////////

    /**
     * <p>Listens for event or state changes on ExoPlayer. When any event happens, we check for
     * changes in the currently-playing metadata and update the encapsulating [Player]. Downstream
     * listeners are also informed.</p>
     *
     * <p>When the renewed metadata contains any error, it is reported as a notification. This is
     * done because not all source resolution errors are a
     * [com.google.android.exoplayer2.PlaybackException], which ExoPlayer also captures but which
     * stop the playback instead.</p>
     */
    fun onEvents(exoPlayer: com.google.android.exoplayer2.Player, events: Events) {
        ExoMediaItems.fromMediaItem(exoPlayer.currentMediaItem).ifPresent { tag ->
            // Compared by identity on purpose: PlayerMediaItem.equals() is the uuid, while the tag
            // of the current window can be another instance carrying the same one.
            if (tag === currentMetadata) {
                return@ifPresent // we still have the same metadata, no need to do anything
            }
            val previousInfo = Optional.ofNullable(currentMetadata)
                .flatMap { item: PlayerMediaItem -> item.maybeStreamInfo }.orElse(null)
            currentMetadata = tag

            if (tag.errors.isNotEmpty()) {
                // new errors might have been added even if previousInfo == tag.getMaybeStreamInfo()
                val errorInfo = ErrorInfo(
                    tag.errors,
                    UserAction.PLAY_STREAM,
                    "Loading failed for [" + tag.title + "]: " + tag.url,
                    tag.serviceId
                )
                ErrorUtil.createNotification(player.context, errorInfo)
            }

            tag.maybeStreamInfo.ifPresent { info ->
                if (Player.DEBUG) {
                    Log.d(Player.TAG, "ExoPlayer - onEvents() update stream info: " + info.name)
                }
                if (previousInfo == null || previousInfo.url != info.url) {
                    // only update with the new stream info if it has actually changed
                    updateMetadataWith(info)
                }
            }
        }
    }

    //////////////////////////////////////////////////////////////////////////
    // Metadata fan out
    //////////////////////////////////////////////////////////////////////////

    fun updateMetadataWith(streamInfo: StreamInfo) {
        if (player.exoPlayerIsNull()) {
            return
        }

        player.autoQueueController.maybeAutoQueueNextStream(streamInfo, false)
        onMetadataChanged(streamInfo)
        NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(player, true)
    }

    private fun onMetadataChanged(info: StreamInfo) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "Playback - onMetadataChanged() called, playing: " + info.name)
        }

        // Zoom belongs to the current video, matching the transient behavior of the official app.
        player.gestureController.resetPinchZoom()
        player.menuController.resetDisplayModeForNewVideo()
        // So does a live resolution override: the tracks of the next item are unrelated.
        player.liveQualityController.resetForNewVideo()

        player.thumbnailController.initThumbnail(info.thumbnailUrl)
        player.historyController.registerStreamViewed()
        player.layoutController.updateStreamRelatedViews()
        player.layoutController.showHideKodiButton()
        // TODO: bullet comments may be reset unexpectedly for round play streams
        player.bulletCommentsController.init()
        player.bulletCommentsController.start()

        player.layoutController.updateMetadataViews(info)

        player.progressController.resetPreviewThumbnails(info.previewFrames)

        NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(player, false)

        player.mediaSessionManager?.setPlayer(player)

        player.notifyMetadataUpdateToListeners()

        player.tracksController.onAudioTracksChanged()

        player.queueController.onMetadataChanged(info)

        player.onMarkSeekbarRequested(info)
    }

    //////////////////////////////////////////////////////////////////////////
    // What is being played, for the share, notification and media session paths
    //////////////////////////////////////////////////////////////////////////

    val videoUrl: String
        get() = currentMetadata?.url
            ?: player.context.getString(R.string.unknown_content)

    val videoTitle: String
        get() = currentMetadata?.title
            ?: player.context.getString(R.string.unknown_content)

    val uploaderName: String
        get() = currentMetadata?.getUploaderName()
            ?: player.context.getString(R.string.unknown_content)

    val videoUrlAtCurrentTime: String
        get() {
            val metadata = currentMetadata
            val timeSeconds = player.binding.playbackSeekBar.progress / 1000
            var url = videoUrl
            if (!player.isLive && timeSeconds >= 0 && metadata != null
                && metadata.serviceId == ServiceList.YouTube.serviceId
            ) {
                // Timestamp doesn't make sense in a live stream so drop it
                url += ("&t=" + timeSeconds)
            }
            return url
        }
}
