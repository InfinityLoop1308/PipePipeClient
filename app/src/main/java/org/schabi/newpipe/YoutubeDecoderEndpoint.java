package org.schabi.newpipe;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/** Maps the extractor's YouTube decoder API requests to a user-provided server. */
public final class YoutubeDecoderEndpoint {
    private static final String OFFICIAL_PREFIX = "https://api.pipepipe.dev/decoder/";

    private YoutubeDecoderEndpoint() {
    }

    public static boolean isValid(final String value) {
        final String input = value.trim();
        return input.isEmpty() || parseBaseUrl(input) != null;
    }

    /** Returns null for requests that should use their original URL. */
    static String overrideUrl(final String value, final String method, final String url)
            throws IOException {
        if (!"GET".equals(method) || !url.startsWith(OFFICIAL_PREFIX)) {
            return null;
        }

        final int queryStart = url.indexOf('?');
        final int pathEnd = queryStart < 0 ? url.length() : queryStart;
        final String route = url.substring(OFFICIAL_PREFIX.length(), pathEnd);
        if (!"decode".equals(route) && !"latest-player".equals(route)) {
            return null;
        }

        final String input = value.trim();
        if (input.isEmpty()) {
            return null;
        }
        final URI base = parseBaseUrl(input);
        if (base == null) {
            throw new IOException("Invalid custom YouTube decoder URL");
        }

        final String separator = base.getRawPath().endsWith("/") ? "" : "/";
        final String query = queryStart < 0 ? "" : url.substring(queryStart);
        return base.toASCIIString() + separator + route + query;
    }

    private static URI parseBaseUrl(final String value) {
        final URI url;
        try {
            url = new URI(value).parseServerAuthority();
        } catch (final URISyntaxException e) {
            return null;
        }
        final String host = url.getHost();
        if (host == null || url.getRawUserInfo() != null || url.getRawQuery() != null
                || url.getRawFragment() != null || url.getPort() == 0 || url.getPort() > 65535
                || url.getRawAuthority().endsWith(":")) {
            return null;
        }

        if ("https".equalsIgnoreCase(url.getScheme())) {
            return url;
        }
        if ("http".equalsIgnoreCase(url.getScheme())
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))) {
            return url;
        }
        return null;
    }
}
