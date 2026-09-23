package org.schabi.newpipe.player

import android.util.Log
import android.view.View
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.source.TrackGroup
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Owns the resolution selection of live streams.
 *
 * A live stream is played as one adaptive DASH/HLS source whose variants are only known once
 * ExoPlayer has parsed the manifest, so unlike the video-on-demand path (which picks a single
 * [org.schabi.newpipe.extractor.stream.VideoStream] and re-prepares the source) the selection
 * here is a track-selection override applied to the tracks the player currently exposes.
 * The user's choice is stored as a [Format] fingerprint: on every `onTracksChanged` (including
 * the periodic live manifest refreshes, which recreate the underlying [TrackGroup] instances and
 * silently invalidate any override bound to them) the fingerprint is matched against the fresh
 * tracks and the override is applied again. A "null" choice keeps the default adaptive
 * behaviour, matching the "Auto" entry of the quality menu.
 */
class LiveQualityController(private val player: Player) {

    data class Option(
        val height: Int,
        val width: Int,
        val frameRate: Int,
        val codec: String,
        val hdr: Boolean,
    ) {
        val label: String
            get() {
                val builder = StringBuilder()
                if (codec.isNotEmpty()) {
                    builder.append(codec).append(' ')
                }
                builder.append(height).append('p')
                if (frameRate > 30) {
                    builder.append(frameRate)
                }
                if (hdr) {
                    builder.append(" HDR")
                }
                return builder.toString()
            }
    }

    /** The resolutions offered by the live manifest, highest first. Empty when unknown. */
    var options: List<Option> = emptyList()
        private set

    /** The option the user locked playback to, null while the selection is "Adaptive". */
    private var selected: Option? = null

    /** The group/index the currently applied override points at, null when none is applied. */
    private var appliedOverride: Pair<TrackGroup, Int>? = null

    /**
     * Whether the item being played is a video live stream played from an adaptive manifest.
     *
     * A live stream whose metadata already carries a per-stream quality (e.g. a BiliBili
     * round-play room, which exposes separate streams just like video on demand) is handled by
     * the regular selection pipeline, so it must not enter the track-override one.
     */
    val isLivePlayback: Boolean
        get() = player.currentStreamInfo.orElse(null)?.streamType == StreamType.LIVE_STREAM
                && player.currentMetadata?.maybeQuality?.orElse(null) == null

    /** Menu position of the current choice: 0 is "Adaptive", 1.. are [options]. */
    val currentMenuIndex: Int
        get() = if (selected == null) 0 else options.indexOf(selected) + 1

    /** The label the quality button should carry while a live stream is playing. */
    fun labelFor(index: Int): String? {
        if (index <= 0) {
            return player.context.getString(R.string.auto)
        }
        return options.getOrNull(index - 1)?.label
    }

    //////////////////////////////////////////////////////////////////////////
    // ExoPlayer hooks
    //////////////////////////////////////////////////////////////////////////

    /**
     * Refreshes the button and the menu when the layout controller lays out a newly loaded item.
     * The manifest tracks may have arrived before the stream metadata, in which case the
     * `onTracksChanged` callback ran against the previous item and skipped this one.
     */
    fun onItemLoaded() {
        if (!isLivePlayback || player.exoPlayerIsNull()) {
            return
        }
        options = extractOptions(player.simpleExoPlayer.currentTracks)
        appliedOverride = null
        applySelectedTrack()
        updateQualityButton()
        player.menuController.buildQualityMenu()
    }

    fun onTracksChanged(tracks: Tracks) {
        if (!isLivePlayback) {
            options = emptyList()
            selected = null
            appliedOverride = null
            return
        }
        options = extractOptions(tracks)
        // the tracks were rebuilt: any override applied to the previous TrackGroups is gone
        appliedOverride = null
        applySelectedTrack()
        updateQualityButton()
        player.menuController.buildQualityMenu()
    }

    /**
     * Drops the live selection when the played item changes. The override must not leak into the
     * next stream, whose tracks (or whole player, for a video stream) are unrelated.
     */
    fun resetForNewVideo() {
        options = emptyList()
        appliedOverride = null
        if (selected != null) {
            selected = null
            clearOverride()
        }
    }

    //////////////////////////////////////////////////////////////////////////
    // User selection
    //////////////////////////////////////////////////////////////////////////

    /**
     * Selects the resolution at [index] of the quality menu, 0 being "Adaptive".
     *
     * @return the label of the new choice, or null when [index] is out of range (stale menu)
     */
    fun select(index: Int): String? {
        val newSelection = if (index <= 0) {
            null
        } else {
            options.getOrNull(index - 1) ?: return null
        }
        selected = newSelection
        applySelectedTrack()
        updateQualityButton()
        return labelFor(currentMenuIndex)
    }

