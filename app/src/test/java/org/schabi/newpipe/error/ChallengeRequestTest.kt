package org.schabi.newpipe.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

class ChallengeRequestTest {
    private val challenge = ReCaptchaException(
        "Bandcamp client challenge requested",
        "https://maoa.bandcamp.com/track/zoungla-master-of-illusion-maoa-remix"
    )

    @Test
    fun findsChallengeItself() {
        assertSame(challenge, ChallengeRequest.find(challenge))
    }

    @Test
    fun findsChallengeWrappedLikeThePlayerDoes() {
        // MediaSourceManager wraps stream info load failures, e.g. in StreamInfoLoadException
        val wrapped = Exception(ExtractionException("load failed", challenge))
        val found = ChallengeRequest.find(wrapped)
        assertSame(challenge, found)
        assertEquals(
            "https://maoa.bandcamp.com/track/zoungla-master-of-illusion-maoa-remix",
            found!!.url
        )
    }

    @Test
    fun ignoresOtherErrors() {
        assertNull(ChallengeRequest.find(IOException("network down")))
        assertNull(ChallengeRequest.find(ExtractionException("parse error")))
        assertNull(ChallengeRequest.find(null as Throwable?))
    }

    @Test
    fun findsChallengeAmongSeveralErrors() {
        val errors = listOf(IOException("first"), Exception("second", challenge))
        assertSame(challenge, ChallengeRequest.find(errors))
        assertNull(ChallengeRequest.find(listOf(IOException("only"))))
        assertNull(ChallengeRequest.find(emptyList<Throwable>()))
    }

    @Test
    fun stopsOnCyclicCauseChain() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)
        assertNull(ChallengeRequest.find(first))
    }

    @Test
    fun givesUpOnVeryDeepChains() {
        var error: Throwable = challenge
        repeat(32) { error = Exception("level $it", error) }
        assertNull(ChallengeRequest.find(error))
    }
}
