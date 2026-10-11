package org.schabi.newpipe.util.dearrow

import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.util.PicassoHelper

/**
 * Applies DeArrow's title and thumbnail to a row.
 *
 * Two rules keep this from corrupting a recycled list:
 *
 * 1. The uploader's title and thumbnail are what the caller binds first; a DeArrow lookup can
 *    never delay a bind or leave a row blank. A replacement is written only while the row still
 *    shows the same video, enforced by a per-bind token on the title view.
 * 2. The thumbnail is decided in one place, once. Earlier designs fetched a community frame and
 *    a stored frame as two independent chains writing the same ImageView, which raced and let a
 *    generic frame overwrite the community's.
 *
 * A failure at any point leaves the row showing what YouTube gave it.
 */
object DeArrowBinder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** A list row's thumbnail is a few hundred pixels wide; do not cache more than it needs. */
    private const val LIST_THUMBNAIL_WIDTH = 480

    /** The detail header is full width, so it keeps the server's own size. */
    private const val DETAIL_THUMBNAIL_WIDTH = 1280

    /**
     * Binds a row's title and thumbnail.
     *
     * @param row           what the row is showing
     * @param titleView     the row's title view, already showing the uploader's title
     * @param thumbnailView the row's thumbnail view, or null for rows with no image
     */
    @JvmStatic
    fun bind(row: DeArrowRow, titleView: TextView, thumbnailView: ImageView?) {
        (titleView.getTag(R.id.dearrow_binding) as? Binding)?.cancel()
        Binding(row, titleView, thumbnailView).attach()
    }

    /**
     * Whether text on screen is the DeArrow title this stream is already showing.
     *
     * A caller that compares the displayed title against the original to decide "is this already
     * drawn?" would otherwise mistake a successful replacement for a stale view and redraw the
     * page on every check.
     */
    @JvmStatic
    fun isShowingTitle(context: Context, info: StreamInfo?, displayed: String?): Boolean {
        if (info == null || displayed == null) return false
        if (info.serviceId != ServiceList.YouTube.serviceId) return false
        val videoId = DeArrowVideoId.fromUrl(info.url) ?: return false
        val rawTitle = DeArrowRepository.cached(videoId)?.title ?: return false
        val config = DeArrowSettings.read(context)
        if (!config.enabled || !config.replaceTitles) return false
        val shown = if (config.autoFormatTitles) DeArrowText.autoFormat(rawTitle) else rawTitle
        return displayed == shown
    }

    private class Binding(
        private val row: DeArrowRow,
        private val titleView: TextView,
        private val thumbnailView: ImageView?,
    ) {
        private var job: Job? = null

        /**
         * True when this binder owns the row's image: it has the uploader's URL and may blank it
         * to a placeholder. False for the video detail page, whose thumbnail the caller loads.
         */
        private var ownsImage = false

        /** True when thumbnails are being replaced at all. */
        private var replaceImage = false

        fun attach() {
            titleView.setTag(R.id.dearrow_binding, this)
            val config = DeArrowSettings.read(titleView.context)
            val active = config.enabled && row.videoId != null
                && row.serviceId == ServiceList.YouTube.serviceId
            if (!active) {
                loadOriginal()
                return
            }

            ownsImage = thumbnailView != null && row.ownsThumbnail
            replaceImage = config.replaceThumbnails
            if (ownsImage) {
                if (replaceImage) showPlaceholder() else loadOriginal()
            }
            if (!config.replaceTitles && !replaceImage) return

            job = scope.launch { apply(config) }
        }

        fun cancel() {
            job?.cancel()
            job = null
        }

        private suspend fun apply(config: DeArrowConfig) {
            val videoId = row.videoId ?: return
            val branding = DeArrowRepository.load(videoId)
            if (!isCurrent()) return

            if (config.replaceTitles) {
                branding.title?.let { raw ->
                    titleView.text = if (config.autoFormatTitles) {
                        DeArrowText.autoFormat(raw)
                    } else {
                        raw
                    }
                }
            }
            if (!replaceImage) return

            val image = resolveImage(videoId, branding, config,
                if (row.ownsThumbnail) LIST_THUMBNAIL_WIDTH else DETAIL_THUMBNAIL_WIDTH)
            if (!isCurrent()) return
            when {
                image != null -> paint(image)
                // Nothing to show: keep the placeholder only if the user asked to never fall
                // back to the uploader's thumbnail.
                ownsImage && config.originalFallback -> loadOriginal()
            }
        }

        /**
         * The community's frame if there is one, then a stored frame, then a frame the server
         * renders on demand. The last covers a live broadcast and an upload too fresh to have
         * been processed yet, both of which have no stored frame.
         */
        private suspend fun resolveImage(
            videoId: String,
            branding: DeArrowBranding,
            config: DeArrowConfig,
            targetWidth: Int,
        ): Bitmap? {
            branding.thumbnailTimestamp?.let { timestamp ->
                DeArrowImages.loadCommunityImage(
                    DeArrowApi.thumbnailUrl(videoId, timestamp), targetWidth)
                    ?.let { return it }
            }
            if (!config.frameFallback) return null
            DeArrowImages.loadStoredFrame(videoId)?.let { return it }
            return DeArrowImages.loadRenderedFrame(videoId, targetWidth)
        }

        private fun isCurrent(): Boolean = titleView.getTag(R.id.dearrow_binding) === this

        private fun paint(frame: Bitmap) {
            val view = thumbnailView ?: return
            // Writing a bitmap does not cancel an in-flight load for the same view, which would
            // paint over it when it finishes.
            PicassoHelper.cancelRequest(view)
            view.setImageBitmap(frame)
        }

        private fun showPlaceholder() {
            val view = thumbnailView ?: return
            PicassoHelper.cancelRequest(view)
            view.setImageResource(R.drawable.dummy_thumbnail)
        }

        private fun loadOriginal() {
            val view = thumbnailView ?: return
            PicassoHelper.cancelRequest(view)
            val url = row.originalThumbnailUrl
            if (url != null) {
                PicassoHelper.loadScaledDownThumbnail(view.context, url).into(view)
            } else {
                view.setImageResource(R.drawable.dummy_thumbnail)
            }
        }
    }
}
