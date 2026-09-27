package org.schabi.newpipe.util.dearrow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a DeArrow API response into the title and thumbnail a list row should display.
 *
 * <p>This class has no Android dependencies on purpose: all of the decision-making that is easy to
 * get wrong — which of several competing community submissions wins, when a replacement is
 * suppressed by a setting, how a SHOUTING title is normalised — lives here and is covered by
 * ordinary JVM unit tests in {@code DeArrowParserTest}. Everything Android-specific (caching,
 * threading, view binding) lives in {@link DeArrowCache} and {@link DeArrowBinder} and holds no
 * logic worth testing.</p>
 *
 * <p>The selection rules mirror the DeArrow browser extension so that a video looks the same here
 * as it does on the desktop:</p>
 * <ol>
 *   <li>Submissions marked {@code removed}, or with negative {@code votes}, are discarded.</li>
 *   <li>A winning submission marked {@code original} is the community voting to keep what the
 *       uploader chose, so it yields no replacement rather than promoting the runner-up.</li>
 *   <li>Of what is left, a {@code locked} submission (pinned by a moderator) always wins;
 *       otherwise the highest {@code votes} wins.</li>
 *   <li>If nothing survives, the uploader's own title/thumbnail is kept.</li>
 * </ol>
 */
public final class DeArrowParser {

    /**
     * Acronyms that survive auto-formatting even though they read as ordinary words by length.
     * Deliberately short and YouTube-flavoured rather than exhaustive: an unlisted acronym is
     * merely lowercased, which is a far milder failure than leaving a whole title shouting.
     */
    private static final Set<String> ACRONYMS = new HashSet<>(Arrays.asList(
            "AI", "API", "AR", "VR", "CEO", "CPU", "GPU", "RAM", "SSD", "USB", "HDMI",
            "NASA", "ESA", "FBI", "CIA", "NSA", "UN", "EU", "UK", "USA", "US", "UAE",
            "NFL", "NBA", "MLB", "NHL", "FIFA", "UFC", "NCAA", "NATO",
            "HD", "FPS", "RPG", "FPV", "DIY", "ASMR", "IRL", "POV", "TV",
            "HTML", "CSS", "SQL", "JSON", "HTTP", "HTTPS", "PC", "OS", "SDK",
            "DNA", "RNA", "LED", "UFO", "GPS", "PDF", "USSR", "EV"));

    /**
     * The longest a vowel-less word may be and still be treated as an acronym.
     */
    @VisibleForTesting
    static final int MAX_ACRONYM_LENGTH = 5;

    /**
     * Auto-formatting only fires when at least this fraction of the eligible words are shouting,
     * so a title that merely contains an acronym or an emphasised word is not rewritten.
     */
    private static final double SHOUTING_THRESHOLD = 0.5;

    /** Number of leading hex characters of the videoId hash that address an API bucket. */
    @VisibleForTesting
    static final int HASH_PREFIX_LENGTH = 4;

    private DeArrowParser() {
    }

    /**
     * Parses a hash-prefix bucket response and returns the branding for one video in it.
     *
     * <p>The bucket endpoint returns every video whose id hashes into the same prefix — typically
     * over a hundred of them — which is what keeps the API from learning which video is being
     * watched. Callers are expected to cache the whole bucket; this method extracts one entry.</p>
     *
     * @param json    the raw response body from {@code /api/branding/{prefix}}
     * @param videoId the video to extract
     * @param config  the user's settings
     * @return the branding to display, or {@link DeArrowBranding#NONE} if this bucket says
     *         nothing about this video
     * @throws DeArrowParseException if the body is not a JSON object of branding objects
     */
    @NonNull
    public static DeArrowBranding parseBucketEntry(@NonNull final String json,
                                                   @NonNull final String videoId,
                                                   @NonNull final DeArrowConfig config)
            throws DeArrowParseException {
        if (!config.isEnabled()) {
            return DeArrowBranding.NONE;
        }
        final JsonObject bucket = parseObject(json);
        if (!bucket.has(videoId)) {
            return DeArrowBranding.NONE;
        }
        final JsonObject branding = bucket.getObject(videoId);
        if (branding == null) {
            return DeArrowBranding.NONE;
        }
        return parseBranding(branding, videoId, config);
    }

