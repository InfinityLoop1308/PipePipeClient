package org.schabi.newpipe.player.datasource

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import java.util.Base64

internal data class SabrAttChallengeData(
    val program: String,
    val globalName: String,
    val interpreterJavascript: String?,
    val interpreterUrl: String?,
)

internal data class SabrYoutubePageAttestation(
    val eventId: String,
    val rawChallengeData: String,
)

internal fun parseSabrYoutubePageAttestation(pageHtml: String): SabrYoutubePageAttestation {
    val eventId = EVENT_ID_PATTERN.find(pageHtml)?.groupValues?.get(1)
        ?: throw IllegalArgumentException("YouTube page has no EVENT_ID")
    val call = YT_AT_N_PATTERN.find(pageHtml)
        ?: throw IllegalArgumentException("YouTube page has no initial attestation call")
    val responseProperty = YT_AT_N_RESPONSE_PATTERN.find(pageHtml, call.range.last + 1)
        ?: throw IllegalArgumentException("YouTube page attestation has no response payload")
    val quote = responseProperty.groupValues[1].single()
    val rawChallengeData = decodeJavascriptString(
        pageHtml,
        responseProperty.range.last + 1,
        quote,
    )
    parseSabrAttChallengeData(rawChallengeData)
    return SabrYoutubePageAttestation(eventId, rawChallengeData)
}

internal fun parseSabrAttChallengeData(rawAttestationData: String): SabrAttChallengeData {
    val challenge = JsonParser.`object`().from(rawAttestationData).getObject("bgChallenge")
    val interpreterJavascript = challenge.getObject("interpreterJavascript")
        ?.getString("privateDoNotAccessOrElseSafeScriptWrappedValue")
        ?.takeIf { it.isNotEmpty() }
    val rawInterpreterUrl = challenge.getObject("interpreterUrl")
        ?.getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
        ?.takeIf { it.isNotEmpty() }
    val interpreterUrl = rawInterpreterUrl?.let {
        if (it.startsWith("//")) "https:$it" else it
    }
    require(interpreterJavascript != null || interpreterUrl != null) {
        "Attestation challenge has no interpreter script or URL"
    }
    return SabrAttChallengeData(
        program = challenge.getString("program"),
        globalName = challenge.getString("globalName"),
        interpreterJavascript = interpreterJavascript,
        interpreterUrl = interpreterUrl,
    )
}

internal fun buildSabrAttChallengeData(
    challengeData: SabrAttChallengeData,
    interpreterJavascript: String,
): String {
    return JsonWriter.string(
        JsonObject.builder()
            .`object`("interpreterJavascript")
            .value(
                "privateDoNotAccessOrElseSafeScriptWrappedValue",
                interpreterJavascript,
            )
            .end()
            .value("program", challengeData.program)
            .value("globalName", challengeData.globalName)
            .done(),
    )
}

internal fun parseSabrIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JsonParser.array().from(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData.getString(0)) to integrityTokenData.getLong(1)
}

internal fun stringToSabrU8(value: String): String {
    return newUint8Array(value.toByteArray())
}

internal fun csvU8ToByteArray(value: String): ByteArray {
    if (value.isBlank()) {
        return ByteArray(0)
    }
    return value.split(",").map { it.toUByte().toByte() }.toByteArray()
}

private fun base64ToU8(base64: String): String {
    return newUint8Array(base64ToByteArray(base64))
}

private fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" + contents.joinToString(separator = ",") {
        it.toUByte().toString()
    } + "])"
}

private fun base64ToByteArray(base64: String): ByteArray {
    val normalized = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')
    return Base64.getDecoder().decode(normalized)
}

private fun decodeJavascriptString(source: String, start: Int, quote: Char): String {
    val result = StringBuilder()
    var index = start
    while (index < source.length) {
        val character = source[index++]
        if (character == quote) {
            return result.toString()
        }
        if (character != '\\') {
            result.append(character)
            continue
        }
        require(index < source.length) { "Incomplete JavaScript string escape" }
        when (val escaped = source[index++]) {
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'v' -> result.append('\u000B')
            'x' -> {
                result.append(readJavascriptHex(source, index, 2).toChar())
                index += 2
            }
            'u' -> {
                result.append(readJavascriptHex(source, index, 4).toChar())
                index += 4
            }
            '\n' -> Unit
            '\r' -> if (index < source.length && source[index] == '\n') index++
            else -> result.append(escaped)
        }
    }
    throw IllegalArgumentException("Unterminated JavaScript string")
}

private fun readJavascriptHex(source: String, start: Int, length: Int): Int {
    require(start + length <= source.length) { "Incomplete hexadecimal escape" }
    var value = 0
    repeat(length) { offset ->
        val digit = source[start + offset].digitToIntOrNull(16)
            ?: throw IllegalArgumentException("Invalid hexadecimal escape")
        value = value * 16 + digit
    }
    return value
}

private val EVENT_ID_PATTERN = Regex("\\\"EVENT_ID\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]+)\\\"")
private val YT_AT_N_PATTERN = Regex("""window\.ytAtN\s*\(""")
private val YT_AT_N_RESPONSE_PATTERN = Regex("""['"]R['"]\s*:\s*(['"])""")
