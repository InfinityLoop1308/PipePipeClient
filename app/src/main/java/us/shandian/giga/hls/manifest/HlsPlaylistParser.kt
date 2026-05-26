package us.shandian.giga.hls.manifest

import java.io.IOException
import java.net.URI
import java.util.Locale

class HlsPlaylistParser {
    @Throws(IOException::class)
    fun parse(sourceUrl: String, body: String): HlsPlaylist {
        val lines = body.lineSequence()
            .map { it.trim().trimStart('\uFEFF') }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.none { it == EXTM3U }) {
            throw IOException("HLS playlist missing $EXTM3U firstLine=${safeLineSnippet(lines.firstOrNull())}")
        }

        val variants = parseVariants(sourceUrl, lines)
        if (variants.isNotEmpty()) {
            return HlsMasterPlaylist(sourceUrl, variants)
        }

        return parseMediaPlaylist(sourceUrl, lines)
    }

    private fun parseVariants(sourceUrl: String, lines: List<String>): List<HlsVariant> {
        val variants = mutableListOf<HlsVariant>()
        var pendingAttributes: Map<String, String>? = null

        for (line in lines) {
            when {
                line.startsWith(EXT_X_STREAM_INF) -> {
                    pendingAttributes = parseAttributes(line.substringAfter(':'))
                }
                pendingAttributes != null && !line.startsWith('#') -> {
                    val attributes = pendingAttributes
                    variants += HlsVariant(
                        url = resolveUrl(sourceUrl, line),
                        bandwidth = attributes["BANDWIDTH"]?.toLongOrNull(),
                        averageBandwidth = attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
                        codecs = attributes["CODECS"],
                        resolution = attributes["RESOLUTION"],
                        frameRate = attributes["FRAME-RATE"]?.toDoubleOrNull(),
                        audioGroupId = attributes["AUDIO"],
                    )
                    pendingAttributes = null
                }
            }
        }

        return variants
    }

    private fun parseMediaPlaylist(sourceUrl: String, lines: List<String>): HlsMediaPlaylist {
        val segments = mutableListOf<HlsSegment>()
        var targetDuration: Double? = null
        var mediaSequence = 0L
        var isEndList = false
        var pendingDuration: Double? = null
        var pendingTitle: String? = null
        var pendingByteRange: HlsByteRange? = null
        var pendingDiscontinuity = false
        var initSegment: HlsInitSegment? = null
        var hasEncryption = false
        var hasUnsupportedEncryption = false
        var hasDiscontinuity = false
        var currentEncryptionKey: HlsEncryptionKey? = null

        for (line in lines) {
            when {
                line.startsWith(EXT_X_TARGETDURATION) -> {
                    targetDuration = line.substringAfter(':').toDoubleOrNull()
                }
                line.startsWith(EXT_X_MEDIA_SEQUENCE) -> {
                    mediaSequence = line.substringAfter(':').toLongOrNull() ?: 0L
                }
                line.startsWith(EXT_X_BYTERANGE) -> {
                    pendingByteRange = parseByteRange(line.substringAfter(':'))
                }
                line.startsWith(EXT_X_MAP) -> {
                    initSegment = parseInitSegment(sourceUrl, line.substringAfter(':'), currentEncryptionKey)
                }
                line.startsWith(EXT_X_KEY) -> {
                    val attributes = parseAttributes(line.substringAfter(':'))
                    val method = attributes["METHOD"]
                    currentEncryptionKey = when {
                        method == null || method.equals("NONE", ignoreCase = true) -> null
                        else -> {
                            hasEncryption = true
                            if (!method.equals("AES-128", ignoreCase = true)) {
                                hasUnsupportedEncryption = true
                            }
                            HlsEncryptionKey(
                                method = method,
                                url = attributes["URI"]?.let { resolveUrl(sourceUrl, it) },
                                iv = attributes["IV"],
                            )
                        }
                    }
                }
                line == EXT_X_DISCONTINUITY -> {
                    pendingDiscontinuity = true
                    hasDiscontinuity = true
                }
                line.startsWith(EXTINF) -> {
                    val value = line.substringAfter(':')
                    pendingDuration = value.substringBefore(',').toDoubleOrNull()
                    pendingTitle = value.substringAfter(',', "").ifBlank { null }
                }
                line == EXT_X_ENDLIST -> {
                    isEndList = true
                }
                !line.startsWith('#') -> {
                    segments += HlsSegment(
                        url = resolveUrl(sourceUrl, line),
                        durationSeconds = pendingDuration,
                        title = pendingTitle,
                        byteRange = pendingByteRange,
                        discontinuity = pendingDiscontinuity,
                        encryptionKey = currentEncryptionKey,
                    )
                    pendingDuration = null
                    pendingTitle = null
                    pendingByteRange = null
                    pendingDiscontinuity = false
                }
            }
        }

        return HlsMediaPlaylist(
            sourceUrl = sourceUrl,
            targetDurationSeconds = targetDuration,
            mediaSequence = mediaSequence,
            segments = segments,
            isEndList = isEndList,
            initSegment = initSegment,
            hasEncryption = hasEncryption,
            hasUnsupportedEncryption = hasUnsupportedEncryption,
            hasDiscontinuity = hasDiscontinuity,
        )
    }

    private fun parseInitSegment(
        sourceUrl: String,
        value: String,
        encryptionKey: HlsEncryptionKey?,
    ): HlsInitSegment? {
        val attributes = parseAttributes(value)
        val uri = attributes["URI"] ?: return null
        return HlsInitSegment(
            url = resolveUrl(sourceUrl, uri),
            byteRange = attributes["BYTERANGE"]?.let { parseByteRange(it) },
            encryptionKey = encryptionKey,
        )
    }

    private fun parseAttributes(value: String): Map<String, String> {
        return splitAttributeList(value).mapNotNull { attribute ->
            val key = attribute.substringBefore('=', "").trim()
            if (key.isBlank()) {
                null
            } else {
                key.uppercase(Locale.US) to attribute.substringAfter('=', "").trim().trim('"')
            }
        }.toMap()
    }

    private fun splitAttributeList(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false

        for (char in value) {
            when (char) {
                '"' -> {
                    quoted = !quoted
                    current.append(char)
                }
                ',' -> if (quoted) {
                    current.append(char)
                } else {
                    result += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
        }

        if (current.isNotEmpty()) {
            result += current.toString()
        }

        return result
    }

    private fun parseByteRange(value: String): HlsByteRange? {
        val length = value.substringBefore('@').toLongOrNull() ?: return null
        val offset = value.substringAfter('@', "").toLongOrNull()
        return HlsByteRange(length, offset)
    }

    private fun resolveUrl(sourceUrl: String, reference: String): String {
        return URI(sourceUrl).resolve(reference).toString()
    }

    private fun safeLineSnippet(line: String?): String {
        return line
            ?.take(80)
            ?.map { char -> if (char.code in 32..126) char else '.' }
            ?.joinToString(separator = "")
            ?: "<none>"
    }

    private companion object {
        const val EXTM3U = "#EXTM3U"
        const val EXTINF = "#EXTINF:"
        const val EXT_X_STREAM_INF = "#EXT-X-STREAM-INF:"
        const val EXT_X_TARGETDURATION = "#EXT-X-TARGETDURATION:"
        const val EXT_X_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
        const val EXT_X_BYTERANGE = "#EXT-X-BYTERANGE:"
        const val EXT_X_MAP = "#EXT-X-MAP:"
        const val EXT_X_KEY = "#EXT-X-KEY:"
        const val EXT_X_DISCONTINUITY = "#EXT-X-DISCONTINUITY"
        const val EXT_X_ENDLIST = "#EXT-X-ENDLIST"
    }
}