    /**
     * Parses a single-video response from {@code /api/branding?videoID=...}.
     *
     * @param json    the raw response body
     * @param videoId the video the response is about, needed to build the thumbnail URL
     * @param config  the user's settings
     * @return the branding to display, never null
     * @throws DeArrowParseException if the body is not a JSON object
     */
    @NonNull
    public static DeArrowBranding parseSingle(@NonNull final String json,
                                              @NonNull final String videoId,
                                              @NonNull final DeArrowConfig config)
            throws DeArrowParseException {
        if (!config.isEnabled()) {
            return DeArrowBranding.NONE;
        }
        return parseBranding(parseObject(json), videoId, config);
    }

    /**
     * Applies the selection rules to one already-parsed branding object.
     *
     * @param branding a {@code {titles: [...], thumbnails: [...], randomTime: n}} object
     * @param videoId  the video the object is about
     * @param config   the user's settings
     * @return the branding to display, never null
     */
    @NonNull
    @VisibleForTesting
    static DeArrowBranding parseBranding(@NonNull final JsonObject branding,
                                         @NonNull final String videoId,
                                         @NonNull final DeArrowConfig config) {
        final String title = config.shouldReplaceTitles()
                ? selectTitle(branding, config)
                : null;
        final String thumbnailUrl = config.shouldReplaceThumbnails()
                ? selectThumbnailUrl(branding, videoId, config)
                : null;
        if (title == null && thumbnailUrl == null) {
            return DeArrowBranding.NONE;
        }
        return new DeArrowBranding(title, thumbnailUrl);
    }

