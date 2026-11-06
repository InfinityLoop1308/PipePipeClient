package org.schabi.newpipe.local.subscription.dialog

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.view.View
import android.widget.CheckBox
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xwray.groupie.OnItemClickListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedContentSelection
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import org.schabi.newpipe.local.subscription.item.PickerSubscriptionItem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FeedGroupDraftContentRulesTest {
    @Test
    fun serializedDraftRestoresContentRulesScreen() {
        assertSerializedDraftRestoresScreen(
            FeedGroupDialog.ScreenState.ContentRulesScreen,
            R.id.default_content_shorts
        )
    }

    @Test
    fun serializedDraftRestoresContentRuleSubscriptionsScreen() {
        assertSerializedDraftRestoresScreen(
            FeedGroupDialog.ScreenState.ContentRuleSubscriptionsScreen,
            R.id.subscriptions_header_title
        )
    }

    private fun assertSerializedDraftRestoresScreen(
        screen: FeedGroupDialog.ScreenState,
        visibleControlId: Int
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val manager = activity.supportFragmentManager
                val dialog = FeedGroupDialog.newInstance().apply {
                    selectedSubscriptions = hashSetOf(42L)
                    wasSubscriptionSelectionChanged = true
                    contentSelectionOverrides = hashMapOf(42L to FeedContentSelection.LIVE.mask)
                    wasContentRulesChanged = true
                    editingContentSelectionMask = FeedContentSelection.LIVE.mask
                    editingRuleOriginalSubscriptions = hashSetOf(42L)
                    editingRuleSelectedSubscriptions = hashSetOf(42L)
                    currentScreen = screen
                }
                dialog.showNow(manager, "serialized-content-draft")
                val screenName = screen.javaClass.simpleName
                assertTrue("Original $screenName controls must be visible",
                    dialog.requireView().findViewById<View>(visibleControlId).isShown)
                val savedBundle = Bundle().apply {
                    putParcelable("dialogState", manager.saveFragmentInstanceState(dialog))
                }
                val bytes = Parcel.obtain().let { parcel ->
                    try {
                        parcel.writeBundle(savedBundle)
                        parcel.marshall()
                    } finally {
                        parcel.recycle()
                    }
                }
                val restoredState = Parcel.obtain().let { parcel ->
                    try {
                        parcel.unmarshall(bytes, 0, bytes.size)
                        parcel.setDataPosition(0)
                        parcel.readBundle(FeedGroupDialog::class.java.classLoader)!!
                            .getParcelable<Fragment.SavedState>("dialogState")!!
                    } finally {
                        parcel.recycle()
                    }
                }
                dialog.dismissNow()
                val restored = FeedGroupDialog.newInstance().apply {
                    setInitialSavedState(restoredState)
                }
                restored.showNow(manager, "restored-content-draft")
                assertTrue("Serialized $screenName must keep its content controls visible",
                    restored.requireView().findViewById<View>(visibleControlId).isShown)
                restored.dismissNow()
            }
        }
    }

    @Test
    fun defaultEditBeforeInitialMembershipDeliveryPreservesPersistedException() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val groupId = 424242L
            val subscription = SubscriptionEntity().apply {
                uid = 42L
                serviceId = 0
                url = "https://example.invalid/channel/42"
                name = "Late channel"
            }
            database.subscriptionDAO().insert(subscription)
            database.feedGroupDAO().insert(FeedGroupEntity(
                groupId, "Late membership", FeedGroupIcon.ALL,
                contentSelection = FeedContentSelection.VIDEOS
            ))
            database.feedGroupDAO().insertSubscriptionsToGroup(listOf(
                FeedGroupSubscriptionEntity(groupId, subscription.uid, FeedContentSelection.LIVE)
            ))
            val group = database.feedGroupDAO().getGroup(groupId).blockingGet()
            val memberships = database.feedGroupDAO().getSubscriptionsForGroup(groupId)
                .firstOrError().blockingGet()

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val dialog = FeedGroupDialog.newInstance(groupId)
                    dialog.showNow(activity.supportFragmentManager, "late-content-rules")
                    val model = FeedGroupDialog::class.java.getDeclaredField("viewModel").apply {
                        isAccessible = true
                    }.get(dialog) as FeedGroupDialogViewModel
                    // Deliver the two initial callbacks in the order a slow membership query allows.
                    model.groupLiveData.removeObservers(dialog.viewLifecycleOwner)
                    model.subscriptionsLiveData.removeObservers(dialog.viewLifecycleOwner)
                    FeedGroupDialogViewModel::class.java.getDeclaredField("feedDatabaseManager")
                        .apply { isAccessible = true }.set(model, FeedDatabaseManager(database))
                    FeedGroupDialog::class.java.getDeclaredMethod(
                        "handleGroup", FeedGroupEntity::class.java
                    ).apply { isAccessible = true }.invoke(dialog, group)

                    val editor = dialog.requireView().findViewById<View>(R.id.content_rules_button)
                    val canEditBeforeMemberships = editor.isEnabled
                    fun addShorts() {
                        editor.performClick()
                        dialog.requireView().findViewById<CheckBox>(R.id.default_content_shorts)
                            .performClick()
                    }
                    if (canEditBeforeMemberships) addShorts()
                    FeedGroupDialog::class.java.getDeclaredMethod(
                        "setupSubscriptionPicker", List::class.java, List::class.java, List::class.java
                    ).apply { isAccessible = true }.invoke(
                        dialog, listOf(PickerSubscriptionItem(subscription)),
                        listOf(PickerSubscriptionItem(subscription)), memberships
                    )
                    // Gating the editor until hydration is also a valid way to preserve the rule.
                    if (!canEditBeforeMemberships) {
                        assertTrue(editor.isEnabled)
                        addShorts()
                    }
                    val confirm = dialog.requireView().findViewById<View>(R.id.confirm_button)
                    confirm.performClick()
                    confirm.performClick()
                }

                val deadline = SystemClock.uptimeMillis() + 5000
                var savedMask: Int?
                do {
                    savedMask = database.feedGroupDAO().getGroup(groupId).blockingGet()
                        ?.contentSelection?.mask
                    if (savedMask == 3) break
                    SystemClock.sleep(20)
                } while (SystemClock.uptimeMillis() < deadline)
                assertEquals("The default edit must be saved before checking the exception", 3, savedMask)
                assertEquals(
                    "A default edit before initial membership delivery must preserve saved exceptions",
                    FeedContentSelection.LIVE,
                    database.feedGroupDAO().getSubscriptionsForGroup(groupId)
                        .firstOrError().blockingGet().single().contentSelectionOverride
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun membershipRemoveAndReaddPreservesExceptionWithoutSearchDelivery() {
        assertMembershipRemoveAndReaddPreservesException(deliverSearchResult = false)
    }

    @Test
    fun membershipRemoveAndReaddPreservesExceptionWithSearchDelivery() {
        assertMembershipRemoveAndReaddPreservesException(deliverSearchResult = true)
    }

    private fun assertMembershipRemoveAndReaddPreservesException(deliverSearchResult: Boolean) {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val groupId = 424244L
            val subscriptions = listOf(42L, 43L).map { id ->
                SubscriptionEntity().apply {
                    uid = id
                    serviceId = 0
                    url = "https://example.invalid/channel/$id"
                    name = "Channel $id"
                }
            }
            subscriptions.forEach { database.subscriptionDAO().insert(it) }
            database.feedGroupDAO().insert(FeedGroupEntity(
                groupId, "Membership rules", FeedGroupIcon.ALL,
                contentSelection = FeedContentSelection.VIDEOS
            ))
            database.feedGroupDAO().insertSubscriptionsToGroup(listOf(
                FeedGroupSubscriptionEntity(groupId, 42L, FeedContentSelection.LIVE),
                FeedGroupSubscriptionEntity(groupId, 43L)
            ))
            val group = database.feedGroupDAO().getGroup(groupId).blockingGet()
            val memberships = database.feedGroupDAO().getSubscriptionsForGroup(groupId)
                .firstOrError().blockingGet()
            val submittedName = "Saved membership rules"

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val dialog = FeedGroupDialog.newInstance(groupId)
                    dialog.showNow(activity.supportFragmentManager, "membership-rule-toggle")
                    val model = FeedGroupDialog::class.java.getDeclaredField("viewModel")
                        .apply { isAccessible = true }.get(dialog) as FeedGroupDialogViewModel
                    model.groupLiveData.removeObservers(dialog.viewLifecycleOwner)
                    model.subscriptionsLiveData.removeObservers(dialog.viewLifecycleOwner)
                    FeedGroupDialogViewModel::class.java.getDeclaredField("feedDatabaseManager")
                        .apply { isAccessible = true }.set(model, FeedDatabaseManager(database))
                    FeedGroupDialog::class.java.getDeclaredMethod(
                        "handleGroup", FeedGroupEntity::class.java
                    ).apply { isAccessible = true }.invoke(dialog, group)
                    val deliverSubscriptions = FeedGroupDialog::class.java.getDeclaredMethod(
                        "setupSubscriptionPicker", List::class.java, List::class.java, List::class.java
                    ).apply { isAccessible = true }
                    val pickerItems = subscriptions.map { PickerSubscriptionItem(it) }
                    deliverSubscriptions.invoke(dialog, pickerItems, pickerItems, memberships)
                    dialog.requireView().findViewById<View>(R.id.select_channel_button).performClick()

                    val listener = FeedGroupDialog::class.java
                        .getDeclaredField("subscriptionPickerItemListener")
                        .apply { isAccessible = true }.get(dialog) as OnItemClickListener
                    val itemView = activity.layoutInflater.inflate(
                        R.layout.picker_subscription_item, null, false
                    )
                    listener.onItemClick(pickerItems[0], itemView)
                    assertEquals(setOf(43L), dialog.selectedSubscriptions)
                    if (deliverSearchResult) {
                        // A search emits the saved memberships alongside the new picker results.
                        deliverSubscriptions.invoke(dialog, pickerItems, pickerItems, memberships)
                    }
                    listener.onItemClick(pickerItems[0], itemView)
                    assertEquals(setOf(42L, 43L), dialog.selectedSubscriptions)

                    val confirm = dialog.requireView().findViewById<View>(R.id.confirm_button)
                    confirm.performClick()
                    dialog.requireView().findViewById<android.widget.EditText>(R.id.group_name_input)
                        .setText(submittedName)
                    confirm.performClick()
                }

                val deadline = SystemClock.uptimeMillis() + 5000
                var savedName: String?
                do {
                    savedName = database.feedGroupDAO().getGroup(groupId).blockingGet()?.name
                    if (savedName == submittedName) break
                    SystemClock.sleep(20)
                } while (SystemClock.uptimeMillis() < deadline)
                assertEquals("The real dialog save must complete", submittedName, savedName)
                val savedMemberships = database.feedGroupDAO().getSubscriptionsForGroup(groupId)
                    .firstOrError().blockingGet()
                assertEquals(setOf(42L, 43L), savedMemberships.map { it.subscriptionId }.toSet())
                assertEquals("Temporary membership changes must preserve the channel exception; " +
                    "search delivery=$deliverSearchResult", FeedContentSelection.LIVE,
                    savedMemberships.single { it.subscriptionId == 42L }.contentSelectionOverride)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun queuedGroupSaveUsesSubmittedMembershipSnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val groupId = 424243L
        for (id in listOf(42L, 43L)) {
            database.subscriptionDAO().insert(SubscriptionEntity().apply {
                uid = id
                serviceId = 0
                url = "https://example.invalid/channel/$id"
                name = "Channel $id"
            })
        }
        database.feedGroupDAO().insert(FeedGroupEntity(groupId, "Before save", FeedGroupIcon.ALL))
        database.feedGroupDAO().insertSubscriptionsToGroup(listOf(
            FeedGroupSubscriptionEntity(groupId, 42L)
        ))

        val transactionStarted = CountDownLatch(1)
        val releaseTransaction = CountDownLatch(1)
        val saveCompleted = CountDownLatch(1)
        val transactionError = AtomicReference<Throwable>()
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var model: FeedGroupDialogViewModel? = null
        val observer = Observer<FeedGroupDialogViewModel.DialogEvent> {
            if (it is FeedGroupDialogViewModel.DialogEvent.SuccessEvent) saveCompleted.countDown()
        }
        val transactionHolder = Thread({
            try {
                database.runInTransaction {
                    transactionStarted.countDown()
                    check(releaseTransaction.await(10, TimeUnit.SECONDS))
                }
            } catch (error: Throwable) {
                transactionError.set(error)
            }
        }, "feed-group-save-gate").apply {
            isDaemon = true
            start()
        }
        try {
            assertTrue("The competing Room transaction must hold the save",
                transactionStarted.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, FeedGroupDialogViewModel.Factory(context, groupId))
                    .get(FeedGroupDialogViewModel::class.java)
                FeedGroupDialogViewModel::class.java.getDeclaredField("feedDatabaseManager")
                    .apply { isAccessible = true }.set(model, FeedDatabaseManager(database))
                model!!.dialogEventLiveData.observeForever(observer)
                val submittedMembership = hashSetOf(42L)
                model!!.updateGroup("Submitted membership", FeedGroupIcon.ALL, submittedMembership,
                    0, FeedContentSelection.ALL, emptyMap())
                // Mutate the caller's set while the real Room transaction keeps its save queued.
                submittedMembership.remove(42L)
                submittedMembership.add(43L)
            }
            releaseTransaction.countDown()
            assertTrue("The queued save must complete", saveCompleted.await(5, TimeUnit.SECONDS))
            assertEquals("Queued saves must preserve submitted membership", setOf(42L), database.feedGroupDAO().getSubscriptionsForGroup(groupId)
                .firstOrError().blockingGet().map { it.subscriptionId }.toSet())
        } finally {
            releaseTransaction.countDown()
            transactionHolder.join(5000)
            instrumentation.runOnMainSync {
                model?.dialogEventLiveData?.removeObserver(observer)
                store.clear()
            }
            database.close()
        }
        assertFalse("The transaction gate must not leave a worker running", transactionHolder.isAlive)
        transactionError.get()?.let { throw AssertionError("The transaction gate failed", it) }
    }

    @Test
    fun equivalentCheckboxOrdersKeepTheSameChannelException() {
        val videosFirst = intArrayOf(R.id.default_content_videos, R.id.default_content_shorts)
        val shortsFirst = videosFirst.reversedArray()
        for (order in listOf(videosFirst, shortsFirst)) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val dialog = FeedGroupDialog().apply {
                        selectedSubscriptions = hashSetOf(42L)
                        wasSubscriptionSelectionChanged = true
                        selectedContentSelectionMask = FeedContentSelection.ALL.mask
                        contentSelectionOverrides = hashMapOf(42L to 6)
                        wasContentRulesChanged = true
                        currentScreen = FeedGroupDialog.ScreenState.ContentRulesScreen
                    }
                    dialog.showNow(activity.supportFragmentManager, "draft-content-rules")
                    for (checkboxId in order) {
                        dialog.requireView().findViewById<CheckBox>(checkboxId).performClick()
                        assertEquals("Unsaved checkbox changes must preserve explicit intent",
                            mapOf(42L to 6), dialog.contentSelectionOverrides)
                    }
                    assertEquals(FeedContentSelection.LIVE.mask, dialog.selectedContentSelectionMask)
                    dialog.dismissNow()
                }
            }
        }
    }
}
