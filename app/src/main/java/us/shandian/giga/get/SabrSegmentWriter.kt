package us.shandian.giga.get

import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrSelfInitializingSegment
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline
import java.io.IOException
import java.io.OutputStream

internal class SabrSegmentWriter(
    private val targets: List<SabrDownloadTarget>,
    private val outputs: Map<Int, OutputStream>,
    private val onBytesWritten: (SabrDownloadTarget, Long) -> Unit,
    private val postLive: Boolean,
) {
    @Throws(IOException::class)
    fun acceptSegment(segment: SabrMediaSegment) {
        val target = targets.firstOrNull {
            it.format.itag == segment.header.itag &&
                it.format.xtags == segment.header.xtags
        }
        if (target == null) {
            segment.delete()
            return
        }
        try {
            if (segment.header.isInitSegment) {
                writeInitializationSegment(
                    target,
                    outputs.getValue(target.resourceIndex),
                    segment.data,
                )
            } else {
                writeMediaSegment(target, outputs.getValue(target.resourceIndex), segment)
            }
        } finally {
            segment.delete()
        }
    }

    fun observeWrittenInitializations() {
        for (target in targets) {
            val data = target.initializationData ?: continue
            if (target.timeline == null && !postLive) {
                target.timeline = YoutubeSabrFormatTimeline.parse(target.format, data)
            }
        }
    }

    @Throws(IOException::class)
    fun writeInitializationData(target: SabrDownloadTarget, data: ByteArray): Boolean {
        return writeInitializationSegment(target, outputs.getValue(target.resourceIndex), data)
    }

    @Throws(IOException::class)
    private fun writeInitializationSegment(
        target: SabrDownloadTarget,
        output: OutputStream,
        data: ByteArray,
    ): Boolean {
        if (target.initializationWritten) {
            return false
        }
        writeToStorage(output, data)
        target.initializationWritten = true
        target.initializationData = data
        // A post-live initialization describes the live encoder state, not the DVR window.
        if (!postLive) {
            target.timeline = YoutubeSabrFormatTimeline.parse(target.format, data)
        }
        onBytesWritten(target, data.size.toLong())
        if (target.timeline != null) {
            flushPendingMedia(target, output)
        }
        return true
    }

    /**
     * Installs the post-live DVR timeline built from the live metadata, then writes the media
     * that arrived before it was known.
     */
    fun installPostLiveTimeline(target: SabrDownloadTarget, timeline: YoutubeSabrFormatTimeline) {
        if (target.timeline != null) {
            return
        }
        target.timeline = timeline
        target.nextWriteSequence = if (target.resumed) {
            target.nextWriteSequence.coerceAtLeast(timeline.startSequence)
        } else {
            timeline.startSequence
        }
        while (target.pending.isNotEmpty() && target.pending.firstKey() < target.nextWriteSequence) {
            val dropped = target.pending.pollFirstEntry()!!.value
            target.pendingBytes = (target.pendingBytes - dropped.size).coerceAtLeast(0)
        }
        if (target.initializationWritten) {
            flushPendingMedia(target, outputs.getValue(target.resourceIndex))
        }
    }

    @Throws(IOException::class)
    private fun writeMediaSegment(
        target: SabrDownloadTarget,
        output: OutputStream,
        segment: SabrMediaSegment,
    ) {
        if (postLive) {
            writePostLiveMediaSegment(target, output, segment)
            return
        }
        val sequence = segment.header.sequenceNumber
        if (sequence < target.nextWriteSequence) {
            return
        }
        if (!target.initializationWritten) {
            cachePendingMedia(target, sequence, segment.data, "waiting for initialization")
            return
        }
        if (sequence > target.nextWriteSequence) {
            cachePendingMedia(target, sequence, segment.data, "waiting for sequence ${target.nextWriteSequence}")
            return
        }
        writeMediaSegmentBytes(target, output, segment)
        flushPendingMedia(target, output)
    }

    /**
     * Post-live SABR has no separate initialization: every media segment starts with its own
     * `ftyp`+`moov`. The first copy becomes the track initialization and is dropped from every
     * segment, so the temporary file is one initialization followed by plain fragments.
     */
    private fun writePostLiveMediaSegment(
        target: SabrDownloadTarget,
        output: OutputStream,
        segment: SabrMediaSegment,
    ) {
        val data = segment.data
        val initializationEnd = SabrSelfInitializingSegment.initializationEnd(data)
        val media = if (initializationEnd > 0) {
            if (!target.initializationWritten) {
                writeInitializationSegment(target, output, data.copyOfRange(0, initializationEnd))
            }
            data.copyOfRange(initializationEnd, data.size)
        } else {
            data
        }
        val sequence = segment.header.sequenceNumber
        if (target.timeline == null) {
            cachePendingMedia(target, sequence, media, "waiting for post-live timeline")
            return
        }
        if (sequence < target.nextWriteSequence) {
            return
        }
        if (!target.initializationWritten) {
            cachePendingMedia(target, sequence, media, "waiting for initialization")
            return
        }
        if (sequence > target.nextWriteSequence) {
            cachePendingMedia(target, sequence, media, "waiting for sequence ${target.nextWriteSequence}")
            return
        }
        writeMediaBytes(target, output, media)
        flushPendingMedia(target, output)
    }

    private fun flushPendingMedia(target: SabrDownloadTarget, output: OutputStream) {
        while (true) {
            val pending = target.pending.remove(target.nextWriteSequence) ?: return
            target.pendingBytes = (target.pendingBytes - pending.size).coerceAtLeast(0)
            writeMediaBytes(target, output, pending)
        }
    }

    private fun cachePendingMedia(
        target: SabrDownloadTarget,
        sequence: Int,
        data: ByteArray,
        reason: String,
    ) {
        val previous = target.pending.put(sequence, data)
        if (previous != null) {
            target.pendingBytes -= previous.size.toLong()
        }
        target.pendingBytes += data.size.toLong()
        if (target.pending.size > MAX_PENDING_SEGMENTS
            || target.pendingBytes > MAX_PENDING_BYTES
        ) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.STALLED,
                "SABR download stalled while writing itag ${target.format.itag}: $reason"
                    + " (${target.pending.size} pending segments, ${target.pendingBytes} bytes)",
            )
        }
    }

    private fun writeMediaBytes(target: SabrDownloadTarget, output: OutputStream, data: ByteArray) {
        writeToStorage(output, data)
        target.nextWriteSequence++
        onBytesWritten(target, data.size.toLong())
    }

    private fun writeMediaSegmentBytes(
        target: SabrDownloadTarget,
        output: OutputStream,
        segment: SabrMediaSegment,
    ) {
        try {
            segment.openStream().use { input ->
                input.copyTo(output)
            }
        } catch (error: IOException) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.STORAGE,
                "SABR download failed: could not write temporary media",
                error,
            )
        }
        target.nextWriteSequence++
        onBytesWritten(target, segment.length.toLong())
    }

    private fun writeToStorage(output: OutputStream, data: ByteArray) {
        try {
            output.write(data)
        } catch (error: IOException) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.STORAGE,
                "SABR download failed: could not write temporary media",
                error,
            )
        }
    }

    private companion object {
        private const val MAX_PENDING_SEGMENTS = 64
        private const val MAX_PENDING_BYTES = 24L * 1024L * 1024L
    }
}
