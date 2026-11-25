package org.schabi.newpipe;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class YoutubeDecoderEndpointTest {
    private static final String DEFAULT_API = "https://api.pipepipe.dev/decoder";
    private static final String LOCAL_API = "http://127.0.0.1:4009";

    @Test
    public void emptySettingUsesDefaultServer() throws IOException {
        assertTrue(YoutubeDecoderEndpoint.isValid(" "));
        assertNull(YoutubeDecoderEndpoint.overrideUrl(" ", "GET", DEFAULT_API + "/decode"));
        assertNull(YoutubeDecoderEndpoint.overrideUrl("", "GET", DEFAULT_API + "/latest-player"));
    }

    @Test
    public void redirectsBothDecoderRequestsWithoutReencodingTheQuery() throws IOException {
        final String query = "?player=deadbeef&n=a%2Bb,x%26y&sig=abc%3D";

        assertEquals(LOCAL_API + "/decode" + query, YoutubeDecoderEndpoint.overrideUrl(
                LOCAL_API, "GET", DEFAULT_API + "/decode" + query));
        assertEquals("https://example.org/ejs/latest-player", YoutubeDecoderEndpoint.overrideUrl(
                " https://example.org/ejs/ ", "GET", DEFAULT_API + "/latest-player"));
    }

    @Test
    public void preservesUnrelatedRequests() throws IOException {
        for (final String url : new String[]{
            "https://api.pipepipe.dev/token-status",
            DEFAULT_API + "/health",
            DEFAULT_API + "/decode/extra?sig=abc",
            "https://api.pipepipe.dev.example.org/decoder/decode?sig=abc",
            "https://example.org/decoder/decode?sig=abc"
        }) {
            assertNull(url, YoutubeDecoderEndpoint.overrideUrl(LOCAL_API, "GET", url));
        }
        assertNull(YoutubeDecoderEndpoint.overrideUrl(LOCAL_API, "POST", DEFAULT_API + "/decode"));
    }

    @Test
    public void validatesServerBaseUrls() {
        assertTrue(YoutubeDecoderEndpoint.isValid(LOCAL_API));
        assertTrue(YoutubeDecoderEndpoint.isValid("http://localhost:4009"));
        assertTrue(YoutubeDecoderEndpoint.isValid("https://example.org:8443/decoder"));

        for (final String url : new String[]{
            "example.org",
            "https:example.org",
            "http://192.168.1.5:4009",
            "http://localhost.example.org:4009",
            "https://user:password@example.org",
            "https://example.org?key=value",
            "https://example.org#decoder",
            "https://example.org:",
            "https://example.org:65536"
        }) {
            assertFalse(url, YoutubeDecoderEndpoint.isValid(url));
        }
    }

    @Test
    public void invalidStoredSettingDoesNotUseDefaultServer() {
        assertThrows(IOException.class, () -> YoutubeDecoderEndpoint.overrideUrl(
                "http://example.org", "GET", DEFAULT_API + "/latest-player"));
    }
}
