package org.schabi.newpipe.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrProtocolException

class YoutubePageAttestationBootstrapTest {
    @Test
    fun parsesOnePageIntoOneContentBoundBootstrap() {
        val bootstrap = parseYoutubePageAttestationBootstrap(
            page(
                flags = "html5_generate_session_po_token=true&" +
                    "html5_generate_content_po_token=true",
                eventConfigFirst = true,
            ),
        )

        assertEquals("visitor=", bootstrap.visitorData)
        assertEquals("WEB", bootstrap.clientName)
        assertEquals("2.20260807.00.00", bootstrap.clientVersion)
        assertEquals(YoutubePoTokenBinding.CONTENT, bootstrap.binding)
        assertEquals("event_123-abc", bootstrap.eventId)
        assertEquals("program", bootstrap.challenge.program)
        assertEquals("global", bootstrap.challenge.globalName)
        assertEquals("script\nvalue", bootstrap.challenge.interpreterJavascript)
    }

    @Test
    fun selectsSessionBindingWhenContentBindingIsDisabled() {
        val bootstrap = parseYoutubePageAttestationBootstrap(
            page(flags = "html5_generate_session_po_token=true"),
        )

        assertEquals(YoutubePoTokenBinding.SESSION, bootstrap.binding)
    }

    @Test
    fun reportsNoBindingWhenPageDisablesBothTokenTypes() {
        val bootstrap = parseYoutubePageAttestationBootstrap(
            page(flags = "html5_generate_content_po_token=false"),
        )

        assertEquals(YoutubePoTokenBinding.NONE, bootstrap.binding)
    }

    @Test
    fun parsesInterpreterUrlAndSkipsMalformedInitialCall() {
        val challenge = challenge(
            "\"interpreterUrl\":{\"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue\":\"//example.test/bg.js\"}",
        )
        val html = page(flags = "html5_generate_content_po_token=true")
            .replace(
                "window.ytAtN(",
                "window.ytAtN({'R':'not-json'});window.ytAtN(",
            )
            .replace(escapedChallenge(), escapeAsJavascriptString(challenge))

        val bootstrap = parseYoutubePageAttestationBootstrap(html)

        assertEquals("https://example.test/bg.js", bootstrap.challenge.interpreterUrl)
    }

    @Test
    fun usesEventIdThatWasActiveAtInitialAttestationCall() {
        val html = page(flags = "html5_generate_content_po_token=true")
            .replace("window.ytAtN(", "window.ytAtN   (")
            .replace(
                "</script>",
                "ytcfg.set({\"EVENT_ID\":\"later_event\"});</script>",
            )

        val bootstrap = parseYoutubePageAttestationBootstrap(html)

        assertEquals("event_123-abc", bootstrap.eventId)
    }

    @Test
    fun rejectsPageWithoutMatchingEventId() {
        assertThrows(SabrProtocolException::class.java) {
            parseYoutubePageAttestationBootstrap(
                page(flags = "html5_generate_content_po_token=true")
                    .replace("\"EVENT_ID\":\"event_123-abc\",", ""),
            )
        }
    }

    @Test
    fun rejectsPageWithoutValidInitialChallenge() {
        assertThrows(SabrProtocolException::class.java) {
            parseYoutubePageAttestationBootstrap(
                page(flags = "html5_generate_content_po_token=true")
                    .replace(escapedChallenge(), "broken"),
            )
        }
    }

    private fun page(flags: String, eventConfigFirst: Boolean = false): String {
        val eventConfig = """ytcfg.set({"EVENT_ID":"event_123-abc","OTHER":{"text":"value }); still inside string"}});"""
        val clientConfig = """ytcfg.set({"EOM_VISITOR_DATA":"visitor%3D","INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.20260807.00.00"}},"WEB_PLAYER_CONTEXT_CONFIGS":{"WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH":{"serializedExperimentFlags":"$flags"}}});"""
        val configs = if (eventConfigFirst) eventConfig + clientConfig else clientConfig + eventConfig
        return "<script>$configs window.ytAtN({'T':'token','R':'${escapedChallenge()}'});</script>"
    }

    private fun escapedChallenge(): String {
        return escapeAsJavascriptString(
            challenge(
                "\"interpreterJavascript\":{\"privateDoNotAccessOrElseSafeScriptWrappedValue\":\"script\\nvalue\"}",
            ),
        )
    }

    private fun challenge(interpreter: String): String {
        return """{"bgChallenge":{"program":"program","globalName":"global",$interpreter}}"""
    }

    private fun escapeAsJavascriptString(value: String): String {
        return value.toByteArray().joinToString(separator = "") {
            "\\x%02x".format(it.toUByte().toInt())
        }
    }
}
