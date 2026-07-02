package us.shandian.giga.get

import android.util.Log
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.player.datasource.WebViewPoTokenProvider
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal class SabrDownloader(
    private val mission: DownloadMission,
) : Runnable {
    private var progressFloor = 0L
    private var attemptBytesWritten = 0L

    override fun run() {
        try {
            ensureRunning()
            val recoveries = validateRecoveryInfo()
            val info = SabrDownloadFormatResolver.resolveInfo(recoveries)

            var coldStartAttempts = 0
            var transientAttempts = 0
            while (true) {
                try {
                    prepareMission()
                    runSessionAttempt(info, recoveries, coldStartAttempts)
                    break
                } catch (error: RetryColdStartException) {
                    coldStartAttempts++
                    cleanup(mission)
                    if (coldStartAttempts > MAX_COLD_START_RETRIES) {
                        throw SabrDownloadException(
                            SabrDownloadException.Reason.INITIALIZATION,
                            "SABR download failed: cold start did not provide initialization",
                            error,
                        )
                    }
                    logDebug("retry cold start attempt=$coldStartAttempts")
                } catch (error: Exception) {
                    if (!isRetryableAttemptFailure(error)) {
                        throw error
                    }
                    if (transientAttempts >= MAX_TRANSIENT_RETRIES) {
                        throw SabrDownloadException(
                            SabrDownloadException.Reason.NETWORK,
                            "SABR download failed: network error after retries",
                            error,
                        )
                    }
                    transientAttempts++
                    cleanup(mission)
                    logDebug("retry transient attempt=$transientAttempts error=${error.javaClass.simpleName}")
                    Thread.sleep(transientRetryDelayMs(transientAttempts))
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: SabrProtocolException) {
            notifyErrorAndCleanup(classifyProtocolException(error))
        } catch (error: Exception) {
            notifyErrorAndCleanup(error)
        }
    }

    @Throws(IOException::class, InterruptedException::class, SabrProtocolException::class)
    private fun runSessionAttempt(
        info: YoutubeSabrInfo,
        recoveries: Array<MissionRecoveryInfo>,
        coldStartAttempt: Int,
    ) {
        val session = YoutubeSabrSession(
            info,
            SabrDownloadFormatResolver.selectedAudioFormat(info, recoveries),
            SabrDownloadFormatResolver.selectedVideoFormat(info, recoveries),
            WebViewPoTokenProvider(mission.context),
        )
        val workDir = prepareWorkDirectory()
        val targets = SabrDownloadFormatResolver.buildTargets(info, recoveries, workDir)
        configureRequestMode(session, targets, coldStartAttempt)
        val outputs = targets.associate { target -> target.resourceIndex to target.file.outputStream() }

        try {
            downloadSegments(
                session,
                targets,
                SabrSegmentWriter(session, targets, outputs, ::reportBytesWritten),
            )
        } finally {
            outputs.values.forEach { output ->
                try {
                    output.close()
                } catch (ignored: Exception) {
                    // Nothing to do.
                }
            }
            session.clearCache()
        }

        ensureRunning()
        val finalBytes = SabrFfmpegMuxer(mission).remuxAndCopy(
            targets.map { it.file },
            targets,
            workDir,
        )
        completeMission(finalBytes)
    }

    @Throws(IOException::class)
    private fun validateRecoveryInfo(): Array<MissionRecoveryInfo> {
        val recoveries = mission.recoveryInfo ?: throw IOException("Missing SABR recovery info")
        if (recoveries.size != mission.urls.size || recoveries.any { !it.isSabr }) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.FORMAT,
                "SABR download failed: mixed SABR/non-SABR resources are not supported",
            )
        }
        return recoveries
    }

    private fun prepareMission() {
        // SABR currently restarts the temp transfer on retry/resume. Keep the previous visible
        // progress as a floor, then count again once the restarted transfer catches up.
        progressFloor = mission.done.coerceAtLeast(0L)
        attemptBytesWritten = 0L
        mission.unknownLength = mission.nearLength <= 0
        mission.sabrStarted = true
        if (mission.nearLength > 0) {
            mission.length = mission.length
                .coerceAtLeast(mission.nearLength)
                .coerceAtLeast(progressFloor)
        }
        mission.current = 0
        mission.writeThisToFile()
    }

    private fun reportBytesWritten(delta: Long) {
        if (delta <= 0) {
            return
        }
        attemptBytesWritten += delta
        val visibleProgress = attemptBytesWritten.coerceAtLeast(progressFloor)
        val visibleDelta = visibleProgress - mission.done
        if (visibleDelta <= 0) {
            return
        }
        if (mission.nearLength > 0) {
            mission.length = mission.length
                .coerceAtLeast(mission.nearLength)
                .coerceAtLeast(visibleProgress)
            mission.unknownLength = false
        }
        mission.notifyProgress(visibleDelta)
    }

    private fun configureRequestMode(
        session: YoutubeSabrSession,
        targets: List<SabrDownloadTarget>,
        coldStartAttempt: Int,
    ) {
        val useCompanionWarmup = targets.size == 1 && coldStartAttempt % 2 == 1
        if (useCompanionWarmup) {
            session.streamState.setVideoAndAudioRequestMode()
        } else if (targets.size == 1 && targets.first().format.isAudio) {
            session.streamState.setAudioOnlyRequestMode()
        } else if (targets.size == 1 && targets.first().format.isVideo) {
            session.streamState.setVideoOnlyRequestMode()
        } else {
            session.streamState.setVideoAndAudioRequestMode()
        }
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
        if (targets.size == 1 && !targets.first().initializationWritten) {
            fetchInitializationsOrRetry(writer, localization)
            writer.observeWrittenInitializations()
            writer.drainCachedInitializations()
        }

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
            enforceSessionCacheLimit(session, writer)
            configureInitializedSingleTargetMode(session, targets)
            if (hasMediaWaitingForInitialization(targets)) {
                fetchMissingInitializationsOrRetry(writer, localization)
                writer.observeWrittenInitializations()
                wroteSegment = writer.drainCachedInitializations() || wroteSegment
                wroteSegment = writer.drainCachedSegments() || wroteSegment
                configureInitializedSingleTargetMode(session, targets)
                if (hasMediaWaitingForInitialization(targets)) {
                    throw RetryColdStartException()
                }
            }

            if (isDownloadComplete(session, targets)) {
                break
            }
            if (wroteSegment || segments.isNotEmpty()) {
                emptyResponses = 0
            } else {
                emptyResponses++
                if (emptyResponses > MAX_EMPTY_RESPONSES) {
                    throw SabrDownloadException(
                        SabrDownloadException.Reason.STALLED,
                        "SABR download stalled: no media received after $MAX_EMPTY_RESPONSES rounds",
                    )
                }
                Thread.sleep(IDLE_POLL_MS)
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchInitializationsOrRetry(
        writer: SabrSegmentWriter,
        localization: Localization,
    ) {
        try {
            writer.fetchUnwrittenInitializations(localization)
        } catch (error: SabrProtocolException) {
            if (isRetryableInitializationProtocolError(error)) {
                throw RetryColdStartException(error)
            }
            throw error
        }
    }

    @Throws(IOException::class)
    private fun fetchMissingInitializationsOrRetry(
        writer: SabrSegmentWriter,
        localization: Localization,
    ) {
        try {
            writer.fetchMissingInitializations(localization)
        } catch (error: SabrProtocolException) {
            if (isRetryableInitializationProtocolError(error)) {
                throw RetryColdStartException(error)
            }
            throw error
        }
    }

    @Throws(IOException::class)
    private fun enforceSessionCacheLimit(
        session: YoutubeSabrSession,
        writer: SabrSegmentWriter,
    ) {
        if (session.cachedBytes <= MAX_SESSION_CACHE_BYTES) {
            return
        }
        writer.drainCachedSegments()
        if (session.cachedBytes <= MAX_SESSION_CACHE_BYTES) {
            return
        }
        throw SabrDownloadException(
            SabrDownloadException.Reason.STALLED,
            "SABR download stalled: cached media grew to ${session.cachedBytes} bytes",
        )
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

    private fun isRetryableAttemptFailure(error: Exception): Boolean {
        if (error is RetryColdStartException || error is SabrDownloadException) {
            return false
        }
        if (error is SabrProtocolException) {
            return false
        }
        return error is SocketTimeoutException ||
            error is ConnectException ||
            error is UnknownHostException ||
            error is IOException
    }

    private fun transientRetryDelayMs(attempt: Int): Long {
        return (500L shl (attempt - 1)).coerceAtMost(MAX_TRANSIENT_RETRY_DELAY_MS)
    }

    private fun classifyProtocolException(error: SabrProtocolException): SabrDownloadException {
        val message = error.message.orEmpty()
        val reason = when {
            message.contains("protected", ignoreCase = true) ||
                message.contains("PO token", ignoreCase = true) -> {
                SabrDownloadException.Reason.PROTECTED
            }
            message.contains("policy-only", ignoreCase = true) ||
                message.contains("not returned", ignoreCase = true) ||
                message.contains("integrity", ignoreCase = true) -> {
                SabrDownloadException.Reason.STALLED
            }
            else -> SabrDownloadException.Reason.PROTOCOL
        }
        return SabrDownloadException(
            reason,
            "SABR download failed: ${message.ifBlank { "protocol error" }}",
            error,
        )
    }

    private fun isRetryableInitializationProtocolError(error: SabrProtocolException): Boolean {
        val message = error.message.orEmpty()
        if (!message.contains(":init")) {
            return false
        }
        return message.contains("policy-only", ignoreCase = true) ||
            message.contains("not returned", ignoreCase = true)
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    companion object {
        private const val TAG = "SabrDownloader"
        private const val IDLE_POLL_MS = 250L
        private const val MAX_EMPTY_RESPONSES = 60
        private const val MAX_COLD_START_RETRIES = 3
        private const val MAX_TRANSIENT_RETRIES = 5
        private const val MAX_TRANSIENT_RETRY_DELAY_MS = 5_000L
        private const val MAX_SESSION_CACHE_BYTES = 48L * 1024L * 1024L

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

    private class RetryColdStartException(cause: Throwable? = null) : IOException(cause)
}
