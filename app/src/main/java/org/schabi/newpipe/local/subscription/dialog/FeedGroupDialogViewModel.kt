package org.schabi.newpipe.local.subscription.dialog

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.processors.BehaviorProcessor
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.database.feed.model.FeedContentSelection
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.local.subscription.item.PickerSubscriptionItem

class FeedGroupDialogViewModel(
    applicationContext: Context,
    private val groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
    initialQuery: String = "",
    initialShowOnlyUngrouped: Boolean = false
) : ViewModel() {

    private var feedDatabaseManager: FeedDatabaseManager = FeedDatabaseManager(applicationContext)
    private var subscriptionManager = SubscriptionManager(applicationContext)

    private var filterSubscriptions = BehaviorProcessor.create<String>()
    private var toggleShowOnlyUngrouped = BehaviorProcessor.create<Boolean>()

    private var membershipSubscriptionsFlowable = Flowable
        .combineLatest(
            filterSubscriptions.startWithItem(initialQuery),
            toggleShowOnlyUngrouped.startWithItem(initialShowOnlyUngrouped)
        ) { t1: String, t2: Boolean -> Filter(t1, t2) }
        .distinctUntilChanged()
        .switchMap { (query, showOnlyUngrouped) ->
            subscriptionManager.getSubscriptions(groupId, query, showOnlyUngrouped)
        }.map { list -> list.map { PickerSubscriptionItem(it) } }

    private var contentRuleSubscriptionsFlowable = filterSubscriptions
        .startWithItem(initialQuery)
        .distinctUntilChanged()
        .switchMap { query ->
            subscriptionManager.getSubscriptions(
                FeedGroupEntity.GROUP_ALL_ID,
                query,
                false
            )
        }.map { list -> list.map { PickerSubscriptionItem(it) } }

    private val mutableGroupLiveData = MutableLiveData<FeedGroupEntity>()
    private val mutableSubscriptionsLiveData = MutableLiveData<SubscriptionsState>()
    private val mutableDialogEventLiveData = MutableLiveData<DialogEvent>()
    val groupLiveData: LiveData<FeedGroupEntity> = mutableGroupLiveData
    val subscriptionsLiveData: LiveData<SubscriptionsState> = mutableSubscriptionsLiveData
    val dialogEventLiveData: LiveData<DialogEvent> = mutableDialogEventLiveData

    private var actionProcessingDisposable: Disposable? = null

    private var feedGroupDisposable = feedDatabaseManager.getGroup(groupId)
        .subscribeOn(Schedulers.io())
        .subscribe(mutableGroupLiveData::postValue)

    private var subscriptionsDisposable = Flowable
        .combineLatest(
            membershipSubscriptionsFlowable,
            contentRuleSubscriptionsFlowable,
            feedDatabaseManager.subscriptionsForGroup(groupId)
        ) { membershipSubscriptions: List<PickerSubscriptionItem>,
            contentRuleSubscriptions: List<PickerSubscriptionItem>,
            memberships: List<FeedGroupSubscriptionEntity> ->
            SubscriptionsState(
                membershipSubscriptions,
                contentRuleSubscriptions,
                memberships
            )
        }
        .subscribeOn(Schedulers.io())
        .subscribe(mutableSubscriptionsLiveData::postValue)

    override fun onCleared() {
        super.onCleared()
        actionProcessingDisposable?.dispose()
        subscriptionsDisposable.dispose()
        feedGroupDisposable.dispose()
    }

    fun createGroup(
        name: String,
        selectedIcon: FeedGroupIcon,
        selectedSubscriptions: Set<Long>,
        contentSelection: FeedContentSelection,
        contentSelectionOverrides: Map<Long, FeedContentSelection>
    ) {
        doAction(
            feedDatabaseManager.createGroupWithContentRules(
                name,
                selectedIcon,
                contentSelection,
                selectedSubscriptions,
                contentSelectionOverrides
            )
        )
    }

    fun updateGroup(
        name: String,
        selectedIcon: FeedGroupIcon,
        selectedSubscriptions: Set<Long>,
        sortOrder: Long,
        contentSelection: FeedContentSelection,
        contentSelectionOverrides: Map<Long, FeedContentSelection>
    ) {
        doAction(
            feedDatabaseManager.updateGroupWithContentRules(
                FeedGroupEntity(
                    groupId,
                    name,
                    selectedIcon,
                    sortOrder,
                    contentSelection
                ),
                selectedSubscriptions,
                contentSelectionOverrides
            )
        )
    }

    fun deleteGroup() {
        doAction(feedDatabaseManager.deleteGroup(groupId))
    }

    private fun doAction(completable: Completable) {
        if (actionProcessingDisposable == null) {
            mutableDialogEventLiveData.value = DialogEvent.ProcessingEvent

            actionProcessingDisposable = completable
                .subscribeOn(Schedulers.io())
                .subscribe { mutableDialogEventLiveData.postValue(DialogEvent.SuccessEvent) }
        }
    }

    fun filterSubscriptionsBy(query: String) {
        filterSubscriptions.onNext(query)
    }

    fun clearSubscriptionsFilter() {
        filterSubscriptions.onNext("")
    }

    fun toggleShowOnlyUngrouped(showOnlyUngrouped: Boolean) {
        toggleShowOnlyUngrouped.onNext(showOnlyUngrouped)
    }

    sealed class DialogEvent {
        object ProcessingEvent : DialogEvent()
        object SuccessEvent : DialogEvent()
    }

    data class Filter(val query: String, val showOnlyUngrouped: Boolean)

    data class SubscriptionsState(
        val membershipSubscriptions: List<PickerSubscriptionItem>,
        val contentRuleSubscriptions: List<PickerSubscriptionItem>,
        val memberships: List<FeedGroupSubscriptionEntity>
    )

    class Factory(
        private val context: Context,
        private val groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
        private val initialQuery: String = "",
        private val initialShowOnlyUngrouped: Boolean = false
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FeedGroupDialogViewModel(
                context.applicationContext,
                groupId, initialQuery, initialShowOnlyUngrouped
            ) as T
        }
    }
}