    @Nullable
    private static String selectTitle(@NonNull final JsonObject branding,
                                      @NonNull final DeArrowConfig config) {
        final JsonObject best = bestSubmission(branding.getArray("titles"));
        // A winning submission marked "original" is the community voting to KEEP the uploader's
        // title, so the correct result is no replacement -- not the runner-up.
        if (best == null || best.getBoolean("original", false)) {
            return null;
        }
        final String raw = best.getString("title");
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        // DeArrow escapes a leading ">" to mean "this word is deliberately capitalised";
        // strip the marker before it reaches a TextView.
        final String cleaned = raw.replace(">", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return config.shouldAutoFormatTitles() ? autoFormatTitle(cleaned) : cleaned;
    }

    @Nullable
    private static String selectThumbnailUrl(@NonNull final JsonObject branding,
                                             @NonNull final String videoId,
                                             @NonNull final DeArrowConfig config) {
        final JsonObject best = bestSubmission(branding.getArray("thumbnails"));
        Double timestamp = null;
        // As with titles, a winning "original" submission means keep the uploader's thumbnail.
        // Original entries also carry a null timestamp, so this guards the unboxing below.
        if (best != null && !best.getBoolean("original", false)
                && best.get("timestamp") instanceof Number) {
            timestamp = best.getDouble("timestamp");
        }
        // NOTE: there is deliberately no `randomTime` fallback here any more.
        //
        // It used to synthesise a thumbnail-server URL for every video that had a bucket
        // entry at all, and hand it to the image loader. The server only serves frames it
        // already holds, so for most videos it answers HTTP 204 — and an image loader given
        // a 204 paints its placeholder, which is a GREY BOX WITH A PLAY ARROW, over a row
        // that had a perfectly good thumbnail a moment earlier (reported on a real device,
        // 2026-09-27). The feature actively made the screen worse, and only for videos
        // DeArrow knows about, which is why it looked sporadic.
        //
        // A frame at the seeded timestamp is now produced locally and instantly by
        // DeArrowAutoThumbnail, so nothing is lost by refusing to guess a server URL. What
        // remains here is only a genuine community submission.
        if (timestamp == null) {
            return null;
        }
        return buildThumbnailUrl(videoId, timestamp, config);
    }

    /**
     * Builds the thumbnail-server URL for a frame.
     *
     * @param videoId   the video
     * @param timestamp seconds into the video, as the API reported it
     * @param config    supplies the thumbnail host, which may be a self-hosted mirror
     * @return an absolute URL suitable for the image loader
     */
    @NonNull
    @VisibleForTesting
    static String buildThumbnailUrl(@NonNull final String videoId,
                                    final double timestamp,
                                    @NonNull final DeArrowConfig config) {
        // Locale-independent, non-scientific formatting: the server rejects "3,92349" and "3.9E0".
        final String time = BigDecimal.valueOf(timestamp).stripTrailingZeros().toPlainString();
        return config.getThumbnailApiUrl() + "?videoID=" + videoId + "&time=" + time;
    }

    /**
     * Picks the winning submission from a DeArrow submission array.
     *
     * @param submissions the {@code titles} or {@code thumbnails} array; may be null
     * @return the winning submission -- which may be an "original" one, meaning the community
     *         voted to keep what the uploader chose; callers must check for that
     */
    @Nullable
    private static JsonObject bestSubmission(@Nullable final JsonArray submissions) {
        if (submissions == null || submissions.isEmpty()) {
            return null;
        }
        return submissions.stream()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .filter(s -> !s.getBoolean("removed", false))
                .filter(s -> s.getInt("votes", 0) >= 0)
                .max(Comparator
                        .comparing((JsonObject s) -> s.getBoolean("locked", false))
                        .thenComparingInt(s -> s.getInt("votes", 0))
                        // Equal votes do happen. Breaking the tie on UUID is arbitrary but
                        // deterministic, which is what matters: the same video must not show a
                        // different title on two devices, or on two runs of the test suite.
                        .thenComparing(s -> s.getString("UUID", "")))
                .orElse(null);
    }

    /**
     * Normalises a SHOUTING title to sentence case, leaving acronyms and non-shouting titles
     * untouched.
     *
     * <p>Only fires when most of the title is already in caps, so "The truth about NASA" is left
     * exactly as written while "THE TRUTH ABOUT NASA" becomes "The truth about NASA".</p>
     *
     * <p><b>On acronyms.</b> Telling NASA from THIS is not something a rule can do — both are
     * four capital letters. Upstream DeArrow ships an English dictionary and keeps any all-caps
     * word that is <em>not</em> a real word; that is the correct approach and is too much weight
     * for this feature. Instead, a word survives in capitals only if it is in {@link #ACRONYMS}
     * or has no vowels ({@code GPU}, {@code HTML}, {@code NFL}). The failure mode is deliberately
     * the mild one: an unlisted acronym gets lowercased, rather than every short word in the
     * title being left shouting.</p>
     *
     * @param title the selected community title
     * @return the title as it should appear on screen
     */
    @NonNull
    @VisibleForTesting
    static String autoFormatTitle(@NonNull final String title) {
        final String[] words = title.split(" ");
        int eligible = 0;
        int shouting = 0;
        for (final String word : words) {
            final String core = coreOf(word);
            if (core.length() < 2) {
                continue;
            }
            eligible++;
            if (core.equals(core.toUpperCase(Locale.ROOT))) {
                shouting++;
            }
        }
        if (eligible == 0 || (double) shouting / eligible < SHOUTING_THRESHOLD) {
            return title;
        }

        final StringBuilder out = new StringBuilder(title.length());
        boolean firstWordWritten = false;
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            final String word = words[i];
            final String core = coreOf(word);
            if (core.isEmpty() || !core.equals(core.toUpperCase(Locale.ROOT))) {
                // Punctuation, a number, or a word that was not shouting in the first place.
                out.append(word);
                firstWordWritten |= !core.isEmpty();
                continue;
            }
            if (isAcronym(core)) {
                // Keep the acronym, but lowercase a possessive: NASA'S -> NASA's.
                out.append(word.replace("'S", "'s").replace("’S", "’s"));
                firstWordWritten = true;
                continue;
            }
            final String lowered = word.toLowerCase(Locale.ROOT);
            // Only the first real word of the sentence keeps its capital.
            out.append(firstWordWritten
                    ? lowered
                    : Character.toUpperCase(lowered.charAt(0)) + lowered.substring(1));
            firstWordWritten = true;
        }
        return out.toString();
    }

    /**
     * Strips surrounding punctuation and a possessive suffix, so that {@code "NASA'S}
     * and {@code NASA} are judged the same way.
     *
     * @param word one space-separated token of the title
     * @return the letters at the heart of it, possibly empty
     */
    @NonNull
    private static String coreOf(@NonNull final String word) {
        int start = 0;
        int end = word.length();
        while (start < end && !Character.isLetter(word.charAt(start))) {
            start++;
        }
        while (end > start && !Character.isLetter(word.charAt(end - 1))) {
            end--;
        }
        final String trimmed = word.substring(start, end);
        // Drop a possessive so NASA'S is measured as NASA, not as a mixed-case word.
        if (trimmed.length() > 2 && (trimmed.endsWith("'S") || trimmed.endsWith("’S")
                || trimmed.endsWith("'s") || trimmed.endsWith("’s"))) {
            return trimmed.substring(0, trimmed.length() - 2);
        }
        return trimmed;
    }

