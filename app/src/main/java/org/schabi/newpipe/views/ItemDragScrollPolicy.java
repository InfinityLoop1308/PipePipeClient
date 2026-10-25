package org.schabi.newpipe.views;

final class ItemDragScrollPolicy {
    static final float DEFAULT_REFRESH_RATE_HZ = 60.0f;
    static final long INITIAL_SCROLL_RAMP_MS = 500L;
    static final long EXPEDITED_SCROLL_DELAY_MS = 1_800L;
    static final long EXPEDITED_SCROLL_RAMP_MS = 1_250L;

    private static final long ANDROIDX_SCROLL_RAMP_MS = 2_000L;
    private static final float NORMAL_MAX_ITEMS_PER_SECOND = 11.0f;
    private static final float EXPEDITED_MAX_ITEMS_PER_SECOND = 30.0f;
    private static final long UNSET_TIME_MS = -1L;

    private long normalCapReachedAtMs = UNSET_TIME_MS;
    private long lastScrollDurationMs = UNSET_TIME_MS;
    private int lastDirection;
    private long initialRampStartedAtMs = UNSET_TIME_MS;
    private long lastInitialRampElapsedMs = UNSET_TIME_MS;
    private int initialRampDirection;

    static int capScrollSpeed(final int standardSpeed, final int itemSizePx,
                              final int itemsPerRow, final float refreshRateHz) {
        return capScrollSpeed(standardSpeed, NORMAL_MAX_ITEMS_PER_SECOND,
                itemSizePx, itemsPerRow, refreshRateHz);
    }

    static long initialRampElapsedForAndroidX(final long elapsedMs) {
        if (elapsedMs <= 0L) {
            return 0L;
        }

        // ItemTouchHelper hardcodes a two-second elapsed-time ramp. Compress that ramp while
        // retaining its distance interpolation and minimum non-zero movement behavior.
        if (elapsedMs >= INITIAL_SCROLL_RAMP_MS) {
            return ANDROIDX_SCROLL_RAMP_MS;
        }

        return elapsedMs * ANDROIDX_SCROLL_RAMP_MS / INITIAL_SCROLL_RAMP_MS;
    }

    long initialRampElapsedForAndroidX(final long elapsedMs, final int direction) {
        final long safeElapsedMs = Math.max(0L, elapsedMs);
        final int safeDirection = Integer.signum(direction);
        final boolean edgeScrollingRestarted = lastInitialRampElapsedMs != UNSET_TIME_MS
                && safeElapsedMs < lastInitialRampElapsedMs;
        final boolean directionChanged = initialRampDirection != 0
                && safeDirection != initialRampDirection;

        if (initialRampStartedAtMs == UNSET_TIME_MS
                || edgeScrollingRestarted
                || directionChanged) {
            initialRampStartedAtMs = safeElapsedMs;
        }

        lastInitialRampElapsedMs = safeElapsedMs;
        initialRampDirection = safeDirection;

        return initialRampElapsedForAndroidX(safeElapsedMs - initialRampStartedAtMs);
    }

