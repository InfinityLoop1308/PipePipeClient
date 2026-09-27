package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Tests for the DeArrow selection rules.
 *
 * <p>Fixtures under {@code src/test/resources/dearrow} are unedited responses captured from the
 * live API, not hand-written ones, so these tests fail if the API changes shape rather than
 * passing against a fiction. Hand-built JSON is used only for cases the live API does not
 * currently exhibit, such as a removed submission.</p>
 */
public class DeArrowParserTest {

    private static final String RICKROLL_ID = "dQw4w9WgXcQ";
    private static final String ZOO_ID = "jNQXAC9IVRw";
    private static final String GANGNAM_ID = "9bZkp7q19f0";

    private static String fixture(final String name) throws IOException {
        try (InputStream in = DeArrowParserTest.class
                .getResourceAsStream("/dearrow/" + name)) {
            assertNotNull("missing fixture: " + name, in);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static JsonObject json(final String raw) throws Exception {
        return JsonParser.object().from(raw);
    }

    private static DeArrowConfig config(final boolean titles,
                                        final boolean thumbnails,
                                        final boolean randomFrame,
                                        final boolean autoFormat) {
        return new DeArrowConfig(true, titles, thumbnails, randomFrame, autoFormat,
                DeArrowConfig.DEFAULT_API_URL, DeArrowConfig.DEFAULT_THUMBNAIL_API_URL);
    }

    // --- title selection ---------------------------------------------------

    @Test
    public void lockedTitleWinsOverMoreRecentSubmission() throws Exception {
        final DeArrowBranding result = DeArrowParser.parseSingle(
                fixture("rickroll_single.json"), RICKROLL_ID, DeArrowConfig.allEnabled());
        assertEquals("Rick Astley - Never gonna give you up (official music video)",
                result.getTitle());
    }

    @Test
    public void highestVotesWinsWhenNothingIsLocked() throws Exception {
        final JsonObject branding = json("{\"titles\":["
                + "{\"title\":\"three votes\",\"votes\":3,\"UUID\":\"a\"},"
                + "{\"title\":\"nine votes\",\"votes\":9,\"UUID\":\"b\"}]}");
        assertEquals("nine votes",
                DeArrowParser.parseBranding(branding, RICKROLL_ID, config(true, true, false, false))
                        .getTitle());
    }

    @Test
    public void removedSubmissionsAreIgnored() throws Exception {
        final JsonObject branding = json("{\"titles\":["
                + "{\"title\":\"removed\",\"votes\":99,\"removed\":true,\"UUID\":\"a\"},"
                + "{\"title\":\"kept\",\"votes\":1,\"UUID\":\"b\"}]}");
        assertEquals("kept",
                DeArrowParser.parseBranding(branding, RICKROLL_ID, config(true, true, false, false))
                        .getTitle());
    }

    @Test
    public void downvotedSubmissionsAreIgnored() throws Exception {
        final JsonObject branding = json("{\"titles\":["
                + "{\"title\":\"downvoted\",\"votes\":-2,\"UUID\":\"a\"},"
                + "{\"title\":\"kept\",\"votes\":0,\"UUID\":\"b\"}]}");
        assertEquals("kept",
                DeArrowParser.parseBranding(branding, RICKROLL_ID, config(true, true, false, false))
                        .getTitle());
    }

    /**
     * The "Me at the zoo" fixture is the real-world case for this: its top-voted, locked title is
     * marked {@code original}, meaning the community voted to keep the uploader's own title. The
     * correct answer is no replacement — promoting the runner-up would show a title the community
     * explicitly did not pick.
     */
    @Test
    public void winningOriginalTitleMeansNoReplacement() throws Exception {
        final DeArrowBranding result = DeArrowParser.parseSingle(
                fixture("jNQXAC9IVRw_single.json"), ZOO_ID, DeArrowConfig.allEnabled());
        assertNull(result.getTitle());
    }

    @Test
    public void noTitleWhenEverySubmissionIsFilteredOut() throws Exception {
        final JsonObject branding = json("{\"titles\":["
                + "{\"title\":\"gone\",\"votes\":5,\"removed\":true,\"UUID\":\"a\"}]}");
        assertTrue(DeArrowParser
                .parseBranding(branding, RICKROLL_ID, config(true, false, false, false))
                .isEmpty());
    }

    @Test
    public void noTitleWhenTitlesArrayIsAbsent() throws Exception {
        assertNull(DeArrowParser
                .parseBranding(json("{}"), RICKROLL_ID, config(true, true, false, false))
                .getTitle());
    }

    @Test
    public void equalVotesAreBrokenDeterministically() throws Exception {
        // Both Gangnam Style titles have exactly one vote and neither is locked. The tie-break is
        // arbitrary, but it must be stable: the same input must always give the same title.
        final String raw = fixture("9bZkp7q19f0_single.json");
        final DeArrowBranding first = DeArrowParser.parseSingle(
                raw, GANGNAM_ID, DeArrowConfig.allEnabled());
        final DeArrowBranding second = DeArrowParser.parseSingle(
                raw, GANGNAM_ID, DeArrowConfig.allEnabled());
        assertNotNull(first.getTitle());
        assertEquals(first.getTitle(), second.getTitle());
    }

    // --- thumbnail selection -----------------------------------------------

    @Test
    public void thumbnailUrlUsesTheWinningCommunityFrame() throws Exception {
        final DeArrowBranding result = DeArrowParser.parseSingle(
                fixture("rickroll_single.json"), RICKROLL_ID, DeArrowConfig.allEnabled());
        assertEquals("https://dearrow-thumb.ajay.app/api/v1/getThumbnail"
                        + "?videoID=dQw4w9WgXcQ&time=3.92349",
                result.getThumbnailUrl());
    }

    @Test
    public void originalThumbnailIsNeverUsedAsAReplacement() throws Exception {
        final JsonObject branding = json("{\"thumbnails\":["
                + "{\"timestamp\":null,\"original\":true,\"votes\":9,\"UUID\":\"a\"}]}");
        assertNull(DeArrowParser
                .parseBranding(branding, RICKROLL_ID, config(false, true, false, false))
                .getThumbnailUrl());
    }

    @Test
    public void randomTimeNeverBecomesAThumbnailUrl() throws Exception {
        // Regression test for a bug that made the screen worse than doing nothing.
        //
        // This used to synthesise a thumbnail-server URL from `randomTime` for every video
        // with a bucket entry, and hand it to the image loader. That server only serves
        // frames it already holds and answers HTTP 204 for the rest — which an image loader
        // reads as a successful empty response, so it paints its placeholder: a grey box
        // with a play arrow, over a row that had a perfectly good thumbnail. Reported on a
        // real device, 2026-09-27, and sporadic-looking because it only hit videos DeArrow
        // knows about.
        //
        // The seeded frame is produced locally now (DeArrowAutoThumbnail), so a URL here is
        // only ever a genuine community submission. "Me at the zoo" has none.
        final String raw = fixture("jNQXAC9IVRw_single.json");
        assertNull("random frame off must not invent a thumbnail",
                DeArrowParser.parseSingle(raw, ZOO_ID, config(true, true, false, false))
                        .getThumbnailUrl());
        assertNull("random frame on must STILL not invent a server URL",
                DeArrowParser.parseSingle(raw, ZOO_ID, config(true, true, true, false))
                        .getThumbnailUrl());
    }

    @Test
    public void thumbnailUrlIsLocaleIndependentAndNotScientific() {
        final String url = DeArrowParser.buildThumbnailUrl(
                RICKROLL_ID, 0.00008978400969449198, DeArrowConfig.allEnabled());
        assertFalse("must not use scientific notation: " + url, url.contains("E"));
        assertFalse("must not use a comma decimal separator: " + url, url.contains(","));
        assertTrue(url.contains("videoID=" + RICKROLL_ID));
    }

    // --- settings gating ---------------------------------------------------

    @Test
    public void titlesOffStillReplacesTheThumbnail() throws Exception {
        final DeArrowBranding result = DeArrowParser.parseSingle(
                fixture("rickroll_single.json"), RICKROLL_ID, config(false, true, false, false));
        assertNull(result.getTitle());
        assertNotNull(result.getThumbnailUrl());
    }

    @Test
    public void thumbnailsOffStillReplacesTheTitle() throws Exception {
        final DeArrowBranding result = DeArrowParser.parseSingle(
                fixture("rickroll_single.json"), RICKROLL_ID, config(true, false, false, false));
        assertNotNull(result.getTitle());
        assertNull(result.getThumbnailUrl());
    }

    @Test
    public void disabledConfigReturnsNothingAtAll() throws Exception {
        assertTrue(DeArrowParser
                .parseSingle(fixture("rickroll_single.json"), RICKROLL_ID,
                        DeArrowConfig.disabled())
                .isEmpty());
    }

    // --- title auto-formatting ---------------------------------------------

    @Test
    public void shoutingTitleIsConvertedToSentenceCase() {
        assertEquals("This video changed everything",
                DeArrowParser.autoFormatTitle("THIS VIDEO CHANGED EVERYTHING"));
    }

    @Test
    public void acronymsSurviveAutoFormatting() {
        assertEquals("Inside NASA's newest rocket",
                DeArrowParser.autoFormatTitle("INSIDE NASA'S NEWEST ROCKET"));
        assertEquals("What AI actually costs",
                DeArrowParser.autoFormatTitle("WHAT AI ACTUALLY COSTS"));
    }

    @Test
    public void sentenceCaseTitleIsLeftUntouched() {
        final String title = "How a jet engine actually works";
        assertEquals(title, DeArrowParser.autoFormatTitle(title));
    }

    @Test
    public void aSingleShoutedWordDoesNotTriggerReformatting() {
        final String title = "This is ACTUALLY how it works";
        assertEquals(title, DeArrowParser.autoFormatTitle(title));
    }

    @Test
    public void autoFormatCanBeTurnedOff() throws Exception {
        final JsonObject branding = json(
                "{\"titles\":[{\"title\":\"SHOUTING AT THE READER\",\"votes\":1,\"UUID\":\"a\"}]}");
        assertEquals("SHOUTING AT THE READER",
                DeArrowParser.parseBranding(branding, RICKROLL_ID, config(true, true, false, false))
                        .getTitle());
        assertEquals("Shouting at the reader",
                DeArrowParser.parseBranding(branding, RICKROLL_ID, config(true, true, false, true))
                        .getTitle());
    }

    // --- error handling ----------------------------------------------------

    @Test
    public void malformedJsonThrowsRatherThanSilentlyDoingNothing() {
        assertThrows(DeArrowParseException.class,
                () -> DeArrowParser.parseSingle("<html>502</html>", RICKROLL_ID,
                        DeArrowConfig.allEnabled()));
    }

    @Test
    public void emptyBucketYieldsNothingAndDoesNotThrow() throws Exception {
        assertTrue(DeArrowParser.parseBucketEntry("{}", RICKROLL_ID, DeArrowConfig.allEnabled())
                .isEmpty());
    }

    // --- hash bucket -------------------------------------------------------

    @Test
    public void hashPrefixMatchesTheDocumentedSha256() {
        // sha256("dQw4w9WgXcQ") = 5f6b0b4e201f... — verified against the `dearrow hash` CLI.
        assertEquals("5f6b", DeArrowParser.hashPrefix(RICKROLL_ID));
    }

    @Test
    public void bucketUrlTargetsThePrefixNotTheVideo() {
        final String url = DeArrowParser.buildBucketUrl(RICKROLL_ID, DeArrowConfig.allEnabled());
        assertEquals("https://sponsor.ajay.app/api/branding/5f6b", url);
        assertFalse("the video id must never appear in the request",
                url.contains(RICKROLL_ID));
    }

    @Test
    public void bucketContainsManyVideosAndTheRightEntryIsExtracted() throws Exception {
        final String raw = fixture("rickroll_bucket.json");
        final List<String> ids = DeArrowParser.bucketVideoIds(raw);
        assertTrue("a real bucket holds many videos, this one had " + ids.size(),
                ids.size() > 10);
        assertTrue(ids.contains(RICKROLL_ID));

        final DeArrowBranding fromBucket = DeArrowParser.parseBucketEntry(
                raw, RICKROLL_ID, DeArrowConfig.allEnabled());
        final DeArrowBranding fromSingle = DeArrowParser.parseSingle(
                fixture("rickroll_single.json"), RICKROLL_ID, DeArrowConfig.allEnabled());
        assertEquals("the bucket and single-video endpoints must agree",
                fromSingle, fromBucket);
    }

    /**
     * A bucket must be parseable in one pass. Reading it entry-by-entry re-parses the whole
     * body for every video in it; on a real device that quadratic work produced an
     * "isn't responding" dialog while every test here stayed green (2026-09-23).
     */
    @Test
    public void wholeBucketParsesInOnePassAndAgreesWithPerEntryParsing() throws Exception {
        final String raw = fixture("rickroll_bucket.json");
        final Map<String, DeArrowBranding> all =
                DeArrowParser.parseBucket(raw, DeArrowConfig.allEnabled());
        assertTrue("a real bucket holds many videos, this one had " + all.size(),
                all.size() > 10);
        assertEquals(
                DeArrowParser.parseBucketEntry(raw, RICKROLL_ID, DeArrowConfig.allEnabled()),
                all.get(RICKROLL_ID));
    }

    @Test
    public void bucketParsedWhileDisabledYieldsNothingForEveryVideo() throws Exception {
        final Map<String, DeArrowBranding> all =
                DeArrowParser.parseBucket(fixture("rickroll_bucket.json"),
                        DeArrowConfig.disabled());
        for (final DeArrowBranding branding : all.values()) {
            assertTrue("disabled must yield no replacement anywhere", branding.isEmpty());
        }
    }

    @Test
    public void videoAbsentFromItsBucketYieldsNothing() throws Exception {
        assertTrue(DeArrowParser
                .parseBucketEntry(fixture("rickroll_bucket.json"), "aaaaaaaaaaa",
                        DeArrowConfig.allEnabled())
                .isEmpty());
    }
}
