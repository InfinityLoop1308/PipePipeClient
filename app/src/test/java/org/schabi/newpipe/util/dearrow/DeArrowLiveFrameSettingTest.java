package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins live-frame rendering to its own opt-in.
 *
 * <p>Every other thumbnail path costs one small image fetch from YouTube's thumbnail host. A
 * live broadcast has no stored frame anywhere, so the only way to get one is to resolve the
 * stream and briefly open it — a player request plus a media decode for every live row the
 * user scrolls past. That is a different order of cost, and the point of these tests is that
 * it can never start happening as a side effect of turning something else on.</p>
 */
public class DeArrowLiveFrameSettingTest {

    @Test
    public void theShippedDefaultLeavesLiveAlone() {
        assertFalse("live frames must be off until the user asks for them",
                DeArrowConfig.disabled().shouldUseLiveFrames());
    }

    @Test
    public void turningOnTheOrdinaryFrameFallbackDoesNotTurnOnLive() {
        // The regression this guards: folding live into shouldUseRandomFrameFallback would
        // make "replace thumbnails when nobody submitted one" silently start opening video
        // streams, which is not what that switch says it does.
        final DeArrowConfig framesOnLiveOff = new DeArrowConfig(
                true, true, true, /* useRandomFrameFallback */ true, true,
                DeArrowConfig.DEFAULT_API_URL, DeArrowConfig.DEFAULT_THUMBNAIL_API_URL);
        assertTrue(framesOnLiveOff.shouldUseRandomFrameFallback());
        assertFalse("the short constructor must never enable live",
                framesOnLiveOff.shouldUseLiveFrames());
    }

    @Test
    public void liveCanBeTurnedOnDeliberately() {
        final DeArrowConfig liveOn = new DeArrowConfig(
                true, true, true, true, /* useLiveFrames */ true, true,
                DeArrowConfig.DEFAULT_API_URL, DeArrowConfig.DEFAULT_THUMBNAIL_API_URL);
        assertTrue(liveOn.shouldUseLiveFrames());
    }

    @Test
    public void allEnabledMeansAllEnabled() {
        // The test helper is allowed to be the one place everything is on, so a test that
        // wants to exercise the live path does not have to spell out eight booleans.
        assertTrue(DeArrowConfig.allEnabled().shouldUseLiveFrames());
    }
}
