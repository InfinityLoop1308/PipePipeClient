package org.schabi.newpipe.util.dearrow

import java.util.regex.Pattern

/**
 * Extracts a YouTube video id from the URLs the extractor produces.
 *
 * The failure mode is "DeArrow silently does nothing", so the shapes are kept explicit.
 */
object DeArrowVideoId {
    private val VIDEO_ID = Pattern.compile(
        "(?:v=|/shorts/|/embed/|/live/|youtu\\.be/)([A-Za-z0-9_-]{11})(?:[?&#]|\$)"
    )

    @JvmStatic
    fun fromUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val matcher = VIDEO_ID.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }
}
