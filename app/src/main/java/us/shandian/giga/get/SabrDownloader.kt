package us.shandian.giga.get

import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.player.datasource.WebViewPoTokenProvider
import java.io.File
import java.io.IOException

internal class SabrDownloader(
    private val mission: DownloadMission,
) : Runnable {
    override fun run() {
        try {
            ensureRunning()
            val recoveries = validateRecoveryInfo()
            prepareMission()

            val info = SabrDownloadFormatResolver.resolveInfo(recoveries)

            var attempts = 0
            while (true) {
                try {
                    runSessionAttempt(info, recoveries)
                    break
                } catch (error: RetryColdStartException) {
                    attempts++
                    cleanup(mission)
                    if (attempts > MAX_COLD_START_RETRIES) {
                        throw IOException("SABR cold start did not provide initialization", error)
                    }
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: SabrProtocolException) {
            notifyErrorAndCleanup(IOException(error))
        } catch (error: Exception) {
            notifyErrorAndCleanup(error)
        }
    }

    @Throws(IOException::class, InterruptedException::class, SabrProtocolException::class)
    private fun runSessionAttempt(
        info: YoutubeSabrInfo,
        recoveries: Array<MissionRecoveryInfo>,
    ) {
        val session = YoutubeSabrSession(
            info,
            SabrDownloadFormatResolver.selectedAudioFormat(info, recoveries),
            SabrDownloadFormatResolver.selectedVideoFormat(info, recoveries),
            WebViewPoTokenProvider(mission.context),
        )
        configureRequestMode(session)

        val workDir = prepareWorkDirectory()
        val targets = SabrDownloadFormatResolver.buildTargets(info, recoveries, workDir)
        val outputs = targets.associate { target ->
            target.resourceIndex to target.file.outputStream()
        }

        try {
            downloadSegments(session, targets, SabrSegmentWriter(mission, session, targets, outputs))
        } finally {
            outputs.values.forEach { output ->
                try {
                    output.close()
                } catch (ignored: Exception) {
                    // Nothing to do.
                }
            }
        }

        ensureRunning()
        val finalBytes = SabrFfmpegMuxer(mission).remuxAndCopy(targets.map { it.file }, workDir)
        completeMission(finalBytes)
    }

    @Throws(IOException::class)
    private fun validateRecoveryInfo(): Array<MissionRecoveryInfo> {
        val recoveries = mission.recoveryInfo ?: throw IOException("Missing SABR recovery info")
        if (recoveries.size != mission.urls.size || recoveries.any { !it.isSabr }) {
            throw IOException("Mixed SABR/non-SABR missions are not supported")
        }
        return recoveries
    }

    private fun prepareMission() {
        mission.unknownLength = true
        mission.sabrStarted = true
        if (mission.done > 0) {
            mission.notifyProgress(-mission.done)
        }
        mission.done = 0
        mission.current = 0
        mission.length = mission.nearLength
        mission.writeThisToFile()
    }

    private fun configureRequestMode(session: YoutubeSabrSession) {
        // Cold starts are most reliable in the normal two-track mode; for single-track downloads we
        // switch to audio-only/video-only once the selected track initialization has been written.
        session.streamState.setVideoAndAudioRequestMode()
    }

    @Throws(IOException::class)
    private fun prepareWorkDirectory(): File {
        val workDir = workDirectory(mission)
        cleanup(mission)
        if (!workDir.mkdirs()) {
            throw IOException("Cannot create SABR work directory: $workDir")
        }
        return workDir
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun downloadSegments(
        session: YoutubeSabrSession,
        targets: List<SabrDownloadTarget>,
        writer: SabrSegmentWriter,
    ) {
        val localization = Localization("en", "US")
        writer.writeDirectInitializations()
        writer.observeWrittenInitializations()

        var emptyResponses = 0
        while (true) {
            ensureRunning()
            writer.observeWrittenInitializations()
            var wroteSegment = writer.drainCachedInitializations()
            wroteSegment = writer.drainCachedSegments() || wroteSegment
            configureInitializedSingleTargetMode(session, targets)

            if (isDownloadComplete(session, targets)) {
                break
            }

            val playerTimeMs = downloadPlayerTimeMs(session, targets)
            session.streamState.setPlayerTimeMs(playerTimeMs)
            val segments = session.pumpOnce(localization)
            writer.observeWrittenInitializations()
            wroteSegment = writer.drainCachedInitializations() || wroteSegment
            wroteSegment = writer.drainCachedSegments() || wroteSegment
            configureInitializedSingleTargetMode(session, targets)
            if (hasMediaWaitingForInitialization(targets)) {
                throw RetryColdStartException()
            }

            if (isDownloadComplete(session, targets)) {
                break
            }
            if (wroteSegment || segments.isNotEmpty()) {
                emptyResponses = 0
            } else {
                emptyResponses++
                if (emptyResponses > MAX_EMPTY_RESPONSES) {
                    throw IOException("SABR download stalled with no media")
                }
                Thread.sleep(IDLE_POLL_MS)
            }
        }
    }

    private fun configureInitializedSingleTargetMode(
        session: YoutubeSabrSession,
        targets: List<SabrDownloadTarget>,
    ) {
        if (targets.size != 1 || !targets.first().initializationWritten) {
            return
        }
        if (targets.first().format.isAudio) {
            session.streamState.setAudioOnlyRequestMode()
        } else {
            session.streamState.setVideoOnlyRequestMode()
        }
    }

    private fun hasMediaWaitingForInitialization(targets: List<SabrDownloadTarget>): Boolean {
        return targets.any { target -> !target.initializationWritten && target.pending.isNotEmpty() }
    }

    private fun downloadPlayerTimeMs(
        session: YoutubeSabrSession,
        targets: List<SabrDownloadTarget>,
    ): Long {
        if (targets.size == 1) {
            return session.streamState.getBufferedEndMs(targets.first().format)
        }
        return session.streamState.minBufferedEndMs
    }

    private fun isDownloadComplete(
        session: YoutubeSabrSession,
        targets: List<SabrDownloadTarget>,
    ): Boolean {
        return targets.all { target ->
            target.pending.isEmpty() &&
                (session.streamState.isComplete(target.format) ||
                    session.isBeyondEnd(SabrSegmentRequest.media(target.format, target.nextWriteSequence)))
        }
    }

    private fun completeMission(finalBytes: Long) {
        if (finalBytes > 0) {
            mission.done = finalBytes
            mission.length = finalBytes
        }
        mission.current = mission.urls.size
        mission.psState = 2
        cleanup(mission)
        mission.unknownLength = false
        mission.notifyFinished()
    }

    @Throws(InterruptedException::class)
    private fun ensureRunning() {
        if (!mission.running || Thread.currentThread().isInterrupted) {
            throw InterruptedException()
        }
    }

    private fun notifyErrorAndCleanup(error: Exception) {
        cleanup(mission)
        if (mission.running) {
            mission.notifyError(error)
        }
    }

    companion object {
        private const val IDLE_POLL_MS = 250L
        private const val MAX_EMPTY_RESPONSES = 60
        private const val MAX_COLD_START_RETRIES = 3

        @JvmStatic
        fun cleanup(mission: DownloadMission) {
            try {
                workDirectory(mission).deleteRecursively()
            } catch (ignored: Exception) {
                // Nothing to do.
            }
        }

        private fun workDirectory(mission: DownloadMission): File {
            val base = mission.context.getExternalFilesDir(null) ?: mission.context.filesDir
            val missionId = if (mission.timestamp > 0) {
                mission.timestamp.toString()
            } else {
                mission.storage.name.hashCode().toString()
            }
            return File(base, "sabr-downloader/$missionId")
        }
    }

    private class RetryColdStartException : IOException()
}
