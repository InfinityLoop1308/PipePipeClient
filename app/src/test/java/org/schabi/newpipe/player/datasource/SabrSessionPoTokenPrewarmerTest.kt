package org.schabi.newpipe.player.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SabrSessionPoTokenPrewarmerTest {
    @Test(timeout = 5_000)
    fun sameContextSharesOneInFlightTask() {
        val executor = Executors.newSingleThreadExecutor()
        val prewarmer = ContextBoundSingleFlight<String, String>(executor)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            assertTrue(prewarmer.start("context") {
                calls.incrementAndGet()
                started.countDown()
                release.await()
                "token"
            })
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val shared = prewarmer.inFlight("context")

            assertFalse(prewarmer.start("context") {
                calls.incrementAndGet()
                "duplicate"
            })
            release.countDown()

            assertEquals("token", shared?.get(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun replacingContextCancelsOldTask() {
        val executor = Executors.newSingleThreadExecutor()
        val prewarmer = ContextBoundSingleFlight<String, String>(executor)
        val started = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val replacementRelease = CountDownLatch(1)
        try {
            assertTrue(prewarmer.start("old") {
                started.countDown()
                CountDownLatch(1).await()
                "old-token"
            })
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val old = prewarmer.inFlight("old")

            assertTrue(prewarmer.start("new") {
                replacementStarted.countDown()
                replacementRelease.await()
                "new-token"
            })
            assertTrue(replacementStarted.await(2, TimeUnit.SECONDS))
            val replacement = prewarmer.inFlight("new")

            assertTrue(old?.isCancelled == true)
            replacementRelease.countDown()
            assertEquals("new-token", replacement?.get(2, TimeUnit.SECONDS))
            assertNull(prewarmer.inFlight("old"))
        } finally {
            replacementRelease.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun fullPlayerContextControlsTaskIdentity() {
        val context = YoutubeSessionPoTokenContext(
            "MWEB",
            "2.test",
            "test-user-agent",
            Localization("en", "US"),
            ContentCountry("US"),
            false,
            "credential-a",
        )

        assertNotEquals(context, context.copy(clientName = "WEB"))
        assertNotEquals(context, context.copy(clientVersion = "3.test"))
        assertNotEquals(context, context.copy(userAgent = "other-user-agent"))
        assertNotEquals(context, context.copy(localization = Localization("zh", "CN")))
        assertNotEquals(context, context.copy(contentCountry = ContentCountry("CN")))
        assertNotEquals(context, context.copy(loggedIn = true))
        assertNotEquals(context, context.copy(credentialIdentity = "credential-b"))
    }
}
