package org.schabi.newpipe.local.subscription

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import org.schabi.newpipe.R
import org.schabi.newpipe.local.subscription.services.SubscriptionsFetchService
import org.schabi.newpipe.local.subscription.services.SubscriptionsFetchService.KEY_SUBSCRIPTION_IDS
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage

class SubscriptionDetailsConfirmationDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        assureCorrectAppLanguage(context)
        val subscriptionIds = requireArguments().getLongArray(KEY_SUBSCRIPTION_IDS)
            ?: throw IllegalStateException("Subscription IDs are missing")

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.fetch_subscription_details_title)
            .setMessage(R.string.fetch_subscription_details_message)
            .setNegativeButton(R.string.skip, null)
            .setPositiveButton(R.string.fetch) { _, _ ->
                requireContext().startService(
                    Intent(requireContext(), SubscriptionsFetchService::class.java)
                        .putExtra(KEY_SUBSCRIPTION_IDS, subscriptionIds)
                )
            }
            .create()
    }

    companion object {
        private const val TAG = "SubscriptionDetailsConfirmationDialog"

        fun show(fragment: Fragment, subscriptionIds: LongArray) {
            val fragmentManager = fragment.parentFragmentManager
            if (fragmentManager.findFragmentByTag(TAG) != null) return

            SubscriptionDetailsConfirmationDialog().apply {
                arguments = Bundle().apply {
                    putLongArray(KEY_SUBSCRIPTION_IDS, subscriptionIds)
                }
            }.show(fragmentManager, TAG)
        }
    }
}
