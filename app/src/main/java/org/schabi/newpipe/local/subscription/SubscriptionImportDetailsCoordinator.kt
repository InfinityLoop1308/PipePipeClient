package org.schabi.newpipe.local.subscription

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.IMPORT_COMPLETE_ACTION
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.KEY_INSERTED_SUBSCRIPTION_IDS

class SubscriptionImportDetailsCoordinator(private val fragment: Fragment) :
    DefaultLifecycleObserver {

    private val broadcastManager by lazy {
        LocalBroadcastManager.getInstance(fragment.requireContext())
    }
    private var receiverRegistered = false

    private val importCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val subscriptionIds = intent.getLongArrayExtra(KEY_INSERTED_SUBSCRIPTION_IDS)
            if (subscriptionIds != null && subscriptionIds.isNotEmpty()) {
                SubscriptionDetailsConfirmationDialog.show(fragment, subscriptionIds)
            }
        }
    }

    init {
        fragment.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        if (!receiverRegistered) {
            broadcastManager.registerReceiver(
                importCompleteReceiver,
                IntentFilter(IMPORT_COMPLETE_ACTION)
            )
            receiverRegistered = true
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        unregisterReceiver()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unregisterReceiver()
    }

    private fun unregisterReceiver() {
        if (receiverRegistered) {
            broadcastManager.unregisterReceiver(importCompleteReceiver)
            receiverRegistered = false
        }
    }
}
