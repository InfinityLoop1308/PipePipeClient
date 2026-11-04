package org.schabi.newpipe.youtube

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.services.youtube.YoutubePoTokenResult
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrProtocolException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Deterministic retirement during an unrelated content-token mint. */
@RunWith(AndroidJUnit4::class)
class LocalDomPoTokenProviderConcurrencyTest {
    @Test
    fun retirementDuringMintRetriesWithReplacementIdentityAndToken() {
        val old = State("visitor-a", "client-a", "AQ==")
        val replacement = State("visitor-b", "client-b", "Ag==")
        val current = AtomicReference(old)
        val oldMintStarted = CountDownLatch(1)
        val retireMint = CountDownLatch(1)
        val calls = AtomicInteger()
        val result = AtomicReference<YoutubePoTokenResult>()
        val failure = AtomicReference<Throwable>()
        val worker = Thread({
            try {
                result.set(
                    mintFromCurrentState(current::get, { it.retired.get() }) { state ->
                        calls.incrementAndGet()
                        if (state === old) {
                            oldMintStarted.countDown()
                            check(retireMint.await(5, TimeUnit.SECONDS)) {
                                "Test must retire the old mint"
                            }
                            throw SabrProtocolException("Local DOM PO token session closed")
                        }
                        state.result()
                    },
                )
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "SabrRetiredMintProbe")
        worker.start()
        try {
            assertTrue("Old mint must be in flight", oldMintStarted.await(5, TimeUnit.SECONDS))
            current.set(replacement)
            old.retired.set(true)
            retireMint.countDown()
            worker.join(5_000)
            assertFalse("Retirement retry must finish", worker.isAlive)
            assertNull("Retiring one session must not fail another video's mint", failure.get())
            assertNotNull(result.get())
            assertEquals(2, calls.get())
            assertEquals("visitor-b", result.get().visitorData)
            assertEquals("client-b", result.get().clientVersion)
            assertEquals("Ag==", result.get().playerPoToken)
        } finally {
            retireMint.countDown()
            if (worker.isAlive) {
                worker.interrupt()
                worker.join(1_000)
            }
        }
    }

    @Test
    fun ordinaryMintFailureDoesNotRetry() {
        val state = State("visitor-a", "client-a", "AQ==")
        val calls = AtomicInteger()
        val expected = SabrProtocolException("Actual mint failure")
        try {
            mintFromCurrentState({ state }, { it.retired.get() }) {
                calls.incrementAndGet()
                throw expected
            }
            fail("An active mint's failure must propagate")
        } catch (error: SabrProtocolException) {
            assertSame(expected, error)
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun retirementDuringSuccessfulMintDoesNotReturnOldIdentity() {
        val old = State("visitor-a", "client-a", "AQ==")
        val replacement = State("visitor-b", "client-b", "Ag==")
        val current = AtomicReference(old)
        val calls = AtomicInteger()
        val result = mintFromCurrentState(current::get, { it.retired.get() }) { state ->
            calls.incrementAndGet()
            if (state === old) {
                current.set(replacement)
                old.retired.set(true)
            }
            state.result()
        }
        assertEquals(2, calls.get())
        assertEquals("visitor-b", result.visitorData)
        assertEquals("client-b", result.clientVersion)
        assertEquals("Ag==", result.playerPoToken)
    }

    @Test
    fun interruptedRetirementDoesNotRetry() {
        val state = State("visitor-a", "client-a", "AQ==")
        val calls = AtomicInteger()
        val expected = SabrProtocolException("Interrupted mint", InterruptedException())
        val previouslyInterrupted = Thread.interrupted()
        try {
            try {
                mintFromCurrentState({ state }, { it.retired.get() }) {
                    calls.incrementAndGet()
                    state.retired.set(true)
                    Thread.currentThread().interrupt()
                    throw expected
                }
                fail("Cancellation must propagate even when the session retires")
            } catch (error: SabrProtocolException) {
                assertSame(expected, error)
            }
            assertEquals(1, calls.get())
            assertTrue("Keep the cancellation flag", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (previouslyInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun interruptedRetiredSuccessDoesNotReturnOldToken() {
        val state = State("visitor-a", "client-a", "AQ==")
        val calls = AtomicInteger()
        val previouslyInterrupted = Thread.interrupted()
        try {
            try {
                mintFromCurrentState({ state }, { it.retired.get() }) {
                    calls.incrementAndGet()
                    state.retired.set(true)
                    Thread.currentThread().interrupt()
                    state.result()
                }
                fail("Cancellation must not return the retired session's cached token")
            } catch (expected: SabrProtocolException) {
                assertTrue(expected.message.orEmpty().contains("interrupted"))
            }
            assertEquals(1, calls.get())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (previouslyInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun repeatedRetirementHasBoundedAttempts() {
        val calls = AtomicInteger()
        try {
            mintFromCurrentState(
                { State("visitor", "client", "AQ==") },
                { it.retired.get() },
            ) { state ->
                calls.incrementAndGet()
                state.retired.set(true)
                throw SabrProtocolException("Local DOM PO token session closed")
            }
            fail("Continuous retirement must stop retrying")
        } catch (expected: SabrProtocolException) {
            assertEquals("Local DOM PO token session closed", expected.message)
        }
        assertEquals("Allow three total mint attempts during retirement", 3, calls.get())
    }

    @Test
    fun repeatedSuccessfulRetirementDoesNotReturnRetiredToken() {
        val calls = AtomicInteger()
        try {
            mintFromCurrentState(
                { State("visitor", "client", "AQ==") },
                { it.retired.get() },
            ) { state ->
                calls.incrementAndGet()
                state.retired.set(true)
                state.result()
            }
            fail("Continuous retirement must fail instead of returning an obsolete token")
        } catch (expected: SabrProtocolException) {
            assertTrue(expected.message.orEmpty().contains("retired"))
        }
        assertEquals(3, calls.get())
    }

    private class State(
        private val visitor: String,
        private val client: String,
        private val token: String,
    ) {
        val retired = AtomicBoolean()

        fun result() = YoutubePoTokenResult(visitor, client, token)
    }
}
