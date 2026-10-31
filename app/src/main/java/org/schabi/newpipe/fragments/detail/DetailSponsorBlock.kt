package org.schabi.newpipe.fragments.detail

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockAction
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockCategory
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockExtractorHelper
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockSegment
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.fragments.list.sponsorblock.SponsorBlockFragment
import org.schabi.newpipe.fragments.list.sponsorblock.SponsorBlockFragmentListener
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.util.SponsorBlockHelper
import org.schabi.newpipe.util.SponsorBlockMode

/** Connects the SponsorBlock tab with the player: marking, previewing and submitting segments. */
class DetailSponsorBlock(
    private val fragment: Fragment,
    private val host: Host,
) : SponsorBlockFragmentListener {

    interface Host {
        val player: Player?
        val currentInfo: StreamInfo?
        val sponsorBlockFragment: SponsorBlockFragment?
    }

    private var submitSubscriber: Disposable? = null

    fun onProgress(progressMillis: Int) {
        host.sponsorBlockFragment?.setCurrentProgress(progressMillis)
    }

    fun onMetadataUpdate() {
        val mode = SponsorBlockHelper.getSkippingMode(fragment.requireContext())
        host.sponsorBlockFragment?.setSponsorBlockMode(mode)
    }

    fun dispose() {
        submitSubscriber?.dispose()
        submitSubscriber = null
    }

    override fun onSkippingEnabledChanged(newValue: Boolean) {
        host.player?.setSponsorBlockMode(
            if (newValue) SponsorBlockMode.ENABLED else SponsorBlockMode.DISABLED)
    }

    override fun onRequestNewPendingSegment(startTime: Int, endTime: Int) {
        val info = host.currentInfo ?: return
        val player = host.player ?: return
        info.removeSponsorBlockSegment(PENDING_UUID)
        info.addSponsorBlockSegment(SponsorBlockSegment(PENDING_UUID, startTime.toDouble(),
            endTime.toDouble(), SponsorBlockCategory.PENDING, SponsorBlockAction.SKIP,
            info.serviceId))
        player.onMarkSeekbarRequested(info)
        host.sponsorBlockFragment?.refreshSponsorBlockSegments()
    }

    override fun onRequestClearPendingSegment() {
        val info = host.currentInfo ?: return
        val player = host.player ?: return
        info.removeSponsorBlockSegment(PENDING_UUID)
        player.onMarkSeekbarRequested(info)
        host.sponsorBlockFragment?.refreshSponsorBlockSegments()
    }

    override fun onRequestSubmitPendingSegment(newSegment: SponsorBlockSegment) {
        val info = host.currentInfo ?: return
        if (host.player == null) {
            return
        }
        val context = fragment.requireContext()
        val userId = SponsorBlockHelper.getUserId(context)
        submitSubscriber?.dispose()
        submitSubscriber = Single.fromCallable {
            SponsorBlockExtractorHelper.submitSponsorBlockSegment(info, newSegment,
                SponsorBlockExtractorHelper.getApiUrl(info), userId)
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ response ->
                val responseCode = response.responseCode()
                // 409: all good, but the request timed out
                if (responseCode != 200 && responseCode != 409) {
                    val message = response.responseMessage().ifEmpty { "Error $responseCode" }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    return@subscribe
                }
                info.removeSponsorBlockSegment(PENDING_UUID)
                info.addSponsorBlockSegment(newSegment)
                host.player?.onMarkSeekbarRequested(info)
                host.sponsorBlockFragment?.clearPendingSegment()
                AlertDialog.Builder(context)
                    .setMessage(R.string.sponsor_block_upload_success_message)
                    .setPositiveButton(R.string.ok) { d, _ -> d.dismiss() }
                    .show()
            }, { throwable ->
                if (throwable !is NullPointerException) {
                    ErrorUtil.showSnackbar(context, ErrorInfo(throwable, UserAction.USER_REPORT,
                        "Submit SponsorBlock segment"))
                }
            })
    }

    override fun onSeekToRequested(positionMillis: Long) {
        host.player?.seekTo(positionMillis)
    }

    private companion object {
        /** UUID of the segment being marked, not submitted yet. */
        const val PENDING_UUID = "TEMP"
    }
}
