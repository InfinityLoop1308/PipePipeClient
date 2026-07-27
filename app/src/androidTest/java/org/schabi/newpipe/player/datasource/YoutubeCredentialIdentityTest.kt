package org.schabi.newpipe.player.datasource

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.schabi.newpipe.extractor.ServiceList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class YoutubeCredentialIdentityTest {
    @Test
    fun accountSwitchWithoutProviderCallDuringLogoutInvalidatesState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(
            "sabr_local_dom_video_token_cache",
            Context.MODE_PRIVATE,
        )
        val originalTokens = ServiceList.YouTube.getTokens()
        prefs.edit().clear().commit()
        try {
            ServiceList.YouTube.setTokens("account-a-cookie")
            val provider = LocalDomPoTokenProvider(context)
            provider.hasCachedToken("missing-video")
            prefs.edit().putString("account-a-token", "sentinel").commit()

            // No provider call observes the logged-out state before account B logs in.
            ServiceList.YouTube.setTokens("")
            ServiceList.YouTube.setTokens("account-b-cookie")
            provider.hasCachedToken("missing-video")

            assertFalse(prefs.contains("account-a-token"))
        } finally {
            ServiceList.YouTube.setTokens(originalTokens)
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun unchangedCredentialsDoNotInvalidateState() {
        var invalidationCount = 0
        val tracker = CredentialIdentityTracker { invalidationCount++ }
        val identity = youtubeCredentialIdentity(true, "same-cookie")

        tracker.observe(identity)
        tracker.observe(identity)

        assertEquals(0, invalidationCount)
    }

    @Test
    fun loggedInIdentityDependsOnCredentialValue() {
        assertNotEquals(
            youtubeCredentialIdentity(true, "account-a-cookie"),
            youtubeCredentialIdentity(true, "account-b-cookie"),
        )
    }
}
