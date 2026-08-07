package org.schabi.newpipe.player.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDomPoTokenRequestTest {
    private val context = LocalDomPoTokenContext(
        visitorData = "visitor-test",
        clientName = "WEB",
        clientVersion = "2.test",
        userAgent = "test-user-agent",
    )

    @Test
    fun parsesInlineAttestationChallenge() {
        val response = """{"bgChallenge":{"program":"program","globalName":"global","interpreterJavascript":{"privateDoNotAccessOrElseSafeScriptWrappedValue":"script"}}}"""

        assertEquals(
            SabrAttChallengeData("program", "global", "script", null),
            parseSabrAttChallengeData(response),
        )
    }

    @Test
    fun resolvesProtocolRelativeAttestationInterpreterUrl() {
        val response = """{"bgChallenge":{"program":"program","globalName":"global","interpreterUrl":{"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue":"//example.test/interpreter.js"}}}"""

        assertEquals(
            SabrAttChallengeData(
                "program",
                "global",
                null,
                "https://example.test/interpreter.js",
            ),
            parseSabrAttChallengeData(response),
        )
    }

    @Test
    fun parsesPageNativeAttestationAndEventId() {
        val challenge = """{"bgChallenge":{"program":"program","globalName":"global","interpreterJavascript":{"privateDoNotAccessOrElseSafeScriptWrappedValue":"script"}}}"""
        val escapedChallenge = challenge.toByteArray().joinToString("") {
            "\\x%02x".format(it.toUByte().toInt())
        }
        val page = """<script>ytcfg.set({"EVENT_ID":"event_123-abc"});window.ytAtN({'R':'$escapedChallenge'});</script>"""

        assertEquals(
            SabrYoutubePageAttestation("event_123-abc", challenge),
            parseSabrYoutubePageAttestation(page),
        )
    }

    @Test
    fun rejectsPageAttestationWithoutEventId() {
        val page = """<script>window.ytAtN({'R':'{}'});</script>"""

        assertThrows(IllegalArgumentException::class.java) {
            parseSabrYoutubePageAttestation(page)
        }
    }

    @Test
    fun rejectsPageAttestationWithoutInitialChallenge() {
        val page = """<script>ytcfg.set({"EVENT_ID":"event_123"});</script>"""

        assertThrows(IllegalArgumentException::class.java) {
            parseSabrYoutubePageAttestation(page)
        }
    }

    @Test
    fun cacheIdentityDoesNotCrossClientContexts() {
        assertNotEquals(
            context.cacheIdentity,
            context.copy(clientName = "MWEB").cacheIdentity,
        )
        assertNotEquals(
            context.cacheIdentity,
            context.copy(clientVersion = "3.test").cacheIdentity,
        )
        assertNotEquals(
            context.cacheIdentity,
            context.copy(visitorData = "other-visitor").cacheIdentity,
        )
        assertNotEquals(
            context.cacheIdentity,
            context.copy(userAgent = "different-user-agent").cacheIdentity,
        )
    }
}
