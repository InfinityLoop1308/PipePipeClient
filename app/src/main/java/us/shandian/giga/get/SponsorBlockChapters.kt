package us.shandian.giga.get

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockExtractorHelper
import org.schabi.newpipe.extractor.utils.Utils
import java.net.URLEncoder
import kotlin.math.roundToLong

/**
 * Turns SponsorBlock segments into continuous chapters: overlapping segments are split into
 * separate spans and everything between them becomes a plain chapter.
 */
internal object SponsorBlockChapters {
    private const val SKIP_ACTION = "skip"
    private const val TITLE_PREFIX = "[SponsorBlock] "
    private const val PLAIN_TITLE = "Video"

    /** The categories turned into chapters, in the order they are listed in a title. */
    private val CATEGORIES = listOf(
        "sponsor",
        "selfpromo",
        "interaction",
        "intro",
        "outro",
        "preview",
        "hook",
        "filler",
        "music_offtopic",
    )

    data class Segment(val startMs: Long, val endMs: Long, val category: String)

    data class Chapter(val startMs: Long, val endMs: Long, val title: String)

    /**
     * Only the first characters of the id's hash leave the device. Without the query parameters
     * the API would answer with the sponsor category alone.
     */
    fun requestUrl(videoId: String): String {
        val categories = CATEGORIES.joinToString(",", "[", "]") { "\"$it\"" }
        val apiUrl = SponsorBlockExtractorHelper.getApiUrl(ServiceList.YouTube.serviceId)
        return apiUrl + "skipSegments/" + Utils.toSha256(videoId).substring(0, 4) +
            "?categories=" + URLEncoder.encode(categories, "UTF-8") +
            "&actionTypes=" + URLEncoder.encode("[\"$SKIP_ACTION\"]", "UTF-8") +
            "&userAgent=Mozilla/5.0"
    }

    @Throws(JsonParserException::class)
    fun parseSegments(json: String, videoId: String): List<Segment> {
        return JsonParser.array().from(json)
            .filterIsInstance<JsonObject>()
            .filter { it.getString("videoID") == videoId }
            .flatMap { it.getArray("segments").filterIsInstance<JsonObject>() }
            .filter { it.getString("actionType") == SKIP_ACTION }
            .filter { it.getString("category") in CATEGORIES }
            .map { segment ->
                val times = segment.getArray("segment")
                Segment(
                    (times.getDouble(0) * 1000).roundToLong(),
                    (times.getDouble(1) * 1000).roundToLong(),
                    segment.getString("category"),
                )
            }
    }

    /**
     * @return chapters covering the whole video, empty when no segment lies inside of it
     */
    fun buildChapters(segments: List<Segment>, durationMs: Long): List<Chapter> {
        val inside = segments
            .map {
                it.copy(
                    startMs = it.startMs.coerceIn(0, durationMs),
                    endMs = it.endMs.coerceIn(0, durationMs),
                )
            }
            .filter { it.endMs > it.startMs }
        if (inside.isEmpty()) {
            return emptyList()
        }
        val cuts = (inside.flatMap { listOf(it.startMs, it.endMs) } + 0L + durationMs)
            .distinct()
            .sorted()
        val chapters = mutableListOf<Chapter>()
        cuts.zipWithNext { start, end ->
            val active = inside.filter { it.startMs <= start && it.endMs >= end }
            val title = titleOf(active.map { it.category })
            val previous = chapters.lastOrNull()
            if (previous?.title == title) {
                chapters[chapters.lastIndex] = previous.copy(endMs = end)
            } else {
                chapters += Chapter(start, end, title)
            }
        }
        return chapters
    }

    fun toFfmetadata(chapters: List<Chapter>): String {
        return buildString {
            append(";FFMETADATA1\n")
            chapters.forEach { chapter ->
                append("[CHAPTER]\n")
                append("TIMEBASE=1/1000\n")
                append("START=").append(chapter.startMs).append('\n')
                append("END=").append(chapter.endMs).append('\n')
                append("title=").append(chapter.title).append('\n')
            }
        }
    }

    private fun titleOf(categories: List<String>): String {
        if (categories.isEmpty()) {
            return PLAIN_TITLE
        }
        return TITLE_PREFIX + CATEGORIES.filter { it in categories }.joinToString(", ")
    }
}
