package us.shandian.giga.get

import android.util.Log
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.player.datasource.WebViewPoTokenProvider
import java.io.File
import java.io.IOException

internal class SabrDownloader(
    private val mission: DownloadMission,
) : Runnable {
    override fun run() {
        Log.d(TAG, "local-sabr-run start urls=${mission.urls.size} running=${mission.running}")
        try {
            ensureRunning()
            val recoveries = validateRecoveryInfo()
            prepareMission()

            val info = SabrDownloadFormatResolver.resolveInfo(recoveries)
            val audioRecovery = recoveries.firstOrNull { it.kind == 'a' }
            val videoRecovery = recoveries.firstOrNull { it.kind == 'v' }
            val session = YoutubeSabrSession(
                info,
                SabrDownloadFormatResolver.selectedAudioFormat(info, recoveries),
                SabrDownloadFormatResolver.selectedVideoFormat(info, recoveries),
                WebViewPoTokenProvider(mission.context),
            )
            configureRequestMode(session, audioRecovery, videoRecovery)

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
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: SabrProtocolException) {
            if (mission.running) {
                mission.notifyError(IOException(error))
            }
        } catch (error: Exception) {
            if (mission.running) {
                mission.notifyError(error)
            }
        }
    }

    @Throws(IOException::class)
    private fun validateRecoveryInfo(): Array<MissionRecoveryInfo> {
        val recoveries = mission.recoveryInfo ?: throw IOException("Missing SABR recovery info")
        Log.d(TAG, "local-sabr-run recoveries=${recoveries.size} sabr=${recoveries.count { it.isSabr }}")
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

    private fun configureRequestMode(
        session: YoutubeSabrSession,
        audioRecovery: MissionRecoveryInfo?,
        videoRecovery: MissionRecoveryInfo?,
    ) {
        when {
            videoRecovery == null -> session.streamState.setAudioOnlyRequestMode()
            audioRecovery == null -> session.streamState.setVideoOnlyRequestMode()
            else -> session.streamState.setVideoAndAudioRequestMode()
        }
    }

    @Throws(IOException::class)
    private fun prepareWorkDirectory(): File {
        val workDir = workDirectory(mission)
        cleanup(mission)
        if (!workDir.mkdirs()) {
            throw IOException("Cannot create SABR work directory: $workDir")
        }
        Log.d(TAG, "local-sabr-run workDir=$workDir")
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

        var emptyResponses = 0
        while (true) {
            ensureRunning()
            var allComplete = true
            var wroteSegment = writer.drainCachedInitializations()
            wroteSegment = writer.drainCachedSegments() || wroteSegment

            for (target in targets) {
                ensureRunning()
                val request = SabrSegmentRequest.media(target.format, target.nextRequestSequence)
                if (session.isBeyondEnd(request) || session.streamState.isComplete(target.format)) {
                    continue
                }

                allComplete = false
                session.streamState.setPlayerTimeMs(session.streamState.minBufferedEndMs)
                val segment = try {
                    session.fetchSegment(request, localization)
                } catch (error: SabrProtocolException) {
                    if (isRetryablePolicyOnly(error)) {
                        Log.d(TAG, "local-sabr-policy-idle itag=${target.format.itag}"
                            + " seq=${target.nextRequestSequence}: ${error.message}")
                        continue
                    }
                    throw error
                }

                writer.writeFetchedSegment(target, segment)
                wroteSegment = true
                wroteSegment = writer.drainCachedInitializations() || wroteSegment
                wroteSegment = writer.drainCachedSegments() || wroteSegment
            }

            if ((allComplete || targets.all { session.streamState.isComplete(it.format) })
                && targets.all { it.pending.isEmpty() }
            ) {
                break
            }
            if (wroteSegment) {
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

    private fun isRetryablePolicyOnly(error: SabrProtocolException): Boolean {
        return error.message?.contains("repeated policy-only responses") == true
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

    companion object {
        private const val TAG = "SabrDownloader"
        private const val IDLE_POLL_MS = 250L
        private const val MAX_EMPTY_RESPONSES = 60

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
}
