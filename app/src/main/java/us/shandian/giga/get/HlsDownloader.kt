package us.shandian.giga.get

import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import us.shandian.giga.hls.state.HlsDownloadCheckpoint
import us.shandian.giga.hls.state.HlsResourceCheckpoint
import us.shandian.giga.hls.transfer.HlsSegmentFileTransferEngine
import us.shandian.giga.hls.transfer.HttpTransferConfig
import us.shandian.giga.hls.transfer.HttpTransferConnectionFactory
import us.shandian.giga.hls.transfer.TransferController
import us.shandian.giga.hls.transfer.TransferHttpException
import us.shandian.giga.hls.transfer.TransferInterruptedException
import us.shandian.giga.hls.transfer.TransferProgress
import us.shandian.giga.hls.transfer.TransferProgressListener
import us.shandian.giga.hls.transfer.useTransferConnection
import java.io.File
import java.io.IOException
import java.io.InputStream

internal class HlsDownloader(
    private val mission: DownloadMission,
) : Runnable {
    private val controller = object : TransferController {
        override fun isRunning(): Boolean = mission.running
    }
    private val transferConfig = HttpTransferConfig(
        maxRetries = mission.maxRetry,
        parallelism = mission.threadCount.coerceIn(1, MAX_PARALLELISM),
    )
    private val hlsTransfer = HlsSegmentFileTransferEngine(transferConfig, controller)
    private val connectionFactory = HttpTransferConnectionFactory(transferConfig)

    override fun run() {
        try {
            ensureRunning()
            if (mission.hlsCheckpoint == null) {
                mission.hlsCheckpoint = HlsDownloadCheckpoint()
            }
            mission.unknownLength = true
            mission.done = restoredProgress()
            mission.length = mission.done.coerceAtLeast(mission.nearLength)
            mission.writeThisToFile()

            val workDir = workDirectory(mission)
            if (!workDir.exists() && !workDir.mkdirs()) {
                throw IOException("Cannot create HLS work directory: $workDir")
            }

            val inputs = mission.urls.indices.map { index -> File(workDir, "input-$index.media") }
            for (index in mission.urls.indices) {
                ensureRunning()
                mission.current = index
                if (isHlsResource(index)) {
                    transferHlsResource(index, inputs[index])
                } else {
                    transferDirectCompanionResource(index, inputs[index])
                }
            }

            ensureRunning()
            remuxWithFfmpeg(inputs)
            mission.current = mission.urls.size
            mission.psState = 2
            mission.hlsCheckpoint = null
            cleanup(mission)
            mission.unknownLength = false
            mission.notifyFinished()
        } catch (error: TransferInterruptedException) {
            // Pause/stop requested. The mission state has already been persisted by checkpoints.
        } catch (error: TransferHttpException) {
            if (error.statusCode == DownloadMission.ERROR_HTTP_FORBIDDEN ||
                error.statusCode == DownloadMission.ERROR_HTTP_AUTH
            ) {
                mission.doRecover(error.statusCode)
            } else {
                mission.notifyError(error)
            }
        } catch (error: Exception) {
            if (mission.running) {
                mission.notifyError(error)
            }
        }
    }

    @Throws(IOException::class)
    private fun transferHlsResource(index: Int, targetFile: File) {
        val previous = mission.hlsCheckpoint?.resources?.firstOrNull { it.resourceIndex == index }
        var lastProgress = previous
            ?.takeIf { it.tempFilePath == targetFile.absolutePath && targetFile.exists() }
            ?.bytesWritten
            ?: 0L

        hlsTransfer.transfer(
            resourceIndex = index,
            manifestUrl = hlsManifestUrl(index),
            targetFile = targetFile,
            checkpoint = previous,
            listener = object : TransferProgressListener {
                override fun onProgress(progress: TransferProgress) {
                    val delta = progress.bytesWritten - lastProgress
                    if (delta > 0) {
                        lastProgress = progress.bytesWritten
                        mission.notifyProgress(delta)
                    }
                }
            },
            onCheckpoint = { checkpoint -> updateResourceCheckpoint(checkpoint) },
            onRestart = {
                if (lastProgress > 0) {
                    mission.notifyProgress(-lastProgress)
                    lastProgress = 0
                }
            },
        )
    }

    @Throws(IOException::class)
    private fun transferDirectCompanionResource(index: Int, targetFile: File) {
        val parent = targetFile.parentFile ?: throw IOException("HLS companion target has no parent")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create HLS companion parent: $parent")
        }
        var written = 0L
        targetFile.outputStream().use { output ->
            connectionFactory.open(mission.urls[index], "GET", null, null).useTransferConnection { connection ->
                val statusCode = connection.responseCode
                if (statusCode < 200 || statusCode > 299) {
                    throw TransferHttpException(statusCode)
                }
                connection.inputStream.use { input ->
                    copyDirectCompanion(input, output) { delta ->
                        written += delta
                        mission.notifyProgress(delta)
                    }
                }
            }
        }
        updateResourceCheckpoint(
            HlsResourceCheckpoint(
                resourceIndex = index,
                manifestUrl = mission.urls[index],
                mediaPlaylistUrl = null,
                tempFilePath = targetFile.absolutePath,
                nextSegmentIndex = 1,
                segmentByteOffset = 0,
                bytesWritten = written,
                mediaSequence = -1,
                playlistFingerprint = "direct-companion",
            )
        )
    }

    @Throws(IOException::class)
    private fun copyDirectCompanion(
        input: InputStream,
        output: java.io.OutputStream,
        onProgress: (Long) -> Unit,
    ) {
        val buffer = ByteArray(transferConfig.bufferSize)
        while (true) {
            ensureRunning()
            val read = input.read(buffer)
            if (read == -1) {
                return
            }
            output.write(buffer, 0, read)
            onProgress(read.toLong())
        }
    }

    @Throws(IOException::class)
    private fun remuxWithFfmpeg(inputs: List<File>) {
        mission.psState = 1
        mission.writeThisToFile()

        val output = FFmpegKitConfig.getSafParameter(mission.context, Uri.parse(mission.storage.source), "w")
        val command = buildString {
            append("-y ")
            inputs.forEach { input ->
                append("-i ").append(quote(input.absolutePath)).append(' ')
            }
            if (inputs.size > 1) {
                inputs.indices.forEach { index ->
                    append("-map ").append(index).append(":v? ")
                    append("-map ").append(index).append(":a? ")
                }
            }
            append("-c copy -movflags +faststart ").append(output)
        }

        Log.d(TAG, "remuxWithFfmpeg inputs=${inputs.size}")
        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            mission.psState = 0
            throw IOException("HLS ffmpeg remux failed: ${session.returnCode}")
        }
    }

    private fun hlsManifestUrl(index: Int): String {
        val isUrl = mission.resourceIsUrls?.getOrNull(index) ?: true
        val manifestUrl = mission.resourceManifestUrls?.getOrNull(index)
        val url = mission.urls[index]
        if (!isUrl) {
            throw IOException("Inline HLS manifests are not supported by HlsDownloader yet")
        }
        return when {
            !manifestUrl.isNullOrBlank() && looksLikeHls(manifestUrl) -> manifestUrl
            else -> url
        }
    }

    private fun isHlsResource(index: Int): Boolean {
        return mission.resourceDeliveryMethods?.getOrNull(index) == "HLS" ||
            looksLikeHls(mission.resourceManifestUrls?.getOrNull(index)) ||
            looksLikeHls(mission.urls.getOrNull(index))
    }

    private fun updateResourceCheckpoint(resourceCheckpoint: HlsResourceCheckpoint) {
        val current = mission.hlsCheckpoint ?: HlsDownloadCheckpoint()
        val resources = current.resources
            .filterNot { it.resourceIndex == resourceCheckpoint.resourceIndex }
            .toMutableList()
        resources += resourceCheckpoint
        mission.hlsCheckpoint = current.copy(resources = resources.sortedBy { it.resourceIndex })
        mission.writeThisToFile()
    }

    private fun restoredProgress(): Long {
        return mission.hlsCheckpoint?.resources
            ?.filter { File(it.tempFilePath).exists() }
            ?.sumOf { it.bytesWritten }
            ?: 0L
    }

    @Throws(TransferInterruptedException::class)
    private fun ensureRunning() {
        if (!mission.running || Thread.currentThread().isInterrupted) {
            throw TransferInterruptedException()
        }
    }

    private fun quote(value: String): String {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"'
    }

    private fun looksLikeHls(value: String?): Boolean {
        return value?.contains(".m3u8", ignoreCase = true) == true
    }

    companion object {
        private const val TAG = "HlsDownloader"
        private const val MAX_PARALLELISM = 12

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
            return File(base, "hls-downloader/$missionId")
        }
    }
}
