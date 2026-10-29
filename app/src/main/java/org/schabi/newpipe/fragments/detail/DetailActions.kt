package org.schabi.newpipe.fragments.detail

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding
import org.schabi.newpipe.download.DownloadDialog
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.dialog.PlaylistDialog
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.VideoDetailPlayerCrasher
import org.schabi.newpipe.sleep.SleepTimerService
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.PermissionHelper
import org.schabi.newpipe.util.external_communication.KoreUtils
import org.schabi.newpipe.util.external_communication.ShareUtils

/** The click handling of the detail page's header and controls. */
class DetailActions(
    private val fragment: Fragment,
    private val binding: FragmentVideoDetailBinding,
    private val header: DetailHeaderBinder,
    private val host: Host,
) {
    interface Host {
        val serviceId: Int
        val currentInfo: StreamInfo?
        val isLoading: Boolean
        val player: Player?

        fun openBackgroundPlayer(append: Boolean)

        fun openPopupPlayer(append: Boolean)

        /** The thumbnail was clicked: play in the main player. */
        fun playInMainPlayer()
    }

    private val content get() = binding.detailContentRootLayout
    private val activity get() = fragment.requireActivity()
    private val disposables = CompositeDisposable()

    fun setup() {
        val context = fragment.requireContext()
        content.detailControlsPlayWithKodi.visibility =
            if (KoreUtils.shouldShowPlayWithKodi(context, host.serviceId)) View.VISIBLE
            else View.GONE
        val showCrashButton = MainActivity.DEBUG
            && PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.show_crash_the_player_key), false)
        content.detailControlsCrashThePlayer.visibility =
            if (showCrashButton) View.VISIBLE else View.GONE
        if (DeviceUtils.isTv(context)) {
            // remove the ripple effects of the controls
            val transparent = ContextCompat.getColor(context, R.color.transparent_background_color)
            listOf(content.detailControlsPlaylistAppend, content.detailControlsBackground,
                content.detailControlsPopup, content.detailControlsDownload,
                content.detailControlsShare, content.detailControlsOpenInBrowser,
                content.detailControlsPlayWithKodi)
                .forEach { it.setBackgroundColor(transparent) }
        }

        val toggle = View.OnClickListener { header.toggleTitleAndSecondaryControls() }
        content.detailTitleRootLayout.setOnClickListener(toggle)
        content.detailToggleSecondaryControlsView.setOnClickListener(toggle)
        val copyTitle = longClick {
            ShareUtils.copyToClipboard(context, content.detailVideoTitleView.text.toString())
        }
        content.detailTitleRootLayout.setOnLongClickListener(copyTitle)
        content.detailToggleSecondaryControlsView.setOnLongClickListener(
            longClick { header.toggleDetails() })

        content.detailUploaderRootLayout.setOnClickListener { onUploaderClicked() }
        content.detailUploaderRootLayout.setOnLongClickListener(longClick { info ->
            if (info.subChannelUrl.isNullOrEmpty()) {
                Log.w(TAG, "Can't open parent channel because we got no parent channel URL")
            } else {
                openChannel(info.uploaderUrl, info.uploaderName)
            }
        })
        binding.detailThumbnailRootLayout.root.setOnClickListener { host.playInMainPlayer() }

        content.detailControlsBackground.setOnClickListener { host.openBackgroundPlayer(false) }
        content.detailControlsBackground.setOnLongClickListener(
            longClick { host.openBackgroundPlayer(true) })
        content.detailControlsPopup.setOnClickListener { host.openPopupPlayer(false) }
        content.detailControlsPopup.setOnLongClickListener(
            longClick { host.openPopupPlayer(true) })
        content.detailControlsPlaylistAppend.setOnClickListener {
            host.currentInfo?.let { showPlaylistDialog(listOf(StreamEntity(it))) }
        }
        content.detailControlsPlaylistAppend.setOnLongClickListener(longClick { info ->
            showPlaylistDialog(info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .filter { it.uploaderName != null }
                .map(::StreamEntity))
        })
        content.detailControlsDownload.setOnClickListener {
            if (PermissionHelper.checkStoragePermissions(activity,
                    PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE)) {
                openDownloadDialog()
            }
        }
        content.detailControlsDownload.setOnLongClickListener(
            longClick { NavigationHelper.openDownloads(activity) })
        content.detailControlsShare.setOnClickListener {
            host.currentInfo?.let {
                ShareUtils.shareText(context, it.name, it.url, it.thumbnailUrl)
            }
        }
        content.detailControlsOpenInBrowser.setOnClickListener {
            host.currentInfo?.let { ShareUtils.openUrlInBrowser(context, it.url) }
        }
        content.detailControlsStartSleepTimer.setOnClickListener {
            if (host.currentInfo != null) {
                startSleepTimer()
            }
        }
        content.detailControlsPlayWithKodi.setOnClickListener {
            host.currentInfo?.let { playWithKodi(it) }
        }
        if (MainActivity.DEBUG) {
            content.detailControlsCrashThePlayer.setOnClickListener {
                VideoDetailPlayerCrasher.onCrashThePlayer(context, host.player,
                    fragment.layoutInflater)
            }
        }
    }

    fun openDownloadDialog() {
        val info = host.currentInfo ?: return
        try {
            DownloadDialog.newInstance(activity, info)
                .show(activity.supportFragmentManager, "downloadDialog")
        } catch (e: Exception) {
            ErrorUtil.showSnackbar(activity, ErrorInfo(e, UserAction.DOWNLOAD_OPEN_DIALOG,
                "Showing download dialog", info))
        }
    }

    fun openChannel(url: String?, name: String?) {
        val info = host.currentInfo ?: return
        try {
            NavigationHelper.openChannelFragment(fragment.parentFragmentManager, info.serviceId,
                url, name.orEmpty())
        } catch (e: Exception) {
            ErrorUtil.showUiErrorSnackbar(fragment, "Opening channel fragment", e)
        }
    }

    fun release() {
        disposables.clear()
    }

    /** Long clicks only do something once the stream is loaded. */
    private fun longClick(action: (StreamInfo) -> Unit) = View.OnLongClickListener {
        val info = host.currentInfo
        if (host.isLoading || info == null) {
            return@OnLongClickListener false
        }
        action(info)
        true
    }

    private fun onUploaderClicked() {
        val info = host.currentInfo ?: return
        if (info.subChannelUrl.isNullOrEmpty()) {
            if (!info.uploaderUrl.isNullOrEmpty()) {
                openChannel(info.uploaderUrl, info.uploaderName)
            }
        } else {
            openChannel(info.subChannelUrl, info.subChannelName)
        }
    }

    private fun showPlaylistDialog(streams: List<StreamEntity>) {
        val fm = fragment.parentFragmentManager
        disposables.add(PlaylistDialog.createCorrespondingDialog(fragment.context, streams) {
            it.show(fm, TAG)
        })
    }

    private fun startSleepTimer() {
        val context = fragment.requireContext()
        val minutes = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.sleep_timer_length_key), "15")!!.toInt()
        val intent = Intent(context, SleepTimerService::class.java)
            .setAction(SleepTimerService.ACTION_START_TIMER)
            .putExtra("timeInMillis", minutes * 60000)
        activity.startService(intent)
    }

    private fun playWithKodi(info: StreamInfo) {
        val context = fragment.requireContext()
        try {
            NavigationHelper.playWithKore(context, Uri.parse(info.url))
        } catch (e: Exception) {
            if (MainActivity.DEBUG) {
                Log.i(TAG, "Failed to start kore", e)
            }
            KoreUtils.showInstallKoreDialog(context)
        }
    }

    private companion object {
        const val TAG = "DetailActions"
    }
}
