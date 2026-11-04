package org.schabi.newpipe.youtube;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.*;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubePoTokenResult;
import org.schabi.newpipe.extractor.services.youtube.sabr.*;
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrAttestationException;
import org.schabi.newpipe.extractor.services.youtube.sabr.protocol.SabrProto;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class SabrTokenIdentityTest {
    @Test public void changedVisitorRefreshesCompleteBundleBeforeNextRequest() throws Exception {
        verifyRenewal("visitor-b", "client-a");
    }

    @Test public void changedClientRefreshesCompleteBundleBeforeNextRequest() throws Exception {
        verifyRenewal("visitor-a", "client-b");
    }

    @Test public void unchangedIdentityDoesNotReloadPlayer() throws Exception {
        final YoutubeSabrSession session = new YoutubeSabrSession(info("visitor-a", "client-a", 1));
        session.setInfoReloader((video, reload) -> { throw new AssertionError("Unneeded reload"); });
        handler("visitor-a", "client-a").prepareRetry(session, rejected());
        assertArrayEquals(new byte[]{2}, (byte[]) field(session, "poToken"));
    }

    @Test public void failedIdentityRefreshDoesNotInstallMismatchedToken() throws Exception {
        final YoutubeSabrSession session = new YoutubeSabrSession(info("visitor-a", "client-a", 1));
        session.setPoToken(new byte[]{1});
        session.setInfoReloader((video, reload) -> null);
        try {
            handler("visitor-b", "client-a").prepareRetry(session, rejected());
            fail("Missing matching player response must fail recovery");
        } catch (SabrAttestationException expected) {
            assertArrayEquals(new byte[]{1}, (byte[]) field(session, "poToken"));
            assertEquals("visitor-a", ((YoutubeSabrInfo) field(session, "info")).getVisitorData());
        }
    }

    private void verifyRenewal(final String visitor, final String client) throws Exception {
        final YoutubeSabrSession session = new YoutubeSabrSession(info("visitor-a", "client-a", 1));
        session.setPoToken(new byte[]{1});
        setField(session, "playbackCookie", new byte[]{99});
        setField(session, "requestNumber", 7);
        final AtomicInteger reloads = new AtomicInteger();
        session.setInfoReloader((video, reload) -> {
            reloads.incrementAndGet();
            return info(visitor, client, 2);
        });
        handler(visitor, client).prepareRetry(session, rejected());
        final Downloader previous = NewPipe.getDownloader();
        final Localization previousLocalization = NewPipe.getPreferredLocalization();
        final org.schabi.newpipe.extractor.localization.ContentCountry previousCountry =
                NewPipe.getPreferredContentCountry();
        final CapturingDownloader capture = new CapturingDownloader();
        try {
            NewPipe.init(capture, previousLocalization, previousCountry);
            session.requestOnce(YoutubeSabrRequest.preparation(61_000,
                    ((YoutubeSabrInfo) field(session, "info")).getFormats()),
                    segment -> { });
            assertTrue("Renewed token must use renewed streaming URL: " + capture.url,
                    capture.url.startsWith("https://example.com/sabr-2?"));
            assertTrue(capture.url.contains("cpn=cpn-2"));
            assertArrayEquals(new byte[]{2}, bytes(capture.body, 5));
            final byte[] context = bytes(capture.body, 19);
            assertArrayEquals(new byte[]{2}, bytes(context, 2));
            assertNull("Old playback cookie must not cross identities", bytes(context, 3));
            assertEquals(client, SabrProto.asString(bytes(bytes(context, 1), 17)));
            assertEquals(1, reloads.get());
        } finally {
            NewPipe.init(previous, previousLocalization, previousCountry);
        }
    }

    private static SabrAttestationRetryHandler handler(final String visitor, final String client) {
        return new SabrAttestationRetryHandler("Hq2c7pww1Ds",
                id -> new YoutubePoTokenResult(visitor, client, "Ag=="), () -> { });
    }

    private static YoutubeSabrInfo info(final String visitor, final String client, final int n)
            throws org.schabi.newpipe.extractor.exceptions.ParsingException {
        return new YoutubeSabrInfo("Hq2c7pww1Ds", "cpn-" + n, client, visitor,
                "https://example.com/sabr-" + n,
                Base64.getEncoder().encodeToString(new byte[]{(byte) n}),
                Collections.singletonList(YoutubeSabrInfo.Format.fromParsedFormat(
                        org.schabi.newpipe.extractor.services.youtube.ItagItem.getItag(140),
                        1, null, "audio/mp4", null, null, false, null, 0, 1)),
                new byte[]{(byte) n});
    }

    private static SabrAttestationException rejected() {
        return new SabrAttestationException("attestation required: status=3");
    }

    private static Object field(final Object object, final String name) throws Exception {
        final Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    private static void setField(final Object object, final String name, final Object value)
            throws Exception {
        final Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(object, value);
    }

    private static byte[] bytes(final byte[] message, final int number) throws Exception {
        for (SabrProto.Field field : SabrProto.readFields(message)) {
            if (field.getNumber() == number) return field.getBytes();
        }
        return null;
    }

    private static final class CapturingDownloader extends Downloader {
        private String url;
        private byte[] body;
        @Override public StreamingResponse postStreaming(final String target,
                final Map<String, List<String>> headers, final byte[] data,
                final Localization localization) {
            url = target;
            body = data.clone();
            return new StreamingResponse(200, Collections.singletonMap("Content-Type",
                    Collections.singletonList("application/vnd.yt-ump")),
                    new ByteArrayInputStream(new byte[0]));
        }
        @Override public Response execute(final Request request) throws IOException {
            throw new IOException("Unexpected request");
        }
        @Override public CancellableCall executeAsync(final Request request,
                final AsyncCallback callback) throws IOException {
            throw new IOException("Unexpected request");
        }
    }
}