    int applyScrollSpeed(final int standardSpeed, final int itemSizePx,
                         final int itemsPerRow, final float refreshRateHz,
                         final long msSinceStartScroll) {
        if (standardSpeed == 0) {
            resetExpeditedScroll();
            return 0;
        }

        final long scrollDurationMs = Math.max(0L, msSinceStartScroll);
        final int direction = Integer.signum(standardSpeed);
        final int absoluteStandardSpeed = Math.abs(standardSpeed);
        final int normalMaxPixelsPerFrame = maxPixelsPerFrame(
                NORMAL_MAX_ITEMS_PER_SECOND, itemSizePx, itemsPerRow, refreshRateHz);
        final boolean edgeScrollingRestarted = lastScrollDurationMs != UNSET_TIME_MS
                && scrollDurationMs < lastScrollDurationMs;
        final boolean directionChanged = lastDirection != 0 && direction != lastDirection;
        final boolean belowNormalCap = absoluteStandardSpeed < normalMaxPixelsPerFrame;

        if (edgeScrollingRestarted || directionChanged || belowNormalCap) {
            resetExpeditedScroll();
        }

        lastScrollDurationMs = scrollDurationMs;
        lastDirection = direction;

        if (belowNormalCap) {
            return standardSpeed;
        }

        // Arm the expedited stage only after AndroidX's distance/time ramp reaches the normal cap.
        if (normalCapReachedAtMs == UNSET_TIME_MS) {
            normalCapReachedAtMs = scrollDurationMs;
        }

        final long normalCapDurationMs = scrollDurationMs - normalCapReachedAtMs;
        final float maxItemsPerSecond = maxItemsPerSecond(normalCapDurationMs);

        return capScrollSpeed(standardSpeed, maxItemsPerSecond,
                itemSizePx, itemsPerRow, refreshRateHz);
    }

    void resetExpeditedScroll() {
        normalCapReachedAtMs = UNSET_TIME_MS;
        lastScrollDurationMs = UNSET_TIME_MS;
        lastDirection = 0;
    }

    void resetScroll() {
        initialRampStartedAtMs = UNSET_TIME_MS;
        lastInitialRampElapsedMs = UNSET_TIME_MS;
        initialRampDirection = 0;
        resetExpeditedScroll();
    }

    private static int capScrollSpeed(final int standardSpeed,
                                      final float maxItemsPerSecond,
                                      final int itemSizePx,
                                      final int itemsPerRow,
                                      final float refreshRateHz) {
        if (standardSpeed == 0) {
            return 0;
        }

        final int maxPixelsPerFrame = maxPixelsPerFrame(
                maxItemsPerSecond, itemSizePx, itemsPerRow, refreshRateHz);

        return Integer.signum(standardSpeed)
                * Math.min(Math.abs(standardSpeed), maxPixelsPerFrame);
    }

    private static int maxPixelsPerFrame(final float maxItemsPerSecond,
                                         final int itemSizePx,
                                         final int itemsPerRow,
                                         final float refreshRateHz) {
        final int safeItemSizePx = Math.max(1, itemSizePx);
        final int safeItemsPerRow = Math.max(1, itemsPerRow);
        final float safeRefreshRate = isPositiveAndFinite(refreshRateHz)
                ? refreshRateHz
                : DEFAULT_REFRESH_RATE_HZ;

        return Math.max(1, Math.round(
                maxItemsPerSecond * safeItemSizePx / safeItemsPerRow / safeRefreshRate));
    }

    private static float maxItemsPerSecond(final long normalCapDurationMs) {
        final long expeditedRampDurationMs = normalCapDurationMs - EXPEDITED_SCROLL_DELAY_MS;
        if (expeditedRampDurationMs <= 0L) {
            return NORMAL_MAX_ITEMS_PER_SECOND;
        }

        final float rampProgress = Math.min(1.0f,
                (float) expeditedRampDurationMs / EXPEDITED_SCROLL_RAMP_MS);
        final float easedProgress = smootherStep(rampProgress);

        return NORMAL_MAX_ITEMS_PER_SECOND
                + (EXPEDITED_MAX_ITEMS_PER_SECOND - NORMAL_MAX_ITEMS_PER_SECOND)
                * easedProgress;
    }

    private static float smootherStep(final float value) {
        return value * value * value * (value * (value * 6.0f - 15.0f) + 10.0f);
    }

    static float clampPointerY(final float pointerY, final int height,
                               final int paddingTop, final int paddingBottom) {
        final float top = Math.max(0, paddingTop);
        final float bottom = Math.max(top, height - Math.max(0, paddingBottom));

        return Math.max(top, Math.min(pointerY, bottom));
    }

    private static boolean isPositiveAndFinite(final float value) {
        return value > 0.0f && !Float.isInfinite(value) && !Float.isNaN(value);
    }
}
