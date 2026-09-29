package org.schabi.newpipe.util.dearrow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * How an exclusion is written down and how a row is matched against it.
 *
 * <p>Split out of {@link DeArrowExclusions} for the same reason {@link DeArrowParser} is split
 * out of {@link DeArrowSettings}: everything here is plain Java with no Android imports, so it
 * can be covered by ordinary JVM unit tests. The matching rules are the part worth testing —
 * a URL that fails to match because one side had a trailing slash means the user excluded a
 * channel and it kept being replaced, silently.</p>
 */
public final class DeArrowExclusionRules {

    /**
     * Separates the fields of a stored entry.
     *
     * <p>A control character rather than a comma or a pipe: a channel's display name may
     * contain any printable character, and splitting on one a name can contain would corrupt
     * the entry. U+001F cannot appear in a URL and does not survive in a YouTube channel
     * name.</p>
     */
    static final String FIELD_SEPARATOR = "\u001F";

    private DeArrowExclusionRules() {
    }

    /**
     * @param entry a stored entry
     * @return its fields; always at least three elements, never null elements, so a caller
     *         can index the URL, the normalised name and the display name without checking
     *         the length of an entry written by an older version
     */
    @NonNull
    static String[] split(@NonNull final String entry) {
        final String[] parts = entry.split(FIELD_SEPARATOR, -1);
        final String[] out = {"", "", ""};
        System.arraycopy(parts, 0, out, 0, Math.min(parts.length, out.length));
        return out;
    }

    /**
     * Builds the stored form of a channel exclusion.
     *
     * @param uploaderUrl  the channel URL as the row reported it, or null
     * @param uploaderName the channel's display name, or null
     * @return the entry to store
     */
    @NonNull
    static String encodeChannel(@Nullable final String uploaderUrl,
                                @Nullable final String uploaderName) {
        final String url = normalizeUrl(uploaderUrl);
        final String name = normalizeName(uploaderName);
        return (url == null ? "" : url)
                + FIELD_SEPARATOR + (name == null ? "" : name)
                + FIELD_SEPARATOR + (uploaderName == null ? "" : uploaderName);
    }

    /**
     * @param entry a stored channel entry
     * @return what to show the user for it: the display name it was stored with, falling back
     *         to whatever identity it does carry so a pre-display-name entry still reads as
     *         something rather than as an empty row
     */
    @NonNull
    static String displayNameOf(@NonNull final String entry) {
        final String[] parts = split(entry);
        if (!parts[2].isEmpty()) {
            return parts[2];
        }
        return !parts[1].isEmpty() ? parts[1] : parts[0];
    }

    /**
     * @param entries stored channel entries
     * @param index   0 for the normalised URL, 1 for the normalised name
     * @return every non-empty value of that field, ready for {@code Set.contains}
     */
    @NonNull
    static Set<String> fieldsOf(@NonNull final Set<String> entries, final int index) {
        final Set<String> out = new HashSet<>();
        for (final String entry : entries) {
            final String value = split(entry)[index];
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * Whether a row's channel is one of the excluded ones.
     *
     * <p>URL <em>or</em> name is enough. The URL is the stable identity, but several extractor
     * paths leave {@code uploaderUrl} null on a list row and only fill it in when the item is
     * opened, so an exclusion that matched on URL alone would work on the video page and do
     * nothing in the list it was created from. The cost is that a renamed channel loses the
     * name half, and two channels sharing a display name are excluded together — the same
     * trade the existing channel filter already makes.</p>
     *
     * @param excludedUrls  normalised URLs, from {@link #fieldsOf}
     * @param excludedNames normalised names, from {@link #fieldsOf}
     * @param uploaderUrl   the row's channel URL, raw
     * @param uploaderName  the row's channel name, raw
     * @return true if this row's channel is excluded
     */
    static boolean matchesChannel(@NonNull final Set<String> excludedUrls,
                                  @NonNull final Set<String> excludedNames,
                                  @Nullable final String uploaderUrl,
                                  @Nullable final String uploaderName) {
        final String url = normalizeUrl(uploaderUrl);
        final String name = normalizeName(uploaderName);
        return (url != null && excludedUrls.contains(url))
                || (name != null && excludedNames.contains(name));
    }

    /**
     * @param uploaderUrl  a channel URL
     * @param uploaderName a channel name
     * @param entry        a stored channel entry
     * @return true if that entry is the one recording this channel, so removing a channel
     *         takes out the entry that was matching it rather than one that merely looks alike
     */
    static boolean entryIsChannel(@Nullable final String uploaderUrl,
                                  @Nullable final String uploaderName,
                                  @NonNull final String entry) {
        final String url = normalizeUrl(uploaderUrl);
        final String name = normalizeName(uploaderName);
        final String[] parts = split(entry);
        return (url != null && url.equals(parts[0]))
                || (name != null && name.equals(parts[1]));
    }

    /**
     * @param url a channel URL
     * @return it with the scheme, a {@code www.}/{@code m.} prefix and any trailing slashes
     *         removed, lowercased; null when there is nothing usable. Comparing raw strings
     *         would miss {@code http} vs {@code https} and the {@code m.youtube.com} host some
     *         extractor paths produce, and a user cannot see why their exclusion did nothing.
     */
    @Nullable
    static String normalizeUrl(@Nullable final String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String out = url.trim();
        final int scheme = out.indexOf("://");
        if (scheme >= 0) {
            out = out.substring(scheme + 3);
        }
        if (out.startsWith("www.") || out.startsWith("m.")) {
            out = out.substring(out.indexOf('.') + 1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out.isEmpty() ? null : out.toLowerCase(Locale.US);
    }

    /**
     * @param name a channel's display name
     * @return it trimmed and lowercased, or null if blank
     */
    @Nullable
    static String normalizeName(@Nullable final String name) {
        if (name == null) {
            return null;
        }
        final String out = name.trim().toLowerCase(Locale.US);
        return out.isEmpty() ? null : out;
    }

    /**
     * @param videoId the video
     * @param title   its title, as the uploader wrote it
     * @return the stored form of the label, kept apart from the id set so that the hot-path
     *         lookup stays a plain {@code Set.contains} instead of a scan that splits strings
     */
    @NonNull
    static String encodeVideoLabel(@NonNull final String videoId, @Nullable final String title) {
        return videoId + FIELD_SEPARATOR + (title == null ? "" : title);
    }

    /**
     * @param videoId the video
     * @param labels  stored label entries
     * @return the title stored for it, or the id itself when none was recorded
     */
    @NonNull
    static String labelFor(@NonNull final String videoId, @NonNull final Set<String> labels) {
        for (final String entry : labels) {
            final String[] parts = split(entry);
            if (parts[0].equals(videoId) && !parts[1].isEmpty()) {
                return parts[1];
            }
        }
        return videoId;
    }

    /**
     * @param videoId the video
     * @param entry   a stored label entry
     * @return whether that entry is this video's label
     */
    static boolean labelIsFor(@NonNull final String videoId, @NonNull final String entry) {
        return split(entry)[0].equals(videoId);
    }
}