    /**
     * @param core an all-caps word, already stripped of punctuation
     * @return true if it should stay capitalised
     */
    private static boolean isAcronym(@NonNull final String core) {
        if (ACRONYMS.contains(core)) {
            return true;
        }
        if (core.length() > MAX_ACRONYM_LENGTH) {
            return false;
        }
        // No vowels means it cannot be read as an English word: GPU, HTML, NFL, TV.
        for (int i = 0; i < core.length(); i++) {
            if ("AEIOUY".indexOf(core.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes the API bucket a video falls into.
     *
     * <p>Requesting the bucket rather than the video is what stops the DeArrow server from
     * learning which video is being watched: the prefix is shared by hundreds of videos. This is
     * the same privacy model PipePipe's SponsorBlock integration already uses.</p>
     *
     * @param videoId the video id, exactly as YouTube spells it
     * @return the first {@value #HASH_PREFIX_LENGTH} hex characters of its SHA-256 hash
     * @throws IllegalStateException if the JVM has no SHA-256, which cannot happen on Android
     */
    @NonNull
    public static String hashPrefix(@NonNull final String videoId) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
        final byte[] hash = digest.digest(videoId.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder(HASH_PREFIX_LENGTH);
        for (int i = 0; hex.length() < HASH_PREFIX_LENGTH && i < hash.length; i++) {
            hex.append(String.format(Locale.ROOT, "%02x", hash[i]));
        }
        return hex.substring(0, HASH_PREFIX_LENGTH);
    }

    /**
     * Builds the bucket URL for a video.
     *
     * @param videoId the video to look up
     * @param config  supplies the API base, which may be a self-hosted mirror
     * @return an absolute URL
     */
    @NonNull
    public static String buildBucketUrl(@NonNull final String videoId,
                                        @NonNull final DeArrowConfig config) {
        return config.getApiUrl() + "/api/branding/" + hashPrefix(videoId);
    }

    /**
     * Parses a whole bucket response into branding for every video in it.
     *
     * <p>This exists so the JSON is parsed <em>once</em> per response. Calling
     * {@link #parseBucketEntry} in a loop instead re-parses the entire body for every video,
     * and a bucket holds well over a hundred of them — quadratic work on a 17 KB payload, which
     * on a scrolling feed saturated the CPU and produced an "isn't responding" dialog on a real
     * device (2026-09-23). Unit tests could never have caught that; only running it could.</p>
     *
     * @param json   the raw bucket response body
     * @param config the user's settings
     * @return video id to branding, for every video the bucket describes
     * @throws DeArrowParseException if the body is not a JSON object
     */
    @NonNull
    public static Map<String, DeArrowBranding> parseBucket(@NonNull final String json,
                                                           @NonNull final DeArrowConfig config)
            throws DeArrowParseException {
        final JsonObject bucket = parseObject(json);
        final Map<String, DeArrowBranding> out = new LinkedHashMap<>(bucket.size());
        for (final Map.Entry<String, Object> entry : bucket.entrySet()) {
            if (!(entry.getValue() instanceof JsonObject)) {
                continue;
            }
            out.put(entry.getKey(), config.isEnabled()
                    ? parseBranding((JsonObject) entry.getValue(), entry.getKey(), config)
                    : DeArrowBranding.NONE);
        }
        return out;
    }

    /**
     * Lists every video id present in a bucket response.
     *
     * @param json the raw bucket response body
     * @return the video ids in the bucket
     * @throws DeArrowParseException if the body is not a JSON object
     */
    @NonNull
    public static List<String> bucketVideoIds(@NonNull final String json)
            throws DeArrowParseException {
        return new ArrayList<>(parseObject(json).keySet());
    }

    @NonNull
    private static JsonObject parseObject(@NonNull final String json)
            throws DeArrowParseException {
        try {
            return JsonParser.object().from(json);
        } catch (final JsonParserException e) {
            throw new DeArrowParseException("DeArrow response was not a JSON object", e);
        }
    }
}
