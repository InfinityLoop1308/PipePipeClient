package org.schabi.newpipe.local.subscription.dialog

import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialog.ScreenState
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialog.ScreenState.ContentRuleSubscriptionsScreen
import org.schabi.newpipe.local.subscription.item.PickerSubscriptionItem

internal fun subscriptionPickerPresentationItems(
    subscriptions: List<PickerSubscriptionItem>,
    selectedIds: Set<Long>
): List<PickerSubscriptionItem> = subscriptions.map { subscription ->
    subscription.copy(isSelected = subscription.subscriptionEntity.uid in selectedIds)
}

internal fun subscriptionPickerCandidates(
    screen: ScreenState,
    membershipSubscriptions: List<PickerSubscriptionItem>,
    contentRuleSubscriptions: List<PickerSubscriptionItem>,
    selectedSubscriptionIds: Set<Long>
): List<PickerSubscriptionItem> = when (screen) {
    ContentRuleSubscriptionsScreen -> contentRuleSubscriptions.filter { subscription ->
        subscription.subscriptionEntity.uid in selectedSubscriptionIds
    }
    else -> membershipSubscriptions
}
