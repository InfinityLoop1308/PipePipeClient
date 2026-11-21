package us.shandian.giga.get

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.ReturnCode
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Adds SponsorBlock chapters to a finished YouTube video without encoding it again. Whatever goes
 * wrong before the download is replaced leaves the download as it is: chapters are never worth a
 * failed download.
 */
internal class SponsorBlockChapterWriter(
    private val mission: DownloadMission,
) : Runnable {
    private val workDir = File(baseDirectory(mission), mission.timestamp.toString())

    override fun run() {
        try {
            val output = restoredOutput() ?: createOutput()
            if (output != null) {
                replaceDownload(output)
            }
        } catch (error: Exception) {
            if (mission.chapterOutputPath != null) {
                // The download is cut short, retrying finishes it from the temporary file.
                mission.writingChapters = false
                mission.notifyError(IOException("Could not write chapters", error))
                return
            }
            Log.w(TAG, "Leaving the download without chapters", error)
        }
        workDir.deleteRecursively()
        mission.chaptersHandled = true
        mission.writingChapters = false
        mission.notifyFinished()
    }

    private fun restoredOutput(): File? {
        val output = mission.chapterOutputPath?.let { File(it) } ?: return null
        if (output.isFile) {
            return output
        }
        mission.chapterOutputPath = null
        return null
    }

    @Throws(IOException::class)
    private fun createOutput(): File? {
        val videoId = videoIdOf(mission) ?: return null
        val segments = fetchSegments(videoId)
        if (segments.isEmpty()) {
            return null
        }
        val durationMs = durationMsOf(probe(inputArgument()))
        val chapters = SponsorBlockChapters.buildChapters(segments, durationMs)
        if (chapters.isEmpty()) {
            return null
        }

        deleteAbandonedFiles()
        if (!workDir.isDirectory && !workDir.mkdirs()) {
            throw IOException("Could not create $workDir")
        }
        if (workDir.usableSpace < mission.storage.length() + SPARE_BYTES) {
            throw IOException("Not enough space for a copy of the video")
        }

        val metadata = File(workDir, "chapters.txt")
        metadata.writeText(SponsorBlockChapters.toFfmetadata(chapters))
        val output = File(workDir, "output.${extensionOf(mission)}")
        addChapters(metadata, output)
        verify(output, durationMs, chapters.size)
        return output
    }

    @Throws(Exception::class)
    private fun fetchSegments(videoId: String): List<SponsorBlockChapters.Segment> {
        val response = DownloaderImpl.getInstance().get(SponsorBlockChapters.requestUrl(videoId))
        // The API answers 404 when it knows no segment for the prefix of the hash.
        if (response.responseCode() != HTTP_OK) {
            return emptyList()
        }
        return SponsorBlockChapters.parseSegments(response.responseBody(), videoId)
    }

    /**
     * A storage access framework parameter is used up by a single command.
     */
    private fun inputArgument(): String {
        val storage = mission.storage
        return if (storage.isDirect) {
            File(storage.uri.path!!).absolutePath
        } else {
            FFmpegKitConfig.getSafParameterForRead(mission.context, storage.uri)
        }
    }

    @Throws(IOException::class)
    private fun probe(input: String): MediaInformation {
        return FFprobeKit.getMediaInformation(input).mediaInformation
            ?: throw IOException("ffprobe could not read the video")
    }

    @Throws(IOException::class)
    private fun durationMsOf(information: MediaInformation): Long {
        val seconds = information.duration?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: throw IOException("Unknown duration")
        return (seconds * 1000).roundToLong()
    }

    @Throws(IOException::class)
    private fun addChapters(metadata: File, output: File) {
        val command = buildList {
            addAll(COMMON_ARGUMENTS)
            add("-i")
            add(inputArgument())
            add("-f")
            add("ffmetadata")
            add("-i")
            add(metadata.absolutePath)
            add("-map")
            add("0")
            add("-map_metadata")
            add("0")
            add("-map_chapters")
            add("1")
            add("-c")
            add("copy")
            if (output.extension == EXTENSION_MP4) {
                add("-movflags")
                add("+faststart")
            }
            add(output.absolutePath)
        }
        execute(command)
    }

    /**
     * Nothing may replace the download unless it holds the very same pictures and sound.
     */
    @Throws(IOException::class)
    private fun verify(output: File, durationMs: Long, chapterCount: Int) {
        val information = probe(output.absolutePath)
        if (information.chapters.size != chapterCount) {
            throw IOException("Expected $chapterCount chapters, got ${information.chapters.size}")
        }
        if (abs(durationMsOf(information) - durationMs) > MAX_DURATION_CHANGE_MS) {
            throw IOException("The duration has changed")
        }
        val inputHashes = hashStreams(inputArgument(), File(workDir, "input.hash"))
        val outputHashes = hashStreams(output.absolutePath, File(workDir, "output.hash"))
        if (inputHashes.isEmpty() || inputHashes != outputHashes) {
            throw IOException("The streams have changed")
        }
    }

    /**
     * Each stream has its own hash, so the order of the packets in the file does not matter.
     */
    @Throws(IOException::class)
    private fun hashStreams(input: String, hashFile: File): List<String> {
        execute(
            COMMON_ARGUMENTS + listOf(
                "-i", input,
                "-map", "0:v?",
                "-map", "0:a?",
                "-c", "copy",
                "-f", "streamhash",
                "-hash", "md5",
                hashFile.absolutePath,
            ),
        )
        return hashFile.readLines().filter { it.isNotBlank() }
    }

    @Throws(IOException::class)
    private fun execute(command: List<String>) {
        val session = FFmpegKit.executeWithArguments(command.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IOException("ffmpeg failed (${session.returnCode}): ${session.output}")
        }
    }

    /**
     * The download is overwritten and not swapped, so that its uri stays valid. Until the copy is
     * complete the path of the temporary file stays saved, which lets a later start finish it.
     */
    @Throws(IOException::class)
    private fun replaceDownload(output: File) {
        mission.chapterOutputPath = output.absolutePath
        mission.writeThisToFile()

        var copied = 0L
        output.inputStream().use { input ->
            mission.storage.getStream().use { storage ->
                storage.setLength(0)
                storage.seek(0)
                val buffer = ByteArray(DownloadMission.BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        break
                    }
                    storage.write(buffer, 0, read)
                    copied += read.toLong()
                }
            }
        }

        mission.done = copied
        mission.length = copied
        mission.chapterOutputPath = null
    }

    /**
     * Files of a mission lost while it was adding chapters would stay forever otherwise.
     */
    private fun deleteAbandonedFiles() {
        val oldest = System.currentTimeMillis() - ABANDONED_AFTER_MS
        baseDirectory(mission).listFiles()
            ?.filter { it != workDir && it.lastModified() < oldest }
            ?.forEach { it.deleteRecursively() }
    }

    companion object {
        private const val TAG = "SponsorBlockChapters"
        private const val HTTP_OK = 200
        private const val EXTENSION_MP4 = "mp4"
        private const val EXTENSION_WEBM = "webm"
        private const val SPARE_BYTES = 16L * 1024 * 1024
        private const val MAX_DURATION_CHANGE_MS = 1000L
        private const val ABANDONED_AFTER_MS = 24L * 60 * 60 * 1000
        private val COMMON_ARGUMENTS = listOf("-hide_banner", "-nostats", "-loglevel", "error", "-y")

        /**
         * @return whether the option is on and the mission is a YouTube video in a container able
         * to hold chapters, or a replacement of the download was interrupted and must be finished
         */
        @JvmStatic
        fun isNeeded(mission: DownloadMission): Boolean {
            if (mission.chapterOutputPath != null) {
                return true
            }
            return isEnabled(mission.context) &&
                mission.kind == 'v' &&
                extensionOf(mission) in listOf(EXTENSION_MP4, EXTENSION_WEBM) &&
                videoIdOf(mission) != null
        }

        private fun isEnabled(context: Context?): Boolean {
            context ?: return false
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getBoolean(context.getString(R.string.sponsor_block_enable_key), true) &&
                prefs.getBoolean(
                    context.getString(R.string.sponsor_block_download_chapters_key),
                    false,
                )
        }

        private fun videoIdOf(mission: DownloadMission): String? {
            val factory = YoutubeStreamLinkHandlerFactory.getInstance()
            return try {
                mission.source?.takeIf { factory.acceptUrl(it) }?.let { factory.getId(it) }
            } catch (ignored: ParsingException) {
                null
            }
        }

        private fun extensionOf(mission: DownloadMission): String {
            return mission.storage.name.orEmpty().substringAfterLast('.', "").lowercase()
        }

        private fun baseDirectory(mission: DownloadMission): File {
            val base = mission.context.getExternalFilesDir(null) ?: mission.context.filesDir
            return File(base, "sponsorblock-chapters")
        }
    }
}
