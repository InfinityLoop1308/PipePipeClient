package org.schabi.newpipe.youtube;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.services.youtube.YoutubePoTokenResult;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrAttestationException;
import java.lang.reflect.Field;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class SabrAttestationRecoveryTest {
    @Test
    public void rejectedCachedTokenIsRenewedBeforeRetry() throws Exception {
        final AtomicInteger generation = new AtomicInteger();
        final SabrAttestationRetryHandler handler = new SabrAttestationRetryHandler(
                "Hq2c7pww1Ds", id -> token(generation.get()), generation::incrementAndGet);
        final YoutubeSabrSession session = session();
        session.setPoToken(new byte[]{0});
        handler.prepareRetry(session, rejected());
        assertEquals("Renew rejected provider before retrying", 1, generation.get());
        assertArrayEquals(new byte[]{1}, tokenBytes(session));
    }

    @Test
    public void repeatedRejectionsStayBounded() throws Exception {
        final AtomicInteger renewals = new AtomicInteger();
        final SabrAttestationRetryHandler handler = new SabrAttestationRetryHandler(
                "Hq2c7pww1Ds", id -> token(renewals.get()), renewals::incrementAndGet);
        final YoutubeSabrSession session = session();
        for (int i = 0; i < 3; i++) handler.prepareRetry(session, rejected());
        try {
            handler.prepareRetry(session, rejected());
            fail("Fourth rejection must terminate recovery");
        } catch (SabrAttestationException expected) {
            assertTrue(expected.getMessage().contains("after 3"));
        }
    }

    @Test
    public void mediaProgressRestartsRecoveryBudget() throws Exception {
        final AtomicInteger generation = new AtomicInteger();
        final SabrAttestationRetryHandler handler = new SabrAttestationRetryHandler(
                "Hq2c7pww1Ds", id -> token(generation.get()), generation::incrementAndGet);
        final YoutubeSabrSession session = session();
        for (int i = 0; i < 3; i++) handler.prepareRetry(session, rejected());
        handler.onMediaReceived();
        handler.prepareRetry(session, rejected());
        assertEquals(4, generation.get());
    }

    @Test
    public void mintFailureDoesNotReplaceWorkingSessionToken() throws Exception {
        final SabrAttestationRetryHandler handler = new SabrAttestationRetryHandler(
                "Hq2c7pww1Ds", id -> { throw new IllegalStateException("mint failed"); },
                () -> { });
        final YoutubeSabrSession session = session();
        session.setPoToken(new byte[]{42});
        try {
            handler.prepareRetry(session, rejected());
            fail("Mint failure must stop recovery");
        } catch (SabrAttestationException expected) {
            assertTrue(expected.getMessage().contains("recovery failed"));
        }
        assertArrayEquals(new byte[]{42}, tokenBytes(session));
    }

    private static YoutubePoTokenResult token(final int value) {
        return new YoutubePoTokenResult("visitor-a", "client-a",
                Base64.getUrlEncoder().encodeToString(new byte[]{(byte) value}));
    }

    private static YoutubeSabrSession session() {
        return new YoutubeSabrSession(new YoutubeSabrInfo("Hq2c7pww1Ds", "cpn-a",
                "client-a", "visitor-a", "https://example.com/sabr-a", "AQ==",
                Collections.emptyList(), new byte[]{0}));
    }

    private static SabrAttestationException rejected() {
        return new SabrAttestationException("SABR attestation required: status=3");
    }

    private static byte[] tokenBytes(final YoutubeSabrSession session) throws Exception {
        final Field field = YoutubeSabrSession.class.getDeclaredField("poToken");
        field.setAccessible(true);
        return (byte[]) field.get(session);
    }
}
