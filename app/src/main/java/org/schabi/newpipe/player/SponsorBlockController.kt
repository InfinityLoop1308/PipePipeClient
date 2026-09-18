package org.schabi.newpipe.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.google.android.exoplayer2.SeekParameters
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockSegment
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.util.SponsorBlockHelper
import org.schabi.newpipe.util.SponsorBlockMode
import org.schabi.newpipe.util.SponsorBlockSecondaryMode

/**
 * Owns all SponsorBlock state and decisions: the enabled mode, the last skipped segment, the
 * un-skip grace period, the manual skip buttons and the per-category skip preferences.
 *
 * [Player] only keeps the primitives this controller cannot own: the seek, the exact seek
 * parameters and the seekbar. The decision whether a position must be skipped stays here, so
 * [Player] no longer holds any SponsorBlock state.
 */
class SponsorBlockController(private val player: Player) {

    private companion object {
        const val TAG = "SPONSOR_BLOCK"
    }

    private val context: Context = player.context
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    /** The last skipped segment, kept so that the un-skip button still works after it ended. */
    private var lastSegment: SponsorBlockSegment? = null

    /** While true, auto skipping is paused, e.g. after a graced rewind. */
    private var autoSkipGracePeriod = false

    var mode: SponsorBlockMode
        private set

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (context.getString(R.string.sponsor_block_enable_key) == key) {
                mode = if (prefs.getBoolean(key, true)) {
                    SponsorBlockMode.ENABLED
                } else {
                    SponsorBlockMode.DISABLED
                }
            }
        }

    init {
        mode = if (prefs.getBoolean(context.getString(R.string.sponsor_block_enable_key), true)) {
            SponsorBlockMode.ENABLED
        } else {
            SponsorBlockMode.DISABLED
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun setMode(newMode: SponsorBlockMode) {
        mode = newMode
        // Persist so that the global switch and the player toggle stay in sync.
        SponsorBlockHelper.setSponsorBlockMode(context, newMode)
    }

    /**
     * Called on every playback progress update. Decides whether the current position must be
     * skipped and updates the manual skip buttons accordingly.
     */
    fun onProgress(
        currentProgress: Int,
        isRewind: Boolean,
        isGracedRewind: Boolean,
        bypassSecondaryMode: Boolean,
        isUnSkip: Boolean,
    ) {
        if (mode != SponsorBlockMode.ENABLED) {
            return
        }

        val segment = findSegmentForProgress(currentProgress) ?: return
        val secondaryMode = SponsorBlockHelper.getSecondaryMode(context, segment)

        updateManualButtons(segment, currentProgress, secondaryMode)

        if (MainActivity.DEBUG) {
            Log.d(
                TAG,
                "Un-skip grace: isGracedRewind = $isGracedRewind, " +
                    "autoSkipGracePeriod = $autoSkipGracePeriod",
            )
        }

        // Temporarily pause auto skipping; an un-skip request bypasses the grace period.
        if (isGracedRewind) {
            autoSkipGracePeriod = true
        } else if (autoSkipGracePeriod) {
            return
        }

        // Prevent skip looping inside the un-skip window.
        if (lastSegment === segment && !bypassSecondaryMode) {
            return
        }

        // Do not skip in highlight mode, nor in manual mode without an explicit bypass.
        if (!SponsorBlockHelper.shouldSkipForMode(secondaryMode, bypassSecondaryMode)) {
            return
        }

        skip(segment, currentProgress, isRewind, isGracedRewind, isUnSkip)
    }

    fun onSkipClicked() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onSkipClicked() called")
        }
        autoSkipGracePeriod = false
        player.triggerProgressUpdate(false, true, true, false)
    }

    fun onUnskipClicked() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onUnskipClicked() called")
        }
        player.triggerProgressUpdate(true, true, true, true)
    }

    /** Forget the last segment so that rewinding back into it skips again. */
    fun onNonGracedRewind() {
        lastSegment = null
        autoSkipGracePeriod = false

        if (MainActivity.DEBUG) {
            Log.d(TAG, "Destroyed last segment variables (UNSKIP)")
        }
    }

    fun markSeekbarSegments(streamInfo: StreamInfo) {
        SponsorBlockHelper.markSegments(context, player.binding.playbackSeekBar, streamInfo)
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    private fun findSegmentForProgress(progress: Int): SponsorBlockSegment? {
        val info = player.currentStreamInfo.orElse(null) ?: return null
        val segments = info.sponsorBlockSegments ?: return null

        SponsorBlockHelper.getSkippableSponsorBlockSegment(segments, progress)?.let { return it }

        // Fallback on the last skipped segment, so that un-skip still works after it ended.
        val previous = lastSegment
        if (previous == null) {
            hideUnskipButtons()
            return null
        }
        if (progress > previous.endTime + SponsorBlockHelper.UNSKIP_WINDOW_MILLIS) {
            // Un-skip window is over.
            hideUnskipButtons()
            onNonGracedRewind()
            return null
        }
        if (progress < previous.endTime + SponsorBlockHelper.UNSKIP_WINDOW_MILLIS &&
            progress >= previous.startTime
        ) {
            // Use the old segment if it exists AND the current progress is in bounds.
            return previous
        }

        hideUnskipButtons()
        return null
    }

    private fun updateManualButtons(
        segment: SponsorBlockSegment,
        progress: Int,
        secondaryMode: SponsorBlockSecondaryMode,
    ) {
        val showManualButtons = prefs.getBoolean(
            context.getString(R.string.sponsor_block_show_manual_skip_key),
            false,
        )
        if (!showManualButtons || secondaryMode == SponsorBlockSecondaryMode.HIGHLIGHT) {
            return
        }

        val binding = player.binding
        binding.skipButton.visibility =
            if (SponsorBlockHelper.isInSegment(segment, progress)) View.VISIBLE else View.GONE
        binding.unskipButton.visibility =
            if (SponsorBlockHelper.isInUnskipWindow(segment, progress)) View.VISIBLE else View.GONE
    }

    private fun skip(
        segment: SponsorBlockSegment,
        currentProgress: Int,
        isRewind: Boolean,
        isGracedRewind: Boolean,
        isUnSkip: Boolean,
    ) {
        val exoPlayer = player.simpleExoPlayer ?: return
        val skipTarget = SponsorBlockHelper.calculateSkipTarget(segment, isRewind)

        // Temporarily force EXACT seek parameters to prevent infinite skip looping.
        val seekParameters = exoPlayer.seekParameters
        exoPlayer.setSeekParameters(SeekParameters.EXACT)

        player.seekTo(skipTarget.toLong())

        exoPlayer.setSeekParameters(seekParameters)

        if (!isRewind || isGracedRewind) {
            // Do not track non-graced rewinds to make them work, but always track graced ones.
            lastSegment = segment
        }

        if (isUnSkip) {
            return
        }

        val canShowNotifications = prefs.getBoolean(
            context.getString(R.string.sponsor_block_notifications_key),
            false,
        )
        if (canShowNotifications) {
            Toast.makeText(
                context,
                SponsorBlockHelper.convertCategoryToSkipMessage(context, segment.category),
                Toast.LENGTH_SHORT,
            ).show()
        }

        if (MainActivity.DEBUG) {
            Log.d(
                TAG,
                "Skipped segment: currentProgress = [$currentProgress], " +
                    "skipped to = [$skipTarget]",
            )
        }
    }

    private fun hideUnskipButtons() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "Hiding manual skip buttons (UNSKIP)")
        }
        player.binding.skipButton.visibility = View.GONE
        player.binding.unskipButton.visibility = View.GONE
    }
}
