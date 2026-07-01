package us.shandian.giga.get

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import java.io.IOException
import java.io.OutputStream

internal class SabrSegmentWriter(
    private val mission: DownloadMission,
    private val session: YoutubeSabrSession,
    private val targets: List<SabrDownloadTarget>,
    private val outputs: Map<Int, OutputStream>,
) {
    @Throws(IOException::class)
    fun writeDirectInitializations() {
        for (target in targets) {
            writeDirectInitializationIfAvailable(target, outputs.getValue(target.resourceIndex))
        }
    }

    @Throws(IOException::class)
    fun drainCachedInitializations(): Boolean {
        var wroteInitialization = false
        for (target in targets) {
            if (target.initializationWritten) {
                continue
            }
            val request = SabrSegmentRequest.initialization(target.format)
            val segment = session.getCachedSegment(request) ?: continue
            writeInitializationSegment(target, outputs.getValue(target.resourceIndex), segment.data)
            session.discardCachedSegment(request)
            Log.d(TAG, "local-sabr-init itag=${target.format.itag} bytes=${segment.data.size}")
            wroteInitialization = true
        }
        return wroteInitialization
    }

    @Throws(IOException::class)
    fun drainCachedSegments(): Boolean {
        var wroteSegment = false
        for (target in targets) {
            while (true) {
                val request = SabrSegmentRequest.media(target.format, target.nextRequestSequence)
                val segment = session.getCachedSegment(request) ?: break
                if (segment.header.isInitSegment) {
                    session.discardCachedSegment(request)
                    continue
                }
                writeMediaSegment(target, outputs.getValue(target.resourceIndex), segment)
                session.discardCachedSegment(request)
                logWrittenSegment(target, segment)
                wroteSegment = true
            }
        }
        return wroteSegment
    }

    @Throws(IOException::class)
    private fun writeDirectInitializationIfAvailable(
        target: SabrDownloadTarget,
        output: OutputStream,
    ) {
        val data = fetchDirectInitializationData(target.format) ?: return
        writeInitializationSegment(target, output, data)
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
        output.write(data)
        target.initializationWritten = true
        mission.notifyProgress(data.size.toLong())
        flushPendingMedia(target, output)
        return true
    }

    @Throws(IOException::class)
    private fun fetchDirectInitializationData(format: YoutubeSabrFormat): ByteArray? {
        val url = format.initializationUrl
        val start = format.initRangeStart
        val end = format.initRangeEnd
        if (url.isNullOrBlank() || start < 0 || end < start) {
            return null
        }
        val range = "bytes=$start-$end"
        val response = NewPipe.getDownloader().get(url, mapOf("Range" to listOf(range)))
        val data = response.rawResponseBody()
        if (response.responseCode() != 206 && response.responseCode() != 200) {
            throw IOException("Could not fetch SABR init for itag=${format.itag}: HTTP ${response.responseCode()}")
        }
        if (data == null || data.isEmpty()) {
            throw IOException("Empty SABR init for itag=${format.itag}")
        }
        return data
    }

    @Throws(IOException::class)
    private fun writeMediaSegment(
        target: SabrDownloadTarget,
        output: OutputStream,
        segment: SabrMediaSegment,
    ) {
        val sequence = segment.header.sequenceNumber
        if (sequence < target.nextWriteSequence) {
            return
        }
        if (!target.initializationWritten) {
            target.pending[sequence] = segment.data
            advanceNextRequestSequence(target, sequence)
            return
        }
        if (sequence > target.nextWriteSequence) {
            target.pending[sequence] = segment.data
            advanceNextRequestSequence(target, sequence)
            return
        }
        writeMediaBytes(target, output, segment.data)
        flushPendingMedia(target, output)
    }

    private fun flushPendingMedia(target: SabrDownloadTarget, output: OutputStream) {
        while (true) {
            val pending = target.pending.remove(target.nextWriteSequence) ?: return
            writeMediaBytes(target, output, pending)
        }
    }

    private fun writeMediaBytes(target: SabrDownloadTarget, output: OutputStream, data: ByteArray) {
        output.write(data)
        target.nextWriteSequence++
        if (target.nextRequestSequence < target.nextWriteSequence) {
            target.nextRequestSequence = target.nextWriteSequence
        }
        mission.notifyProgress(data.size.toLong())
    }

    private fun logWrittenSegment(target: SabrDownloadTarget, segment: SabrMediaSegment) {
        val sequence = segment.header.sequenceNumber
        if (sequence <= 3 || sequence % LOG_EVERY_SEGMENTS == 0) {
            Log.d(TAG, "local-sabr-write itag=${target.format.itag}"
                + " seq=$sequence"
                + " bytes=${segment.data.size}")
        }
    }

    private fun advanceNextRequestSequence(target: SabrDownloadTarget, observedSequence: Int) {
        if (observedSequence >= target.nextRequestSequence) {
            target.nextRequestSequence = observedSequence + 1
        }
    }

    private companion object {
        private const val TAG = "SabrSegmentWriter"
        private const val LOG_EVERY_SEGMENTS = 50
    }
}
