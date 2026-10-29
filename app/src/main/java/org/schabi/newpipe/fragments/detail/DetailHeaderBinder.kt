package org.schabi.newpipe.fragments.detail

import android.content.Context
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.squareup.picasso.Callback
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.ktx.animateRotation
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.PicassoHelper
import org.schabi.newpipe.util.dearrow.DeArrowBinder
import org.schabi.newpipe.util.dearrow.DeArrowRow
import java.util.concurrent.TimeUnit

/** Tag of the images loaded for the detail page, to cancel them when another stream loads. */
internal const val PICASSO_VIDEO_DETAILS_TAG = "PICASSO_VIDEO_DETAILS_TAG"

/**
 * The top part of the detail page: thumbnail, title, uploader, counts, the controls' visibility
 * and the playback position.
 */
class DetailHeaderBinder(
    private val fragment: Fragment,
    private val binding: FragmentVideoDetailBinding,
    /** Called when the height of the header changed, which moves the tab layout. */
    private val onHeightChanged: () -> Unit,
    private val onError: (ErrorInfo) -> Unit,
) {
    private val thumbnail get() = binding.detailThumbnailRootLayout
    private val content get() = binding.detailContentRootLayout
    private val context: Context get() = fragment.requireContext()
    private val disposables = CompositeDisposable()
    private var positionSubscriber: Disposable? = null

    /** URL of the stream shown, null while loading. */
    var shownUrl: String? = null
        private set

    fun showLoading(serviceId: Int, url: String?, title: String) {
        shownUrl = null
        // if the data is already cached, the VISIBLE -> INVISIBLE -> VISIBLE transition is useless
        if (!ExtractorHelper.isCached(serviceId, url, InfoItem.InfoType.STREAM)) {
            content.detailContentRootHiding.visibility = View.INVISIBLE
        }
        thumbnail.detailThumbnailPlayButton.animate(false, 50)
        thumbnail.detailDurationView.animate(false, 100)
        thumbnail.detailPositionView.animate(false, 100)
        thumbnail.positionView.animate(false, 50)

        val ellipsize = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.auto_ellipsize_key), false)
        content.detailVideoTitleView.text = title
        content.detailVideoTitleView.maxLines = if (ellipsize) 1 else 10
        content.detailVideoTitleView.animate(true, 0)

        content.detailToggleSecondaryControlsView.visibility = View.GONE
        content.detailTitleRootLayout.isClickable = false
        content.detailSecondaryControlPanel.visibility = View.GONE

        PicassoHelper.cancelTag(PICASSO_VIDEO_DETAILS_TAG)
        thumbnail.detailThumbnailImageView.setImageBitmap(null)
        content.detailSubChannelThumbnailView.setImageBitmap(null)
    }

    fun showInfo(info: StreamInfo, playQueue: PlayQueue?) {
        shownUrl = info.url
        thumbnail.detailThumbnailPlayButton.animate(true, 200)
        content.detailVideoTitleView.text = info.name

        bindUploader(info)
        bindCounts(info)
        bindDuration(info)

        content.detailTitleRootLayout.isClickable = true
        content.detailToggleSecondaryControlsView.rotation = 0f
        content.detailToggleSecondaryControlsView.visibility = View.VISIBLE
        content.detailSecondaryControlPanel.visibility = View.GONE

        updateProgressInfo(info, playQueue)
        loadImages(info)
        // Swap in DeArrow's honest title and thumbnail, if the user opted in. This must come
        // after loadImages(): the original URL is deliberately not handed over, so the binder only
        // ever paints a replacement over the top and never blanks this view.
        DeArrowBinder.bind(DeArrowRow.fromDetail(info.serviceId, info.url),
            content.detailVideoTitleView, thumbnail.detailThumbnailImageView)
        ExtractorHelper.showMetaInfoInTextView(info.metaInfo, content.detailMetaInfoTextView,
            content.detailMetaInfoSeparator, disposables)

        val live = info.streamType == StreamType.LIVE_STREAM
        content.detailControlsDownload.visibility =
            if (live || info.streamType == StreamType.AUDIO_LIVE_STREAM) View.GONE
            else View.VISIBLE
        val noVideoStreams = !live && info.videoStreams.isEmpty()
            && info.videoOnlyStreams.isEmpty()
        content.detailControlsPopup.visibility = if (noVideoStreams) View.GONE else View.VISIBLE
        thumbnail.detailThumbnailPlayButton.setImageResource(
            if (noVideoStreams) R.drawable.ic_headset_shadow else R.drawable.ic_play_arrow_shadow)

        showStreamErrors(info)
    }

    fun showContent() {
        content.detailContentRootHiding.visibility = View.VISIBLE
    }

    fun showError() {
        shownUrl = null
        thumbnail.detailThumbnailImageView.setImageDrawable(
            AppCompatResources.getDrawable(context, R.drawable.not_available_monkey))
        thumbnail.detailThumbnailImageView.animate(false, 0, execOnEnd = {
            thumbnail.detailThumbnailImageView.animate(true, 500)
        })
    }

    /** Shows the position bar again once the shown stream plays. */
    fun showPositionIfHidden() {
        if (thumbnail.positionView.alpha != 1f) {
            thumbnail.positionView.animate(true, 100)
            thumbnail.detailPositionView.animate(true, 100)
        }
    }

    fun showPlaybackProgress(progressMillis: Long, durationMillis: Long) {
        val progress = TimeUnit.MILLISECONDS.toSeconds(progressMillis).toInt()
        val duration = TimeUnit.MILLISECONDS.toSeconds(durationMillis).toInt()
        val positionView = thumbnail.positionView
        positionView.max = duration
        // Only animate big jumps, as the animation costs CPU
        if (kotlin.math.abs(positionView.progress - progress) > 2) {
            positionView.setProgressAnimated(progress)
        } else {
            positionView.progress = progress
        }
        thumbnail.detailPositionView.text = Localization.getDurationString(progress.toLong())
        if (positionView.visibility != View.VISIBLE) {
            positionView.animate(true, 100)
            thumbnail.detailPositionView.animate(true, 100)
        }
    }

    /** Expands the title and shows the secondary controls, or the other way around. */
    fun toggleTitleAndSecondaryControls() {
        val expand = content.detailSecondaryControlPanel.visibility == View.GONE
        content.detailVideoTitleView.maxLines = if (expand) 10 else 1
        content.detailToggleSecondaryControlsView.animateRotation(
            Player.DEFAULT_CONTROLS_DURATION.toLong(), if (expand) 180 else 0)
        content.detailSecondaryControlPanel.visibility = if (expand) View.VISIBLE else View.GONE
        onHeightChanged()
    }

    /** Hides everything below the title, or shows it again. */
    fun toggleDetails() {
        if (content.detailContentRootHiding.visibility == View.GONE) {
            content.detailVideoTitleView.maxLines = 10
            content.detailToggleSecondaryControlsView.animateRotation(
                Player.DEFAULT_CONTROLS_DURATION.toLong(), 180)
            content.detailContentRootHiding.visibility = View.VISIBLE
        } else {
            content.detailVideoTitleView.maxLines = 1
            content.detailContentRootHiding.visibility = View.GONE
        }
        onHeightChanged()
    }

    fun release() {
        positionSubscriber?.dispose()
        positionSubscriber = null
        disposables.clear()
    }

    private fun bindUploader(info: StreamInfo) {
        content.detailSubChannelThumbnailView.visibility = View.GONE
        when {
            !info.subChannelName.isNullOrEmpty() -> bindUploaderAndSubChannel(info)
            !info.uploaderName.isNullOrEmpty() -> bindUploaderAsSubChannel(info)
            else -> {
                content.detailUploaderTextView.visibility = View.GONE
                content.detailUploaderThumbnailView.visibility = View.GONE
            }
        }
        val buddy = AppCompatResources.getDrawable(context, R.drawable.buddy)
        content.detailSubChannelThumbnailView.setImageDrawable(buddy)
        content.detailUploaderThumbnailView.setImageDrawable(buddy)
    }

    private fun bindUploaderAsSubChannel(info: StreamInfo) {
        content.detailSubChannelTextView.text = info.uploaderName
        content.detailSubChannelTextView.visibility = View.VISIBLE
        content.detailSubChannelTextView.isSelected = true
        if (info.uploaderSubscriberCount > -1) {
            content.detailUploaderTextView.text =
                Localization.shortSubscriberCount(context, info.uploaderSubscriberCount)
            content.detailUploaderTextView.visibility = View.VISIBLE
        } else {
            content.detailUploaderTextView.visibility = View.GONE
        }
    }

    private fun bindUploaderAndSubChannel(info: StreamInfo) {
        content.detailSubChannelTextView.text = info.subChannelName
        content.detailSubChannelTextView.visibility = View.VISIBLE
        content.detailSubChannelTextView.isSelected = true
        content.detailSubChannelThumbnailView.visibility = View.VISIBLE

        val subText = buildList {
            if (!info.uploaderName.isNullOrEmpty()) {
                add(context.getString(R.string.video_detail_by, info.uploaderName))
            }
            if (info.uploaderSubscriberCount > -1) {
                add(Localization.shortSubscriberCount(context, info.uploaderSubscriberCount))
            }
        }.joinToString(Localization.DOT_SEPARATOR)
        if (subText.isNotEmpty()) {
            content.detailUploaderTextView.text = subText
            content.detailUploaderTextView.visibility = View.VISIBLE
            content.detailUploaderTextView.isSelected = true
        } else {
            content.detailUploaderTextView.visibility = View.GONE
        }
    }

    private fun bindCounts(info: StreamInfo) {
        if (info.viewCount >= 0) {
            content.detailViewCountView.text = when (info.streamType) {
                StreamType.AUDIO_LIVE_STREAM ->
                    Localization.listeningCount(context, info.viewCount)
                StreamType.LIVE_STREAM ->
                    Localization.localizeWatchingCount(context, info.viewCount)
                else -> Localization.localizeViewCount(context, info.viewCount)
            }
            content.detailViewCountView.visibility = View.VISIBLE
        } else {
            content.detailViewCountView.visibility = View.GONE
        }

        if (info.dislikeCount == -1L && info.likeCount == -1L) {
            content.detailThumbsDownImgView.visibility = View.VISIBLE
            content.detailThumbsUpImgView.visibility = View.VISIBLE
            content.detailThumbsUpCountView.visibility = View.GONE
            content.detailThumbsDownCountView.visibility = View.GONE
            content.detailThumbsDisabledView.visibility = View.VISIBLE
            return
        }
        val showDislikes = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.show_dislike_key), false)
        bindCount(info.dislikeCount, showDislikes, content.detailThumbsDownCountView,
            content.detailThumbsDownImgView)
        bindCount(info.likeCount, true, content.detailThumbsUpCountView,
            content.detailThumbsUpImgView)
        content.detailThumbsDisabledView.visibility = View.GONE
    }

    private fun bindCount(count: Long, show: Boolean,
                          countView: android.widget.TextView, imageView: View) {
        if (count >= 0 && show) {
            countView.text = Localization.shortCount(context, count)
            countView.visibility = View.VISIBLE
            imageView.visibility = View.VISIBLE
        } else {
            countView.visibility = View.GONE
            imageView.visibility = View.GONE
        }
    }

    private fun bindDuration(info: StreamInfo) {
        val durationView = thumbnail.detailDurationView
        when {
            info.duration > 0 -> {
                durationView.text = Localization.getDurationString(info.duration)
                durationView.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.duration_background_color))
                durationView.animate(true, 100)
            }
            info.streamType == StreamType.LIVE_STREAM -> {
                durationView.setText(R.string.duration_live)
                durationView.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.live_duration_background_color))
                durationView.animate(true, 100)
            }
            else -> durationView.visibility = View.GONE
        }
    }

    private fun loadImages(info: StreamInfo) {
        PicassoHelper.loadScaledDownThumbnail(context, info.thumbnailUrl)
            .tag(PICASSO_VIDEO_DETAILS_TAG)
            .into(thumbnail.detailThumbnailImageView, object : Callback {
                override fun onSuccess() = Unit

                override fun onError(e: Exception) {
                    onError(ErrorInfo(e, UserAction.LOAD_IMAGE, info.thumbnailUrl, info))
                }
            })
        PicassoHelper.loadAvatar(info.subChannelAvatarUrl).tag(PICASSO_VIDEO_DETAILS_TAG)
            .into(content.detailSubChannelThumbnailView)
        PicassoHelper.loadAvatar(info.uploaderAvatarUrl).tag(PICASSO_VIDEO_DETAILS_TAG)
            .into(content.detailUploaderThumbnailView)
    }

    /** Shows the saved playback position, from the watch history or else from the queue. */
    private fun updateProgressInfo(info: StreamInfo, playQueue: PlayQueue?) {
        positionSubscriber?.dispose()
        positionSubscriber = null
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val resumeEnabled = prefs.getBoolean(context.getString(R.string.enable_watch_history_key),
            true) && prefs.getBoolean(context.getString(R.string.enable_playback_resume_key), true)
        if (!resumeEnabled) {
            val item = playQueue?.item
            val recovery = item?.let { playQueue.getRecoveryPosition(playQueue.index) }
            if (recovery == null || recovery == PlayQueue.RECOVERY_UNSET) {
                thumbnail.positionView.visibility = View.INVISIBLE
                thumbnail.detailPositionView.visibility = View.GONE
            } else {
                showPlaybackProgress(recovery, item.duration * 1000)
                thumbnail.positionView.animate(true, 500)
                thumbnail.detailPositionView.animate(true, 500)
            }
            return
        }
        positionSubscriber = HistoryRecordManager(context).loadStreamState(info)
            .subscribeOn(Schedulers.io())
            .onErrorComplete()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ state ->
                showPlaybackProgress(state.progressMillis, info.duration * 1000)
                thumbnail.positionView.animate(true, 500)
                thumbnail.detailPositionView.animate(true, 500)
            }, { e ->
                if (MainActivity.DEBUG) {
                    e.printStackTrace()
                }
            }, {
                thumbnail.positionView.visibility = View.GONE
                thumbnail.detailPositionView.visibility = View.GONE
            })
    }

    private fun showStreamErrors(info: StreamInfo) {
        // Bandcamp fan pages are not supported yet and throw a ContentNotSupportedException.
        // That is not an error and thus not shown to the user.
        info.errors.removeAll {
            it is ContentNotSupportedException && it.message == "Fan pages are not supported"
        }
        if (info.errors.isNotEmpty()) {
            onError(ErrorInfo(info.errors, UserAction.REQUESTED_STREAM, info.url, info))
        }
    }
}
