package org.schabi.newpipe.local.subscription.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialog.ScreenState.ContentRuleSubscriptionsScreen
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialog.ScreenState.SubscriptionsPickerScreen
import org.schabi.newpipe.local.subscription.item.PickerSubscriptionItem

class SubscriptionPickerPresentationTest {

    @Test
    fun `rendered items are fresh copies with selection from the supplied ids`() {
        val unselectedSource = pickerItem(1L, isSelected = false)
        val selectedSource = pickerItem(2L, isSelected = true)

        val rendered = subscriptionPickerPresentationItems(
            listOf(unselectedSource, selectedSource),
            selectedIds = setOf(1L)
        )

        assertNotSame(unselectedSource, rendered[0])
        assertNotSame(selectedSource, rendered[1])
        assertTrue(rendered[0].isSelected)
        assertFalse(rendered[1].isSelected)
        assertFalse(unselectedSource.isSelected)
        assertTrue(selectedSource.isSelected)
    }

    @Test
    fun `successive renders derive selection without retaining the previous overlay`() {
        val sources = listOf(pickerItem(1L), pickerItem(2L))

        val firstRender = subscriptionPickerPresentationItems(sources, selectedIds = setOf(1L))
        val secondRender = subscriptionPickerPresentationItems(sources, selectedIds = setOf(2L))

        assertTrue(firstRender[0].isSelected)
        assertFalse(firstRender[1].isSelected)
        assertFalse(secondRender[0].isSelected)
        assertTrue(secondRender[1].isSelected)
        assertNotSame(firstRender[0], secondRender[0])
        assertNotSame(firstRender[1], secondRender[1])
        assertFalse(sources[0].isSelected)
        assertFalse(sources[1].isSelected)
    }

    @Test
    fun `content rule candidates include unsaved draft members from full catalog`() {
        val persistedMembershipCandidates = listOf(pickerItem(1L))
        val fullCatalog = listOf(pickerItem(1L), pickerItem(2L), pickerItem(3L))

        val candidates = subscriptionPickerCandidates(
            screen = ContentRuleSubscriptionsScreen,
            membershipSubscriptions = persistedMembershipCandidates,
            contentRuleSubscriptions = fullCatalog,
            selectedSubscriptionIds = setOf(1L, 2L)
        )

        assertEquals(listOf(1L, 2L), candidates.map { it.subscriptionEntity.uid })
    }

    @Test
    fun `membership candidates retain the membership picker query`() {
        val persistedMembershipCandidates = listOf(pickerItem(1L))
        val fullCatalog = listOf(pickerItem(1L), pickerItem(2L), pickerItem(3L))

        val candidates = subscriptionPickerCandidates(
            screen = SubscriptionsPickerScreen,
            membershipSubscriptions = persistedMembershipCandidates,
            contentRuleSubscriptions = fullCatalog,
            selectedSubscriptionIds = setOf(1L, 2L)
        )

        assertEquals(listOf(1L), candidates.map { it.subscriptionEntity.uid })
    }

    private fun pickerItem(id: Long, isSelected: Boolean = false): PickerSubscriptionItem {
        return PickerSubscriptionItem(
            SubscriptionEntity().apply { uid = id },
            isSelected
        )
    }
}
