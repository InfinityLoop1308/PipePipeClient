package us.shandian.giga.get

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException

internal class SabrFfmpegMuxer(
    private val mission: DownloadMission,
) {
    @Throws(IOException::class, InterruptedException::class)
    fun remuxAndCopy(inputs: List<File>, workDir: File): Long {
        val output = File(workDir, "output.${outputExtension()}")
        remuxWithFfmpeg(inputs, output)
        return copyOutputToStorage(output)
    }

    @Throws(IOException::class)
    private fun remuxWithFfmpeg(inputs: List<File>, output: File) {
        mission.psState = 1
        mission.writeThisToFile()
        val command = buildString {
            append("-y ")
            inputs.forEach { input ->
                append("-i ").append(quote(input.absolutePath)).append(' ')
            }
            if (mission.kind == 'a' && inputs.size == 1) {
                append("-map 0:a? -vn ")
            } else if (inputs.size > 1) {
                inputs.indices.forEach { index ->
                    append("-map ").append(index).append(":v? ")
                    append("-map ").append(index).append(":a? ")
                }
            }
            append("-c copy ")
            if (supportsFastStart(output)) {
                append("-movflags +faststart ")
            }
            append(quote(output.absolutePath))
        }
        Log.d(TAG, "remuxWithFfmpeg inputs=${inputs.size}")
        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            mission.psState = 0
            throw IOException("SABR ffmpeg remux failed: ${session.returnCode}")
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun copyOutputToStorage(output: File): Long {
        var copied = 0L
        output.inputStream().use { input ->
            mission.storage.getStream().use { storage ->
                storage.setLength(0)
                storage.seek(0)
                val buffer = ByteArray(DownloadMission.BUFFER_SIZE)
                while (true) {
                    ensureRunning()
                    val read = input.read(buffer)
                    if (read == -1) {
                        break
                    }
                    storage.write(buffer, 0, read)
                    copied += read.toLong()
                }
            }
        }
        return copied
    }

    @Throws(InterruptedException::class)
    private fun ensureRunning() {
        if (!mission.running || Thread.currentThread().isInterrupted) {
            throw InterruptedException()
        }
    }

    private fun quote(value: String): String {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"'
    }

    private fun outputExtension(): String {
        val name = mission.storage.name ?: return "mp4"
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return extension.takeIf { it.isNotBlank() && it.all { char -> char.isLetterOrDigit() } } ?: "mp4"
    }

    private fun supportsFastStart(output: File): Boolean {
        return when (output.extension.lowercase()) {
            "m4a", "m4v", "mov", "mp4" -> true
            else -> false
        }
    }

    private companion object {
        private const val TAG = "SabrFfmpegMuxer"
    }
}
