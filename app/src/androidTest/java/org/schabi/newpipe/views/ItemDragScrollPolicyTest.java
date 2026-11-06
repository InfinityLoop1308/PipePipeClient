package org.schabi.newpipe.views;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ItemDragScrollPolicyTest {
    private static final int ITEM_SIZE_PX = 120;

    @Test
    public void keepsSpeedsBelowTheCapUnchanged() {
        assertEquals(0, cap(0, 120.0f));
        assertEquals(1, cap(1, 120.0f));
        assertEquals(10, cap(10, 120.0f));
        assertEquals(-10, cap(-10, 120.0f));
    }

    @Test
    public void capsBothScrollDirections() {
        assertEquals(11, cap(100, 120.0f));
        assertEquals(-11, cap(-100, 120.0f));
    }

    @Test
    public void normalizesTheCapForCommonRefreshRates() {
        assertEquals(22, cap(100, 60.0f));
        assertEquals(15, cap(100, 90.0f));
        assertEquals(11, cap(100, 120.0f));
    }

    @Test
    public void fallsBackToSixtyHertzForInvalidRefreshRates() {
        assertEquals(22, cap(100, 0.0f));
        assertEquals(22, cap(100, -1.0f));
        assertEquals(22, cap(100, Float.NaN));
        assertEquals(22, cap(100, Float.POSITIVE_INFINITY));
    }

    @Test
    public void fallsBackToOnePixelForInvalidItemSizes() {
        assertEquals(1, ItemDragScrollPolicy.capScrollSpeed(100, 0, 1, 60.0f));
        assertEquals(1, ItemDragScrollPolicy.capScrollSpeed(100, -1, 1, 60.0f));
    }

    @Test
    public void scalesTheCapWithTheDraggedItemSize() {
        assertEquals(11, ItemDragScrollPolicy.capScrollSpeed(
                100, ITEM_SIZE_PX, 1, 120.0f));
        assertEquals(22, ItemDragScrollPolicy.capScrollSpeed(
                100, ITEM_SIZE_PX * 2, 1, 120.0f));
    }

    @Test
    public void accountsForMultipleItemsInEachGridRow() {
        assertEquals(11, ItemDragScrollPolicy.capScrollSpeed(
                100, ITEM_SIZE_PX * 2, 2, 120.0f));
        assertEquals(11, ItemDragScrollPolicy.capScrollSpeed(
                100, ITEM_SIZE_PX, 0, 120.0f));
    }

    @Test
    public void compressesTheInitialAndroidXRampToHalfASecond() {
        assertEquals(0L, initialRampElapsed(-1L));
        assertEquals(0L, initialRampElapsed(0L));
        assertEquals(500L, initialRampElapsed(125L));
        assertEquals(1_000L, initialRampElapsed(250L));
        assertEquals(1_996L, initialRampElapsed(499L));
        assertEquals(2_000L, initialRampElapsed(500L));
        assertEquals(2_000L, initialRampElapsed(5_000L));
    }

    @Test
    public void restartsTheInitialRampAfterChangingDirection() {
        final ItemDragScrollPolicy policy = new ItemDragScrollPolicy();

        assertEquals(0L, policy.initialRampElapsedForAndroidX(0L, -1));
        assertEquals(2_000L, policy.initialRampElapsedForAndroidX(500L, -1));
        assertEquals(0L, policy.initialRampElapsedForAndroidX(700L, 1));
        assertEquals(1_000L, policy.initialRampElapsedForAndroidX(950L, 1));
    }

    @Test
    public void restartsTheInitialRampWhenEdgeScrollingRestarts() {
        final ItemDragScrollPolicy policy = new ItemDragScrollPolicy();

        assertEquals(0L, policy.initialRampElapsedForAndroidX(0L, 1));
        assertEquals(2_000L, policy.initialRampElapsedForAndroidX(500L, 1));
        assertEquals(0L, policy.initialRampElapsedForAndroidX(0L, 1));
        assertEquals(1_000L, policy.initialRampElapsedForAndroidX(250L, 1));
    }

    @Test
    public void explicitResetRestartsTheInitialRamp() {
        final ItemDragScrollPolicy policy = new ItemDragScrollPolicy();

        assertEquals(0L, policy.initialRampElapsedForAndroidX(0L, 1));
        assertEquals(2_000L, policy.initialRampElapsedForAndroidX(500L, 1));

        policy.resetScroll();

        assertEquals(0L, policy.initialRampElapsedForAndroidX(500L, 1));
    }

    @Test
    public void keepsTheNormalCapDuringTheExpeditedDelay() {
        final ItemDragScrollPolicy policy = new ItemDragScrollPolicy();

        assertEquals(11, apply(policy, 100, 0L));
        assertEquals(11, apply(policy, 100,
                ItemDragScrollPolicy.EXPEDITED_SCROLL_DELAY_MS - 1L));
        assertEquals(11, apply(policy, 100,
                ItemDragScrollPolicy.EXPEDITED_SCROLL_DELAY_MS));
    }

    @Test
    public void smoothlyRampsToTheExpeditedCap() {
        final ItemDragScrollPolicy policy = new ItemDragScrollPolicy();
        final long halfwayThroughRamp = ItemDragScrollPolicy.EXPEDITED_SCROLL_DELAY_MS
                + ItemDragScrollPolicy.EXPEDITED_SCROLL_RAMP_MS / 2L;
        final long rampComplete = ItemDragScrollPolicy.EXPEDITED_SCROLL_DELAY_MS
                + ItemDragScrollPolicy.EXPEDITED_SCROLL_RAMP_MS;

        assertEquals(11, apply(policy, 100, 0L));
        assertEquals(21, apply(policy, 100, halfwayThroughRamp));
        assertEquals(30, apply(policy, 100, rampComplete));
        assertEquals(30, apply(policy, 100, rampComplete + 10_000L));
    }

    @Test
    public void normalizesTheExpeditedCapForCommonRefreshRates() {
        assertEquals(60, reachExpeditedCap(60.0f));
        assertEquals(40, reachExpeditedCap(90.0f));
        assertEquals(30, reachExpeditedCap(120.0f));
    }

    @Test
    public void restartsTheDelayAfterDroppingBelowTheNormalCap() {
        final ItemDragScrollPolicy policy = expeditedPolicy();

        assertEquals(10, apply(policy, 10, 3_000L));
        assertEquals(11, apply(policy, 100, 3_100L));
        assertEquals(11, apply(policy, 100,
                3_100L + ItemDragScrollPolicy.EXPEDITED_SCROLL_DELAY_MS));
    }

    @Test
    public void restartsTheDelayAfterChangingDirection() {
        final ItemDragScrollPolicy policy = expeditedPolicy();

        assertEquals(-11, apply(policy, -100, 3_000L));
        assertEquals(-11, apply(policy, -100,
                3_000L + ItemDragScrollPolicy.EXPEDITED_SCROLL_DELAY_MS));
    }

    @Test
    public void restartsTheDelayWhenEdgeScrollingRestarts() {
        final ItemDragScrollPolicy policy = expeditedPolicy();

        assertEquals(11, apply(policy, 100, 0L));
        assertEquals(11, apply(policy, 100,
                ItemDragScrollPolicy.EXPEDITED_SCROLL_DELAY_MS));
    }

    @Test
    public void explicitResetRestartsTheDelay() {
        final ItemDragScrollPolicy policy = expeditedPolicy();

        policy.resetExpeditedScroll();

        assertEquals(11, apply(policy, 100, 3_000L));
        assertEquals(11, apply(policy, 100,
                3_000L + ItemDragScrollPolicy.EXPEDITED_SCROLL_DELAY_MS));
    }

    @Test
    public void keepsPointersWithinTheListUnchanged() {
        assertEquals(100.0f, clampPointerY(100.0f), 0.0f);
        assertEquals(170.0f, clampPointerY(170.0f), 0.0f);
    }

    @Test
    public void clampsPointersToThePaddedListEdges() {
        assertEquals(20.0f, clampPointerY(-100.0f), 0.0f);
        assertEquals(170.0f, clampPointerY(500.0f), 0.0f);
    }

    @Test
    public void immediatelyTracksAReversingPointerOnceItReentersTheList() {
        assertEquals(170.0f, clampPointerY(500.0f), 0.0f);
        assertEquals(160.0f, clampPointerY(160.0f), 0.0f);
    }

    private static int cap(final int standardSpeed, final float refreshRateHz) {
        return ItemDragScrollPolicy.capScrollSpeed(
                standardSpeed, ITEM_SIZE_PX, 1, refreshRateHz);
    }

    private static long initialRampElapsed(final long elapsedMs) {
        return ItemDragScrollPolicy.initialRampElapsedForAndroidX(elapsedMs);
    }

    private static int apply(final ItemDragScrollPolicy policy,
                             final int standardSpeed,
                             final long msSinceStartScroll) {
        return policy.applyScrollSpeed(
                standardSpeed, ITEM_SIZE_PX, 1, 120.0f, msSinceStartScroll);
    }

    private static ItemDragScrollPolicy expeditedPolicy() {
        final ItemDragScrollPolicy policy = new ItemDragScrollPolicy();
        apply(policy, 100, 0L);
        apply(policy, 100, ItemDragScrollPolicy.EXPEDITED_SCROLL_DELAY_MS
                + ItemDragScrollPolicy.EXPEDITED_SCROLL_RAMP_MS);
        return policy;
    }

    private static int reachExpeditedCap(final float refreshRateHz) {
        final ItemDragScrollPolicy policy = new ItemDragScrollPolicy();
        policy.applyScrollSpeed(100, ITEM_SIZE_PX, 1, refreshRateHz, 0L);
        return policy.applyScrollSpeed(100, ITEM_SIZE_PX, 1, refreshRateHz,
                ItemDragScrollPolicy.EXPEDITED_SCROLL_DELAY_MS
                        + ItemDragScrollPolicy.EXPEDITED_SCROLL_RAMP_MS);
    }

    private static float clampPointerY(final float pointerY) {
        return ItemDragScrollPolicy.clampPointerY(pointerY, 200, 20, 30);
    }
}
