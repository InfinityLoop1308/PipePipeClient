package org.schabi.newpipe.fragments.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import org.junit.Test;

public final class VideoDetailBottomSheetStateTest {
    @Test
    public void stableStatesArePreserved() {
        final int[] stableStates = {
                BottomSheetBehavior.STATE_COLLAPSED,
                BottomSheetBehavior.STATE_EXPANDED,
                BottomSheetBehavior.STATE_HALF_EXPANDED,
                BottomSheetBehavior.STATE_HIDDEN
        };

        for (final int state : stableStates) {
            assertTrue(VideoDetailFragment.isStableBottomSheetState(state));
            assertEquals(state, VideoDetailFragment.sanitizeBottomSheetState(state));
        }
    }

    @Test
    public void transientAndUnknownStatesBecomeCollapsed() {
        final int[] invalidSavedStates = {
                BottomSheetBehavior.STATE_DRAGGING,
                BottomSheetBehavior.STATE_SETTLING,
                12345
        };

        for (final int state : invalidSavedStates) {
            assertFalse(VideoDetailFragment.isStableBottomSheetState(state));
            assertEquals(BottomSheetBehavior.STATE_COLLAPSED,
                    VideoDetailFragment.sanitizeBottomSheetState(state));
        }
    }
}
