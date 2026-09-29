package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * What "leave this channel alone" has to match, and what it must not.
 *
 * <p>The failure this guards against is silent: an exclusion that does not match is
 * indistinguishable from the feature ignoring the user, because the row simply keeps being
 * replaced and nothing says why.</p>
 */
public class DeArrowExclusionRulesTest {

    private static Set<String> excluding(final String url, final String name) {
        return Collections.singleton(DeArrowExclusionRules.encodeChannel(url, name));
    }

    private static boolean matches(final Set<String> entries,
                                   final String url,
                                   final String name) {
        return DeArrowExclusionRules.matchesChannel(
                DeArrowExclusionRules.fieldsOf(entries, 0),
                DeArrowExclusionRules.fieldsOf(entries, 1),
                url, name);
    }

    @Test
    public void matchesTheChannelItWasCreatedFrom() {
        final Set<String> entries =
                excluding("https://www.youtube.com/channel/UC123", "Some Channel");
        assertTrue(matches(entries, "https://www.youtube.com/channel/UC123", "Some Channel"));
    }

    @Test
    public void matchesAcrossSchemeHostAndTrailingSlashDifferences() {
        final Set<String> entries =
                excluding("https://www.youtube.com/channel/UC123", "Some Channel");
        assertTrue(matches(entries, "http://m.youtube.com/channel/UC123/", null));
        assertTrue(matches(entries, "youtube.com/channel/UC123", null));
    }

    /**
     * The case the URL-only design would have got wrong: several extractor paths leave the
     * uploader URL null on a list row, so an exclusion created there has to still fire when
     * only the name is known.
     */
    @Test
    public void matchesOnNameAloneWhenTheRowCarriesNoUrl() {
        final Set<String> entries = excluding(null, "Some Channel");
        assertTrue(matches(entries, null, "some channel"));
        assertTrue(matches(entries, null, "  SOME CHANNEL  "));
    }

    @Test
    public void matchesOnUrlAloneAfterARename() {
        final Set<String> entries =
                excluding("https://www.youtube.com/channel/UC123", "Old Name");
        assertTrue(matches(entries, "https://www.youtube.com/channel/UC123", "Brand New Name"));
    }

    @Test
    public void doesNotMatchAnUnrelatedChannel() {
        final Set<String> entries =
                excluding("https://www.youtube.com/channel/UC123", "Some Channel");
        assertFalse(matches(entries, "https://www.youtube.com/channel/UC999", "Other Channel"));
        assertFalse(matches(entries, null, null));
    }

    /**
     * An empty field must never be a wildcard. A channel stored with no URL leaves that field
     * blank, and a row that also has no URL would otherwise match every such entry.
     */
    @Test
    public void anEmptyStoredFieldMatchesNothing() {
        final Set<String> entries = excluding(null, "Some Channel");
        assertTrue(DeArrowExclusionRules.fieldsOf(entries, 0).isEmpty());
        assertFalse(matches(entries, "", "Other Channel"));
        assertFalse(matches(entries, null, "Other Channel"));
    }

    @Test
    public void removalTargetsTheEntryThatWasMatching() {
        final String entry =
                DeArrowExclusionRules.encodeChannel("https://youtube.com/channel/UC123", "Name");
        assertTrue(DeArrowExclusionRules.entryIsChannel(
                "https://www.youtube.com/channel/UC123/", null, entry));
        assertTrue(DeArrowExclusionRules.entryIsChannel(null, "NAME", entry));
        assertFalse(DeArrowExclusionRules.entryIsChannel(
                "https://youtube.com/channel/UC999", "Other", entry));
    }

    /** The display name survives the round trip, because it is what the settings list shows. */
    @Test
    public void keepsTheDisplayNameForTheSettingsList() {
        final String entry = DeArrowExclusionRules.encodeChannel(
                "https://youtube.com/channel/UC123", "Mixed Case Name");
        assertEquals("Mixed Case Name", DeArrowExclusionRules.displayNameOf(entry));
    }

    /** A name containing the field separator would otherwise split into a corrupt entry. */
    @Test
    public void survivesANameWithPunctuationAndPipes() {
        final String name = "Weird | Name, with \u001F stuff";
        final String entry =
                DeArrowExclusionRules.encodeChannel("https://youtube.com/channel/UC1", name);
        assertTrue(DeArrowExclusionRules.entryIsChannel(
                "https://youtube.com/channel/UC1", null, entry));
    }

    @Test
    public void videoLabelsRoundTripAndFallBackToTheId() {
        final Set<String> labels = new HashSet<>();
        labels.add(DeArrowExclusionRules.encodeVideoLabel("abcdefghijk", "A Title"));
        assertEquals("A Title", DeArrowExclusionRules.labelFor("abcdefghijk", labels));
        assertEquals("zzzzzzzzzzz", DeArrowExclusionRules.labelFor("zzzzzzzzzzz", labels));
        assertTrue(DeArrowExclusionRules.labelIsFor("abcdefghijk",
                DeArrowExclusionRules.encodeVideoLabel("abcdefghijk", null)));
    }

    @Test
    public void normalisesNothingOutOfNothing() {
        assertNull(DeArrowExclusionRules.normalizeUrl(null));
        assertNull(DeArrowExclusionRules.normalizeUrl("   "));
        assertNull(DeArrowExclusionRules.normalizeUrl("https://"));
        assertNull(DeArrowExclusionRules.normalizeName(null));
        assertNull(DeArrowExclusionRules.normalizeName("  "));
    }
}
