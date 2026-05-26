package us.shandian.giga.hls.transfer

import android.util.Log
import us.shandian.giga.hls.manifest.HlsManifestResolver
import us.shandian.giga.hls.manifest.HlsMasterPlaylist
import us.shandian.giga.hls.manifest.HlsMediaPlaylist
import us.shandian.giga.hls.manifest.HlsPlaylist
import us.shandian.giga.hls.manifest.HlsPlaylistSelector
import us.shandian.giga.hls.manifest.HlsSegment
import us.shandian.giga.hls.manifest.HlsByteRange
import us.shandian.giga.hls.manifest.HlsEncryptionKey
import us.shandian.giga.hls.manifest.UnsupportedHlsPlaylistException
import us.shandian.giga.hls.state.HlsResourceCheckpoint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsSegmentFileTransferEngine(
    private val config: HttpTransferConfig = HttpTransferConfig(),
    private val controller: TransferController = AlwaysRunningTransferController,
) {
    private val connectionFactory = HttpTransferConnectionFactory(config)
    private val manifestResolver = HlsManifestResolver(config)
    private val retryPolicy = TransferRetryPolicy(config.maxRetries)

    @Throws(IOException::class)
    fun transfer(
        resourceIndex: Int,
        manifestUrl: String,
        targetFile: File,
        checkpoint: HlsResourceCheckpoint?,
        listener: TransferProgressListener? = null,
        onCheckpoint: (HlsResourceCheckpoint) -> Unit = {},
        onRestart: () -> Unit = {},
    ): TransferResult {
        val resolved = resolveMediaPlaylist(manifestUrl)
        val playlist = resolved.playlist
        validatePlaylist(playlist)
        val keyCache = mutableMapOf<String, ByteArray>()

        val fingerprint = fingerprint(playlist)
        val target = targetFile.absoluteFile
        val parent = target.parentFile ?: throw IOException("HLS target has no parent: $target")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create HLS target parent: $parent")
        }

        val canResume = checkpoint != null &&
            checkpoint.playlistFingerprint == fingerprint &&
            checkpoint.tempFilePath == target.absolutePath &&
            target.exists()

        val startedAt = System.currentTimeMillis()
        var outputPosition = if (canResume) checkpoint!!.bytesWritten else 0L
        var startSegmentIndex = if (canResume) {
            checkpoint!!.nextSegmentIndex.coerceIn(0, playlist.segments.size)
        } else {
            0
        }
        Log.d(
            TAG,
            "resourceIndex=$resourceIndex segments=${playlist.segments.size} " +
                "init=${playlist.initSegment != null} byteranges=${playlist.segments.count { it.byteRange != null }} " +
                "resume=$canResume startSegment=$startSegmentIndex parallelism=${config.parallelism}"
        )
        if (!canResume && checkpoint != null) {
            onRestart()
            onCheckpoint(
                checkpointFor(
                    resourceIndex = resourceIndex,
                    manifestUrl = manifestUrl,
                    playlist = playlist,
                    rawMediaPlaylistUrl = resolved.rawMediaPlaylistUrl,
                    target = target,
                    nextSegmentIndex = 0,
                    bytesWritten = 0,
                    fingerprint = fingerprint,
                )
            )
        }
        var lastStatusCode = 0
        var lastFinalUrl = resolved.rawMediaPlaylistUrl

        RandomAccessFile(target, "rw").use { output ->
            output.setLength(outputPosition)

            if (!canResume && playlist.initSegment != null) {
                val result = retryPolicy.withRetries {
                    if (playlist.initSegment.encryptionKey == null) {
                        downloadPart(
                            url = withManifestCookie(playlist.initSegment.url, resolved.rawMediaPlaylistUrl),
                            byteRange = playlist.initSegment.byteRange,
                            defaultByteRangeStart = 0L,
                            output = output,
                            outputPosition = outputPosition,
                            listener = listener,
                        )
                    } else {
                        val memory = downloadPartToMemory(
                            url = withManifestCookie(playlist.initSegment.url, resolved.rawMediaPlaylistUrl),
                            byteRange = playlist.initSegment.byteRange,
                            defaultByteRangeStart = 0L,
                        )
                        val decrypted = decryptHlsBytes(
                            encrypted = memory.bytes,
                            encryptionKey = playlist.initSegment.encryptionKey,
                            sequenceNumber = playlist.mediaSequence,
                            rawMediaPlaylistUrl = resolved.rawMediaPlaylistUrl,
                            keyCache = keyCache,
                        )
                        appendBytesToOutput(
                            bytes = decrypted,
                            output = output,
                            outputPosition = outputPosition,
                            listener = listener,
                            resourceUrl = memory.finalUrl,
                        )
                        TransferResult(decrypted.size.toLong(), -1, memory.statusCode, memory.finalUrl)
                    }
                }
                outputPosition += result.bytesWritten
                lastStatusCode = result.statusCode
                lastFinalUrl = result.finalUrl
                onCheckpoint(
                    checkpointFor(
                        resourceIndex = resourceIndex,
                        manifestUrl = manifestUrl,
                        playlist = playlist,
                        rawMediaPlaylistUrl = resolved.rawMediaPlaylistUrl,
                        target = target,
                        nextSegmentIndex = 0,
                        bytesWritten = outputPosition,
                        fingerprint = fingerprint,
                    )
                )
            }

            val result = downloadSegmentsInParallel(
                resourceIndex = resourceIndex,
                manifestUrl = manifestUrl,
                rawMediaPlaylistUrl = resolved.rawMediaPlaylistUrl,
                playlist = playlist,
                keyCache = keyCache,
                target = target,
                output = output,
                startSegmentIndex = startSegmentIndex,
                outputPosition = outputPosition,
                fingerprint = fingerprint,
                listener = listener,
                onCheckpoint = onCheckpoint,
            )
            outputPosition = result.bytesWritten
            lastStatusCode = result.statusCode
            lastFinalUrl = result.finalUrl
            output.fd.sync()
        }

        Log.d(
            TAG,
            "resourceIndex=$resourceIndex bytes=$outputPosition segments=${playlist.segments.size} " +
                "status=$lastStatusCode elapsedMs=${System.currentTimeMillis() - startedAt}"
        )
        return TransferResult(
            bytesWritten = outputPosition,
            expectedBytes = -1,
            statusCode = lastStatusCode,
            finalUrl = lastFinalUrl,
        )
    }

    @Throws(IOException::class)
    private fun downloadSegmentsInParallel(
        resourceIndex: Int,
        manifestUrl: String,
        rawMediaPlaylistUrl: String,
        playlist: HlsMediaPlaylist,
        keyCache: MutableMap<String, ByteArray>,
        target: File,
        output: RandomAccessFile,
        startSegmentIndex: Int,
        outputPosition: Long,
        fingerprint: String,
        listener: TransferProgressListener?,
        onCheckpoint: (HlsResourceCheckpoint) -> Unit,
    ): TransferResult {
        val remainingSegments = playlist.segments.size - startSegmentIndex
        if (remainingSegments <= 0) {
            return TransferResult(outputPosition, -1, 0, rawMediaPlaylistUrl)
        }

        val byteRangeStarts = byteRangeStarts(playlist.segments)
        val parallelism = config.parallelism.coerceIn(1, remainingSegments)
        val executor = Executors.newFixedThreadPool(parallelism)
        val completion = ExecutorCompletionService<DownloadedSegment>(executor)
        val downloadedSegments = mutableMapOf<Int, DownloadedSegment>()
        var nextToSubmit = startSegmentIndex
        var nextToAppend = startSegmentIndex
        var submitted = 0
        var completed = 0
        var currentOutputPosition = outputPosition
        var lastStatusCode = 0
        var lastFinalUrl = rawMediaPlaylistUrl

        fun submitNextSegment() {
            val segmentIndex = nextToSubmit++
            val segment = playlist.segments[segmentIndex]
            completion.submit(Callable {
                downloadSegmentToMemory(
                    segmentIndex = segmentIndex,
                    segment = segment,
                    rawMediaPlaylistUrl = rawMediaPlaylistUrl,
                    mediaSequence = playlist.mediaSequence,
                    keyCache = keyCache,
                    defaultByteRangeStart = byteRangeStarts[segmentIndex],
                )
            })
            submitted++
        }

        try {
            repeat(parallelism) { submitNextSegment() }

            while (completed < remainingSegments) {
                ensureRunning()
                val future = try {
                    completion.poll(250, TimeUnit.MILLISECONDS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw TransferInterruptedException()
                } ?: continue

                val downloaded = try {
                    future.get()
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw TransferInterruptedException()
                } catch (error: ExecutionException) {
                    throw unwrapExecutionException(error)
                }

                completed++
                downloadedSegments[downloaded.segmentIndex] = downloaded

                while (true) {
                    val next = downloadedSegments.remove(nextToAppend) ?: break
                    currentOutputPosition += appendDownloadedSegment(
                        downloaded = next,
                        output = output,
                        outputPosition = currentOutputPosition,
                        listener = listener,
                    )
                    lastStatusCode = next.statusCode
                    lastFinalUrl = next.finalUrl
                    nextToAppend++
                    onCheckpoint(
                        checkpointFor(
                            resourceIndex = resourceIndex,
                            manifestUrl = manifestUrl,
                            playlist = playlist,
                            rawMediaPlaylistUrl = rawMediaPlaylistUrl,
                            target = target,
                            nextSegmentIndex = nextToAppend,
                            bytesWritten = currentOutputPosition,
                            fingerprint = fingerprint,
                        )
                    )
                }

                while (nextToSubmit < playlist.segments.size && submitted - completed < parallelism) {
                    submitNextSegment()
                }
            }
        } finally {
            executor.shutdownNow()
        }

        return TransferResult(
            bytesWritten = currentOutputPosition,
            expectedBytes = -1,
            statusCode = lastStatusCode,
            finalUrl = lastFinalUrl,
        )
    }

    @Throws(IOException::class)
    private fun downloadSegmentToMemory(
        segmentIndex: Int,
        segment: HlsSegment,
        rawMediaPlaylistUrl: String,
        mediaSequence: Long,
        keyCache: MutableMap<String, ByteArray>,
        defaultByteRangeStart: Long,
    ): DownloadedSegment {
        ensureRunning()
        val result = retryPolicy.withRetries {
            downloadPartToMemory(
                url = withManifestCookie(segment.url, rawMediaPlaylistUrl),
                byteRange = segment.byteRange,
                defaultByteRangeStart = defaultByteRangeStart,
            )
        }
        val bytes = decryptHlsBytes(
            encrypted = result.bytes,
            encryptionKey = segment.encryptionKey,
            sequenceNumber = mediaSequence + segmentIndex,
            rawMediaPlaylistUrl = rawMediaPlaylistUrl,
            keyCache = keyCache,
        )
        return DownloadedSegment(
            segmentIndex = segmentIndex,
            bytes = bytes,
            statusCode = result.statusCode,
            finalUrl = result.finalUrl,
        )
    }

    @Throws(IOException::class)
    private fun resolveMediaPlaylist(manifestUrl: String): ResolvedMediaPlaylist {
        return when (val playlist = manifestResolver.resolve(manifestUrl)) {
            is HlsMediaPlaylist -> ResolvedMediaPlaylist(playlist, manifestUrl)
            is HlsMasterPlaylist -> {
                val variant = HlsPlaylistSelector.selectVariant(playlist)
                    ?: throw UnsupportedHlsPlaylistException("HLS master playlist has no variants")
                val variantUrl = withManifestCookie(variant.url, manifestUrl)
                when (val selected: HlsPlaylist = manifestResolver.resolve(variantUrl)) {
                    is HlsMediaPlaylist -> ResolvedMediaPlaylist(selected, variantUrl)
                    is HlsMasterPlaylist -> throw UnsupportedHlsPlaylistException(
                        "Nested HLS master playlists are not supported yet"
                    )
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun validatePlaylist(playlist: HlsMediaPlaylist) {
        val initSegment = playlist.initSegment
        val initEncryptionKey = initSegment?.encryptionKey
        if (!playlist.isEndList) {
            throw UnsupportedHlsPlaylistException("Live HLS playlists are not supported yet")
        }
        if (playlist.hasUnsupportedEncryption) {
            throw UnsupportedHlsPlaylistException("Only AES-128 HLS encryption is supported")
        }
        if (initEncryptionKey?.url == null && initEncryptionKey != null) {
            throw UnsupportedHlsPlaylistException("HLS init encryption key URI is missing")
        }
        if (initEncryptionKey != null && initEncryptionKey.iv == null) {
            throw UnsupportedHlsPlaylistException("Encrypted HLS init segments require an explicit IV")
        }
        if (initEncryptionKey != null && initSegment.byteRange != null) {
            throw UnsupportedHlsPlaylistException("Encrypted HLS init byte ranges are not supported yet")
        }
        if (playlist.segments.any { it.encryptionKey?.url == null && it.encryptionKey != null }) {
            throw UnsupportedHlsPlaylistException("HLS encryption key URI is missing")
        }
        if (playlist.segments.any { it.encryptionKey != null && it.byteRange != null }) {
            throw UnsupportedHlsPlaylistException("Encrypted HLS byte ranges are not supported yet")
        }
        if (playlist.hasDiscontinuity) {
            throw UnsupportedHlsPlaylistException("HLS discontinuities are not supported yet")
        }
    }

    @Throws(IOException::class)
    private fun downloadPart(
        url: String,
        byteRange: HlsByteRange?,
        defaultByteRangeStart: Long,
        output: RandomAccessFile,
        outputPosition: Long,
        listener: TransferProgressListener?,
    ): TransferResult {
        val rangeStart = byteRange?.offset ?: if (byteRange == null) null else defaultByteRangeStart
        val rangeEnd = byteRange?.let { rangeStart!! + it.length - 1 }

        connectionFactory.open(url, "GET", rangeStart, rangeEnd)
            .useTransferConnection { connection ->
                val statusCode = connection.responseCode
                if (statusCode < 200 || statusCode > 299) {
                    throw TransferHttpException(statusCode)
                }
                if (byteRange != null && statusCode != java.net.HttpURLConnection.HTTP_PARTIAL) {
                    throw TransferHttpException(statusCode)
                }

                val bytesWritten = connection.inputStream.use { input ->
                    copyPart(
                        input = input,
                        output = output,
                        outputPosition = outputPosition,
                        listener = listener,
                        resourceUrl = url,
                    )
                }

                return TransferResult(
                    bytesWritten = bytesWritten,
                    expectedBytes = byteRange?.length ?: -1,
                    statusCode = statusCode,
                    finalUrl = connection.url.toString(),
                )
            }
    }

    @Throws(IOException::class)
    private fun downloadPartToMemory(
        url: String,
        byteRange: HlsByteRange?,
        defaultByteRangeStart: Long,
    ): MemoryTransferResult {
        val rangeStart = byteRange?.offset ?: if (byteRange == null) null else defaultByteRangeStart
        val rangeEnd = byteRange?.let { rangeStart!! + it.length - 1 }
        connectionFactory.open(url, "GET", rangeStart, rangeEnd)
            .useTransferConnection { connection ->
                val statusCode = connection.responseCode
                if (statusCode < 200 || statusCode > 299) {
                    throw TransferHttpException(statusCode)
                }
                if (byteRange != null && statusCode != java.net.HttpURLConnection.HTTP_PARTIAL) {
                    throw TransferHttpException(statusCode)
                }

                val bytes = connection.inputStream.use { input ->
                    copyPartToMemory(input, initialMemoryBufferCapacity(connection.contentLengthLong))
                }

                return MemoryTransferResult(
                    bytes = bytes,
                    expectedBytes = byteRange?.length ?: -1,
                    statusCode = statusCode,
                    finalUrl = connection.url.toString(),
                )
            }
    }

    @Throws(IOException::class)
    private fun copyPart(
        input: InputStream,
        output: RandomAccessFile,
        outputPosition: Long,
        listener: TransferProgressListener?,
        resourceUrl: String,
    ): Long {
        val buffer = ByteArray(config.bufferSize)
        var total = 0L

        while (true) {
            ensureRunning()
            val read = input.read(buffer)
            if (read == -1) {
                return total
            }

            output.seek(outputPosition + total)
            output.write(buffer, 0, read)
            total += read
            listener?.onProgress(
                TransferProgress(
                    bytesWritten = outputPosition + total,
                    expectedBytes = -1,
                    resourceUrl = resourceUrl,
                )
            )
        }
    }

    @Throws(IOException::class)
    private fun copyPartToMemory(input: InputStream, initialCapacity: Int = config.bufferSize): ByteArray {
        val buffer = ByteArray(config.bufferSize)
        val output = ByteArrayOutputStream(initialCapacity)

        while (true) {
            ensureRunning()
            val read = input.read(buffer)
            if (read == -1) {
                return output.toByteArray()
            }

            output.write(buffer, 0, read)
        }
    }

    private fun initialMemoryBufferCapacity(contentLength: Long): Int {
        return if (contentLength in 1..MAX_INITIAL_MEMORY_BUFFER_BYTES) {
            contentLength.toInt().coerceAtLeast(config.bufferSize)
        } else {
            config.bufferSize
        }
    }

    @Throws(IOException::class)
    private fun appendDownloadedSegment(
        downloaded: DownloadedSegment,
        output: RandomAccessFile,
        outputPosition: Long,
        listener: TransferProgressListener?,
    ): Long {
        ensureRunning()
        appendBytesToOutput(downloaded.bytes, output, outputPosition, listener, downloaded.finalUrl)
        return downloaded.bytes.size.toLong()
    }

    @Throws(IOException::class)
    private fun appendBytesToOutput(
        bytes: ByteArray,
        output: RandomAccessFile,
        outputPosition: Long,
        listener: TransferProgressListener?,
        resourceUrl: String,
    ) {
        output.seek(outputPosition)
        output.write(bytes)
        listener?.onProgress(
            TransferProgress(
                bytesWritten = outputPosition + bytes.size,
                expectedBytes = -1,
                resourceUrl = resourceUrl,
            )
        )
    }

    @Throws(IOException::class)
    private fun decryptHlsBytes(
        encrypted: ByteArray,
        encryptionKey: HlsEncryptionKey?,
        sequenceNumber: Long,
        rawMediaPlaylistUrl: String,
        keyCache: MutableMap<String, ByteArray>,
    ): ByteArray {
        if (encryptionKey == null) {
            return encrypted
        }
        if (!encryptionKey.method.equals("AES-128", ignoreCase = true)) {
            throw UnsupportedHlsPlaylistException("Unsupported HLS encryption: ${encryptionKey.method}")
        }
        val keyUrl = encryptionKey.url
            ?: throw UnsupportedHlsPlaylistException("HLS encryption key URI is missing")
        val key = synchronized(keyCache) {
            keyCache[keyUrl] ?: downloadEncryptionKey(withManifestCookie(keyUrl, rawMediaPlaylistUrl))
                .also { keyCache[keyUrl] = it }
        }
        val iv = encryptionKey.iv?.let { parseIv(it) } ?: ivFromSequence(sequenceNumber)

        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(encrypted)
        } catch (error: Exception) {
            throw IOException("HLS AES-128 decrypt failed", error)
        }
    }

    @Throws(IOException::class)
    private fun downloadEncryptionKey(rawKeyUrl: String): ByteArray {
        connectionFactory.open(rawKeyUrl, "GET", null, null).useTransferConnection { connection ->
            val statusCode = connection.responseCode
            if (statusCode < 200 || statusCode > 299) {
                throw TransferHttpException(statusCode)
            }
            val key = connection.inputStream.use { input -> copyPartToMemory(input, AES_128_KEY_BYTES) }
            if (key.size != AES_128_KEY_BYTES) {
                throw IOException("Invalid HLS AES-128 key size: ${key.size}")
            }
            return key
        }
    }

    @Throws(IOException::class)
    private fun parseIv(value: String): ByteArray {
        val hex = value.removePrefix("0x").removePrefix("0X")
        if (hex.length > AES_128_IV_BYTES * 2) {
            throw IOException("HLS IV is too large")
        }
        val padded = hex.padStart(AES_128_IV_BYTES * 2, '0')
        return try {
            ByteArray(AES_128_IV_BYTES) { index ->
                padded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (error: NumberFormatException) {
            throw IOException("Invalid HLS IV", error)
        }
    }

    private fun ivFromSequence(sequenceNumber: Long): ByteArray {
        val iv = ByteArray(AES_128_IV_BYTES)
        var value = sequenceNumber
        for (index in AES_128_IV_BYTES - 1 downTo AES_128_IV_BYTES - LONG_BYTES) {
            iv[index] = (value and 0xff).toByte()
            value = value ushr 8
        }
        return iv
    }

    private data class MemoryTransferResult(
        val bytes: ByteArray,
        val expectedBytes: Long,
        val statusCode: Int,
        val finalUrl: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is MemoryTransferResult) {
                return false
            }
            return bytes.contentEquals(other.bytes) &&
                expectedBytes == other.expectedBytes &&
                statusCode == other.statusCode &&
                finalUrl == other.finalUrl
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + expectedBytes.hashCode()
            result = 31 * result + statusCode
            result = 31 * result + finalUrl.hashCode()
            return result
        }
    }

    private fun unwrapExecutionException(error: ExecutionException): IOException {
        return when (val cause = error.cause) {
            is IOException -> cause
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                TransferInterruptedException()
            }
            else -> IOException(cause)
        }
    }

    private fun checkpointFor(
        resourceIndex: Int,
        manifestUrl: String,
        playlist: HlsMediaPlaylist,
        rawMediaPlaylistUrl: String,
        target: File,
        nextSegmentIndex: Int,
        bytesWritten: Long,
        fingerprint: String,
    ): HlsResourceCheckpoint {
        return HlsResourceCheckpoint(
            resourceIndex = resourceIndex,
            manifestUrl = manifestUrl,
            mediaPlaylistUrl = rawMediaPlaylistUrl,
            tempFilePath = target.absolutePath,
            nextSegmentIndex = nextSegmentIndex,
            segmentByteOffset = 0,
            bytesWritten = bytesWritten,
            mediaSequence = playlist.mediaSequence,
            playlistFingerprint = fingerprint,
        )
    }

    private fun byteRangeStarts(segments: List<HlsSegment>): LongArray {
        val starts = LongArray(segments.size)
        val nextOffsets = mutableMapOf<String, Long>()
        for ((index, segment) in segments.withIndex()) {
            val byteRange = segment.byteRange
            if (byteRange != null) {
                val start = byteRange.offset ?: nextOffsets[segment.url] ?: 0L
                starts[index] = start
                nextOffsets[segment.url] = start + byteRange.length
            }
        }
        return starts
    }

    private fun fingerprint(playlist: HlsMediaPlaylist): String {
        return buildString {
            append(playlist.mediaSequence)
            append('|').append(playlist.segments.size)
            append('|').append(playlist.initSegment != null)
            append('@').append(playlist.initSegment?.byteRange)
            appendKey(playlist.initSegment?.encryptionKey)
            for (segment in playlist.segments) {
                append('|').append(segment.byteRange)
                append('@').append(segment.durationSeconds)
                appendKey(segment.encryptionKey)
            }
        }
    }

    private fun StringBuilder.appendKey(encryptionKey: HlsEncryptionKey?) {
        append('@').append(encryptionKey?.method)
        append('@').append(encryptionKey?.iv)
    }

    private fun withManifestCookie(url: String, manifestUrl: String): String {
        if (url.contains("#cookie=") || url.contains('#')) {
            return url
        }
        val cookieIndex = manifestUrl.indexOf("#cookie=")
        return if (cookieIndex < 0) url else url + manifestUrl.substring(cookieIndex)
    }

    @Throws(TransferInterruptedException::class)
    private fun ensureRunning() {
        if (!controller.isRunning() || Thread.currentThread().isInterrupted) {
            throw TransferInterruptedException()
        }
    }

    private data class ResolvedMediaPlaylist(
        val playlist: HlsMediaPlaylist,
        val rawMediaPlaylistUrl: String,
    )

    private data class DownloadedSegment(
        val segmentIndex: Int,
        val bytes: ByteArray,
        val statusCode: Int,
        val finalUrl: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is DownloadedSegment) {
                return false
            }
            return segmentIndex == other.segmentIndex &&
                bytes.contentEquals(other.bytes) &&
                statusCode == other.statusCode &&
                finalUrl == other.finalUrl
        }

        override fun hashCode(): Int {
            var result = segmentIndex
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + statusCode
            result = 31 * result + finalUrl.hashCode()
            return result
        }
    }

    private companion object {
        const val TAG = "HlsSegmentTransfer"
        const val MAX_INITIAL_MEMORY_BUFFER_BYTES = 16L * 1024L * 1024L
        const val AES_128_KEY_BYTES = 16
        const val AES_128_IV_BYTES = 16
        const val LONG_BYTES = 8

        val AlwaysRunningTransferController = object : TransferController {
            override fun isRunning(): Boolean = true
        }
    }
}
