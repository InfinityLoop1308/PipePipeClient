package org.schabi.newpipe.player

import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.player.helper.PlayerHelper
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Owns the auto-queue feature: appending the next related stream when the queue is about to run
 * out, the round-play part chain detection and the delayed enqueue timer. The scheduled executor
 * and the pending [Future] live here, so [Player] only has to cancel the timer on teardown.
 */
class AutoQueueController(private val player: Player) {

    private val context = player.context
    private val prefs = player.prefs

    private var enqueueTimer: Future<*>? = null
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    /** Splits a part url into (everything before the p parameter, part number). */
    private fun parsePart(url: String): Pair<String, Int>? {
        val match = PART_REGEX.find(url) ?: return null
        val page = match.groupValues[2].toIntOrNull() ?: return null
        return match.groupValues[1] to page
    }

    fun cancelEnqueueTimer() {
        enqueueTimer?.cancel(true)
    }

    fun maybeAutoQueueNextStream(info: StreamInfo, forceEnqueue: Boolean) {
        val playQueue = player.playQueue ?: return

        val partitions: List<StreamInfoItem> = info.partitions
        if (partitions.size > 1 && playQueue.index == playQueue.size() - 1) {
            val queueParts = playQueue.streams.map { parsePart(it.url) }.filterNotNull()
            val p = parsePart(info.url)?.second
            if (p != null
                && queueParts.size == playQueue.size()
                && queueParts.zipWithNext().all { (a, b) -> a.first == b.first && a.second + 1 == b.second }
                && partitions.size > p
            ) {
                playQueue.appendAutoQueued(
                    PlayerHelper.getAutoQueuedSinglePlayQueue(partitions[p]).streams
                )
            }
        }

        if (!forceEnqueue && (playQueue.index != playQueue.size() - 1
                || player.repeatMode != RepeatMode.OFF
                || !PlayerHelper.isAutoQueueEnabled(context))) {
            return
        }

        val dontAutoQueueLongVideos = prefs.getBoolean(
            context.getString(R.string.dont_auto_queue_long_key), true
        )

        // auto queue when starting playback on the last item when not repeating
        val autoQueue = PlayerHelper.autoQueueOf(info, playQueue.streams, dontAutoQueueLongVideos)
        autoQueue?.let { playQueue.appendAutoQueued(it.streams) }
    }

    /**
     * Schedules the auto-queue of the next part of a round-play stream when playback is about to
     * end. The scheduling is only done if there is no pending enqueue timer.
     *
     * @param streamInfo the current stream info, which must be a round-play stream
     */
    fun scheduleRoundPlayAutoQueue(streamInfo: StreamInfo) {
        val timer = enqueueTimer
        if (timer == null || timer.isDone || timer.isCancelled) {
            enqueueTimer = executor.schedule(
                { maybeAutoQueueNextStream(streamInfo, true) },
                Math.max(player.duration - player.currentPosition - 1000, 0),
                MILLISECONDS,
            )
        }
    }

    private companion object {
        val PART_REGEX = Regex("^(.*?)[?&]p=(\\d+)")
    }
}
