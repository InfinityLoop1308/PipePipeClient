package org.schabi.newpipe.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.Log
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.PicassoHelper

/**
 * Owns the thumbnail of a [Player].
 *
 * The player-owned copy of the current thumbnail, the asynchronous loading through Picasso and the
 * scaling of the end screen thumbnail all live here. [Player] only keeps a delegate getter with
 * the same name, so the media session and the notification are unaffected.
 */
class PlayerThumbnailController(private val player: Player) {

    private var currentThumbnail: Bitmap? = null

    fun initThumbnail(url: String?) {
        if (Player.DEBUG) {
            Log.d(Player.TAG, "Thumbnail - initThumbnail() called with url = ["
                + (url ?: "null") + "]")
        }
        if (isNullOrEmpty(url)) {
            return
        }

        // scale down the notification thumbnail for performance
        PicassoHelper.loadScaledDownThumbnail(player.context, url, true).into(object : Target {
            override fun onBitmapLoaded(bitmap: Bitmap, from: Picasso.LoadedFrom) {
                if (Player.DEBUG) {
                    Log.d(
                        Player.TAG, "Thumbnail - onLoadingComplete() called with: url = [$url"
                            + "], loadedImage = [$bitmap -> " + bitmap.width + "x"
                            + bitmap.height + "], from = [" + from + "]"
                    )
                }

                // Picasso owns the bitmap passed to Targets and may reuse it for other requests.
                // Keep a player-owned copy because the thumbnail is also retained by the media
                // session and notification after this callback returns.
                currentThumbnail = if (bitmap.isRecycled) {
                    Log.w(Player.TAG, "Ignoring a recycled player thumbnail")
                    null
                } else {
                    bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                }
                NotificationUtil.getInstance()
                    .createNotificationIfNeededAndUpdate(player, false)
                // there is a new thumbnail, so changed the end screen thumbnail, too.
                updateEndScreenThumbnail()
            }

            override fun onBitmapFailed(e: Exception, errorDrawable: Drawable) {
                Log.e(Player.TAG, "Thumbnail - onBitmapFailed() called with: url = [$url]", e)
                currentThumbnail = null
                NotificationUtil.getInstance()
                    .createNotificationIfNeededAndUpdate(player, false)
            }

            override fun onPrepareLoad(placeHolderDrawable: Drawable) {
                if (Player.DEBUG) {
                    Log.d(Player.TAG, "Thumbnail - onLoadingStarted() called with: url = [$url]")
                }
            }
        })
    }

    /**
     * Scale the player audio / end screen thumbnail down if necessary.
     *
     * This is necessary when the thumbnail's height is larger than the device's height
     * and thus is enlarging the player's height
     * causing the bottom playback controls to be out of the visible screen.
     */
    fun updateEndScreenThumbnail() {
        val thumbnail = currentThumbnail ?: return

        val endScreenHeight = calculateMaxEndScreenThumbnailHeight()

        val endScreenBitmap = Bitmap.createScaledBitmap(
            thumbnail,
            (thumbnail.width / (thumbnail.height / endScreenHeight)).toInt(),
            endScreenHeight.toInt(),
            true
        )

        if (Player.DEBUG) {
            Log.d(
                Player.TAG, "Thumbnail - updateEndScreenThumbnail() called with: "
                    + "currentThumbnail = [$thumbnail], "
                    + thumbnail.width + "x" + thumbnail.height
                    + ", scaled end screen height = " + endScreenHeight
                    + ", scaled end screen width = " + endScreenBitmap.width
            )
        }

        player.binding.endScreen.setImageBitmap(endScreenBitmap)
    }

    fun getThumbnail(): Bitmap {
        if (currentThumbnail == null) {
            currentThumbnail = BitmapFactory.decodeResource(
                player.context.resources, R.drawable.dummy_thumbnail
            )
        }
        return currentThumbnail!!
    }

    /**
     * Calculate the maximum allowed height for the end screen, following the rules documented on
     * [Player.updateEndScreenThumbnail]. The result is the smaller of the thumbnail height and the
     * screen height (minus the video info height on TVs and tablets in landscape).
     */
    private fun calculateMaxEndScreenThumbnailHeight(): Float {
        // ensure that screenHeight is initialized and thus not 0
        player.updateScreenSize()
        val screenHeight = player.screenHeight
        val thumbnail = currentThumbnail!!

        return if (DeviceUtils.isTv(player.context) && !player.isFullscreen) {
            val videoInfoHeight = DeviceUtils.dpToPx(85, player.context)
                + DeviceUtils.spToPx(16, player.context)
            minOf(thumbnail.height.toFloat(), screenHeight - videoInfoHeight)
        } else if (DeviceUtils.isTablet(player.context)
            && player.service.isLandscape && !player.isFullscreen
        ) {
            val videoInfoHeight = DeviceUtils.dpToPx(85, player.context)
                + DeviceUtils.spToPx(15, player.context)
            minOf(thumbnail.height.toFloat(), screenHeight - videoInfoHeight)
        } else { // fullscreen player: max height is the device height
            minOf(thumbnail.height.toFloat(), screenHeight)
        }
    }
}
