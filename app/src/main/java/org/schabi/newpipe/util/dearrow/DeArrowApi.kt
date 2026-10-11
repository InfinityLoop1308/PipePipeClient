package org.schabi.newpipe.util.dearrow

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * The DeArrow API: hash-prefix bucket URLs, response parsing and submission selection.
 *
 * The bucket endpoint returns every video whose id hashes into the same four-hex-character
 * prefix, so one request answers ~130 videos and the server never learns which one was watched.
 *
 * Selection mirrors the DeArrow browser extension: removed and downvoted submissions are
 * dropped, a winning `locked` submission beats vote count, and a winning `original` submission
 * means "keep what the uploader chose".
 */
object DeArrowApi {
    /** The official branding API, shared with SponsorBlock. */
    const val API_URL = "https://sponsor.ajay.app"

    /** The official thumbnail renderer. Separate host from the branding API. */
    const val THUMBNAIL_URL = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail"

    private const val HASH_PREFIX_LENGTH = 4

    @JvmStatic
    fun hashPrefix(videoId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(videoId.toByteArray(StandardCharsets.UTF_8))
        val hex = StringBuilder(HASH_PREFIX_LENGTH)
        var i = 0
        while (hex.length < HASH_PREFIX_LENGTH && i < hash.size) {
            hex.append(String.format(Locale.ROOT, "%02x", hash[i]))
            i++
        }
        return hex.substring(0, HASH_PREFIX_LENGTH)
    }

    fun bucketUrl(videoId: String): String = "$API_URL/api/branding/${hashPrefix(videoId)}"

    /** A URL for a community-submitted frame at the given second of the video. */
    fun thumbnailUrl(videoId: String, timestamp: Double): String {
        // Locale-independent, non-scientific formatting: the server rejects "3,92349" and "3.9E0".
        val time = BigDecimal.valueOf(timestamp).stripTrailingZeros().toPlainString()
        return "$THUMBNAIL_URL?videoID=$videoId&time=$time"
    }

    /**
     * A URL that asks the server to render a frame for a video it has none cached for.
     *
     * No timestamp: the server picks one (and for a live broadcast there is none to seek into).
     * Used as the last resort for a live broadcast, whose upload has no stored frames, and for an
     * upload too fresh to have been processed yet.
     */
    fun renderedThumbnailUrl(videoId: String): String =
        "$THUMBNAIL_URL?videoID=$videoId&generateNow=true"

    /**
     * Parses a whole bucket response.
     *
     * @throws com.grack.nanojson.JsonParserException if the body is not a JSON object
     */
    fun parseBucket(json: String): Map<String, DeArrowBranding> {
        val root = JsonParser.`object`().from(json)
        val out = HashMap<String, DeArrowBranding>(root.size)
        for ((videoId, value) in root) {
            if (value is JsonObject) {
                out[videoId] = parseBranding(value)
            }
        }
        return out
    }

    private fun parseBranding(branding: JsonObject): DeArrowBranding =
        DeArrowBranding(selectTitle(branding), selectThumbnailTimestamp(branding))

    private fun selectTitle(branding: JsonObject): String? {
        val best = bestSubmission(branding.getArray("titles")) ?: return null
        // A winning "original" submission is the community voting to keep the uploader's title.
        if (best.getBoolean("original", false)) return null
        // DeArrow escapes a leading ">" to mark a deliberately capitalised word; strip it.
        val title = best.getString("title")?.replace(">", "")?.trim()
        return title?.ifEmpty { null }
    }

    private fun selectThumbnailTimestamp(branding: JsonObject): Double? {
        val best = bestSubmission(branding.getArray("thumbnails")) ?: return null
        if (best.getBoolean("original", false)) return null
        return (best["timestamp"] as? Number)?.toDouble()
    }

    private fun bestSubmission(submissions: JsonArray?): JsonObject? {
        if (submissions == null || submissions.isEmpty()) return null
        return submissions.asSequence()
            .filterIsInstance<JsonObject>()
            .filter { !it.getBoolean("removed", false) }
            .filter { it.getInt("votes", 0) >= 0 }
            .maxWithOrNull(
                compareBy<JsonObject> { it.getBoolean("locked", false) }
                    .thenBy { it.getInt("votes", 0) }
                    // Equal votes happen; a deterministic tie-break keeps two devices in sync.
                    .thenBy { it.getString("UUID", "") }
            )
    }
}
