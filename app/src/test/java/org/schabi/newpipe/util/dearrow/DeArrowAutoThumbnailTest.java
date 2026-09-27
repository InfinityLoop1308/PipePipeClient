package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Tests for the URL side of the stored-frame path.
 *
 * <p>Only the pure parts are covered here — fetching and letterbox removal need a real
 * {@code Bitmap} and belong to the on-device suite. What matters at this level is that the
 * right file is asked for, since asking for the wrong one fails silently as "no thumbnail
 * available" rather than as an error.</p>
 */
public class DeArrowAutoThumbnailTest {

    /** A long-standing upload, used throughout these tests as the stable reference. */
    private static final String UPLOAD = "dQw4w9WgXcQ";

    @Test
    public void anUploadAsksForOneOfTheThreeStoredFrames() {
        final String url = DeArrowAutoThumbnail.urlFor(UPLOAD);
        assertTrue(url, url.startsWith("https://i.ytimg.com/vi/" + UPLOAD + "/hq"));
        assertTrue(url, url.endsWith(".jpg"));
        final int index = DeArrowAutoThumbnail.frameIndexFor(UPLOAD);
        assertEquals("https://i.ytimg.com/vi/" + UPLOAD + "/hq" + index + ".jpg", url);
    }

    @Test
    public void neverAsksForALiveVariant() {
        // This is a regression test for a wrong answer that looked right for two days.
        //
        // YouTube serves hq720_live.jpg for a broadcast, and it is tempting: present on every
        // live stream, 1280x720, natively 16:9. It is NOT a frame — it is the broadcaster's own
        // thumbnail at 720p, so a row using it has its clickbait replaced with the same
        // clickbait. Measuring it against hqdefault.jpg reads 0.28-0.40 RMSE and looks like a
        // real difference, but that is the letterboxing: hqdefault is boxed into 4:3 and
        // hq720_live is not. Same picture.
        //
        // Live frames come from DeArrowFrameRenderer#renderLive, which decodes the broadcast.
        for (final String videoId : new String[]{UPLOAD, "jNQXAC9IVRw", "SZ97C64_N-A"}) {
            final String url = DeArrowAutoThumbnail.urlFor(videoId);
            assertFalse(url + " asks for a _live variant, which is the uploader's own artwork",
                    url.contains("_live"));
        }
    }

    @Test
    public void theChosenFrameIsAlwaysOneThatExists() {
        // Off-by-one here produces hq0.jpg or hq4.jpg, which 404 — and a 404 is indistinguishable
        // from "this video has no stored frames", so the feature would just look dead.
        for (final String videoId : new String[]{
                UPLOAD, "jNQXAC9IVRw", "hbnt8mmL0ZI", "TU2fsUBRH4M", "aaaaaaaaaaa", "___________"}) {
            final int index = DeArrowAutoThumbnail.frameIndexFor(videoId);
            assertTrue(videoId + " picked hq" + index,
                    index >= 1 && index <= DeArrowAutoThumbnail.AUTO_FRAME_COUNT);
        }
    }

    @Test
    public void theSameVideoAlwaysGetsTheSameFrame() {
        // A row that scrolls out of view and back must not change picture, so the choice has to
        // come from the id alone and never from a clock or a counter.
        final int first = DeArrowAutoThumbnail.frameIndexFor(UPLOAD);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, DeArrowAutoThumbnail.frameIndexFor(UPLOAD));
        }
    }

    @Test
    public void differentVideosDoNotAllGetTheSameFrame() {
        // A seed bug that collapses to a constant would still pass every test above while
        // showing frame 1 of every video on YouTube.
        final Set<Integer> seen = new HashSet<>();
        for (final String videoId : new String[]{
                "dQw4w9WgXcQ", "jNQXAC9IVRw", "hbnt8mmL0ZI", "TU2fsUBRH4M", "BDMA3BOvIQQ",
                "5NagHDVO9rQ", "6W45b41q3tc", "swiNuKZljYw", "FHty-Gto5lQ", "NYZ1nUjrdTc"}) {
            seen.add(DeArrowAutoThumbnail.frameIndexFor(videoId));
        }
        assertNotEquals("every video got the same frame", 1, seen.size());
    }

    @Test
    public void theFrameIsChosenByTheSameGeneratorTheServerUses() {
        // Not an independent hash: reusing DeArrowRandomTime is what keeps this client's choice
        // consistent with the timestamp the DeArrow server would have picked for the same video.
        final double scaled = DeArrowRandomTime.fractionFor(UPLOAD) / DeArrowRandomTime.TAIL_TO_AVOID;
        final int expected = Math.min(
                (int) (scaled * DeArrowAutoThumbnail.AUTO_FRAME_COUNT) + 1,
                DeArrowAutoThumbnail.AUTO_FRAME_COUNT);
        assertEquals(expected, DeArrowAutoThumbnail.frameIndexFor(UPLOAD));
    }
}