    private fun applySelectedTrack() {
        if (player.exoPlayerIsNull()) {
            return
        }
        val desired = selected
        if (desired == null) {
            if (appliedOverride != null) {
                appliedOverride = null
                clearOverride()
            }
            return
        }
        val match = findTrack(player.simpleExoPlayer.currentTracks, desired)
        if (match == null) {
            // the variant is not in the current tracks (manifest being refreshed): keep the
            // selection pending, the next onTracksChanged will re-apply it
            return
        }
        if (appliedOverride?.first === match.first && appliedOverride?.second == match.second) {
            return
        }
        val builder = player.trackSelector.buildUponParameters()
        builder.setOverrideForType(TrackSelectionOverride(match.first, match.second))
        player.trackSelector.setParameters(builder)
        appliedOverride = match
    }

    private fun clearOverride() {
        if (player.exoPlayerIsNull()) {
            return
        }
        val builder = player.trackSelector.buildUponParameters()
        builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        player.trackSelector.setParameters(builder)
    }

    //////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////

    /**
     * Shows the quality button when the live manifest turned out to offer several resolutions,
     * and labels it with the current choice. The button is owned here rather than by the layout
     * because the variants only exist once ExoPlayer has parsed the manifest.
     */
    fun updateQualityButton() {
        if (!isLivePlayback || player.exoPlayerIsNull()) {
            return
        }
        val binding = player.binding ?: return
        if (options.size > 1) {
            binding.qualityTextView.text = labelFor(currentMenuIndex)
            binding.qualityTextView.visibility = View.VISIBLE
        } else {
            binding.qualityTextView.visibility = View.GONE
        }
    }

    //////////////////////////////////////////////////////////////////////////
    // Tracks parsing
    //////////////////////////////////////////////////////////////////////////

    private fun extractOptions(tracks: Tracks): List<Option> {
        val seen = LinkedHashSet<Option>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) {
                continue
            }
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (format.height <= 0) {
                    continue
                }
                seen.add(toOption(format) ?: continue)
            }
        }
        return seen.sortedWith(
            compareByDescending<Option> { it.height }
                .thenByDescending { it.frameRate }
                .thenByDescending { it.hdr }
                .thenByDescending { codecPriority(it.codec) }
        )
    }

    /**
     * Locates the track to pin to. Several tracks can normalize to one option: BiliBili live
     * lists every ladder twice (one per content-steering pathway) and keeps 蓝光 and 原画 at the
     * same 1080p resolution, differing only in bitrate. The highest bitrate match wins, so one
     * "1080p" entry selects 原画 and the CDN mirror choice becomes arbitrary.
     */
    private fun findTrack(tracks: Tracks, option: Option): Pair<TrackGroup, Int>? {
        var best: Pair<TrackGroup, Int>? = null
        var bestBitrate = -1
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) {
                continue
            }
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (toOption(format) == option) {
                    val bitrate = if (format.averageBitrate > 0) format.averageBitrate else format.bitrate
                    if (best == null || bitrate > bestBitrate) {
                        best = Pair(group.mediaTrackGroup, i)
                        bestBitrate = bitrate
                    }
                }
            }
        }
        if (best == null && Player.DEBUG) {
            Log.d(TAG, "live option $option not found in the current tracks")
        }
        return best
    }

    private fun toOption(format: Format): Option? {
        if (format.height <= 0) {
            return null
        }
        return Option(
            format.height,
            format.width,
            if (format.frameRate.isNaN() || format.frameRate <= 0) 0 else format.frameRate.roundToInt(),
            codecName(format.codecs),
            isHdr(format),
        )
    }

    /**
     * ExoPlayer 2.18 carries no [Format.hdrType]-style field, so HDR is detected from the
     * color transfer characteristics (HLG/PQ) the manifest reports, with the 10-bit VP9/AV1
     * codec profile as a fallback hint.
     */
    private fun isHdr(format: Format): Boolean {
        val colorInfo = format.colorInfo
        if (colorInfo != null) {
            val transfer = colorInfo.colorTransfer
            if (transfer == C.COLOR_TRANSFER_HLG || transfer == C.COLOR_TRANSFER_ST2084) {
                return true
            }
        }
        val codecs = format.codecs ?: return false
        return codecs.startsWith("vp9.2") ||
            Regex("av01\\.0\\.(09|1[0-3])M", RegexOption.IGNORE_CASE).matches(codecs)
    }

    companion object {
        private const val TAG = "LiveQualityController"

        /** Turn ExoPlayer's codec string ("vp09.00.10.08") into a short menu name. */
        fun codecName(codecs: String?): String = when {
            codecs == null -> ""
            codecs.contains("av01", ignoreCase = true) -> "AV1"
            codecs.contains("vp9", ignoreCase = true) -> "VP9"
            codecs.contains("vp8", ignoreCase = true) -> "VP8"
            codecs.contains("avc", ignoreCase = true) || codecs.contains("h264", ignoreCase = true) ->
                "H264"
            codecs.contains("hevc", ignoreCase = true) || codecs.contains("h265", ignoreCase = true)
                || codecs.contains("hev1", ignoreCase = true) || codecs.contains("hvc1", ignoreCase = true) ->
                "HEVC"
            else -> codecs.substringBefore('.').uppercase(Locale.getDefault())
        }

        private fun codecPriority(codec: String): Int = when (codec) {
            "AV1" -> 4
            "VP9" -> 3
            "HEVC" -> 2
            "H264" -> 1
            else -> 0
        }
    }
}
