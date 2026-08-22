package org.schabi.newpipe.local.subscription

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.fragment.app.Fragment
import org.schabi.newpipe.local.subscription.services.SubscriptionsExportService
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.KEY_MODE
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.KEY_VALUE
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.PREVIOUS_EXPORT_MODE
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard
import org.schabi.newpipe.streams.io.StoredFileHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shares the subscription JSON import/export flow between fragments.
 *
 * This helper must be created before its fragment reaches the created state because it registers
 * activity result launchers.
 */
class SubscriptionsImportExportHelper(private val fragment: Fragment) {
    @Suppress("unused")
    private val detailsCoordinator = SubscriptionImportDetailsCoordinator(fragment)

    private val requestExportLauncher =
        fragment.registerForActivityResult(StartActivityForResult(), this::requestExportResult)
    private val requestImportLauncher =
        fragment.registerForActivityResult(StartActivityForResult(), this::requestImportResult)

    fun importSubscriptions() {
        NoFileManagerSafeGuard.launchSafe(
            requestImportLauncher,
            StoredFileHelper.getPicker(fragment.requireContext(), JSON_MIME_TYPE),
            TAG,
            fragment.requireContext()
        )
    }

    fun exportSubscriptions() {
        val date = SimpleDateFormat("yyyyMMddHHmm", Locale.ENGLISH).format(Date())
        val exportName = "newpipe_subscriptions_$date.json"

        NoFileManagerSafeGuard.launchSafe(
            requestExportLauncher,
            StoredFileHelper.getNewPicker(
                fragment.requireContext(),
                exportName,
                JSON_MIME_TYPE,
                null
            ),
            TAG,
            fragment.requireContext()
        )
    }

    private fun requestExportResult(result: ActivityResult) {
        val outputUri = result.data?.data
        if (outputUri != null && result.resultCode == Activity.RESULT_OK) {
            fragment.requireContext().startService(
                Intent(fragment.requireContext(), SubscriptionsExportService::class.java)
                    .putExtra(SubscriptionsExportService.KEY_FILE_PATH, outputUri)
            )
        }
    }

    private fun requestImportResult(result: ActivityResult) {
        val inputUri = result.data?.data
        if (inputUri != null && result.resultCode == Activity.RESULT_OK) {
            fragment.requireContext().startService(
                Intent(fragment.requireContext(), SubscriptionsImportService::class.java)
                    .putExtra(KEY_MODE, PREVIOUS_EXPORT_MODE)
                    .putExtra(KEY_VALUE, inputUri)
            )
        }
    }

    private companion object {
        const val JSON_MIME_TYPE = "application/json"
        val TAG: String = SubscriptionsImportExportHelper::class.java.simpleName
    }
}
