package org.schabi.newpipe;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.grack.nanojson.JsonParserException;
import okhttp3.*;
import org.schabi.newpipe.error.ReCaptchaActivity;
import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.bilibili.BilibiliService;
import org.schabi.newpipe.util.CookieUtils;
import org.schabi.newpipe.util.InfoCache;
import org.schabi.newpipe.util.TLSSocketFactoryCompat;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.schabi.newpipe.MainActivity.DEBUG;
import static org.schabi.newpipe.extractor.services.bilibili.BilibiliService.WWW_REFERER;

public final class DownloaderImpl extends Downloader {
    public static final String USER_AGENT
            = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
    public static final String YOUTUBE_RESTRICTED_MODE_COOKIE_KEY
            = "youtube_restricted_mode_key";
    public static final String YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000";
    public static final String YOUTUBE_DOMAIN = "youtube.com";

    private static DownloaderImpl instance;
    private final Map<String, String> mCookies;
    private final OkHttpClient client;
    private Integer customTimeout;

    private DownloaderImpl(final OkHttpClient.Builder builder) {
        this.client = builder
                .readTimeout(30, TimeUnit.SECONDS)
//                .cache(new Cache(new File(context.getExternalCacheDir(), "okhttp"),
//                        16 * 1024 * 1024))
                .build();
        this.mCookies = new HashMap<>();
    }

    /**
     * It's recommended to call exactly once in the entire lifetime of the application.
     *
     * @param builder if null, default builder will be used
     * @return a new instance of {@link DownloaderImpl}
     */
    public static DownloaderImpl init(@Nullable final OkHttpClient.Builder builder) {
        instance = new DownloaderImpl(
                builder != null ? builder : new OkHttpClient.Builder());
        return instance;
    }

    public static DownloaderImpl getInstance() {
        return instance;
    }

    public DownloaderImpl setCustomTimeout(final Integer value) {
        this.customTimeout = value;
        return this;
    }

    /**
     * Enable TLS 1.2 and 1.1 on Android Kitkat. This function is mostly taken
     * from the documentation of OkHttpClient.Builder.sslSocketFactory(_,_).
     * <p>
     * If there is an error, the function will safely fall back to doing nothing
     * and printing the error to the console.
     * </p>
     *
     * @param builder The HTTPClient Builder on which TLS is enabled on (will be modified in-place)
     */
    private static void enableModernTLS(final OkHttpClient.Builder builder) {
        try {
            // get the default TrustManager
            final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
            final TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
                throw new IllegalStateException("Unexpected default trust managers:"
                        + Arrays.toString(trustManagers));
            }
            final X509TrustManager trustManager = (X509TrustManager) trustManagers[0];

            // insert our own TLSSocketFactory
            final SSLSocketFactory sslSocketFactory = TLSSocketFactoryCompat.getInstance();

            builder.sslSocketFactory(sslSocketFactory, trustManager);

            // This will try to enable all modern CipherSuites(+2 more)
            // that are supported on the device.
            // Necessary because some servers (e.g. Framatube.org)
            // don't support the old cipher suites.
            // https://github.com/square/okhttp/issues/4053#issuecomment-402579554
            final List<CipherSuite> cipherSuites =
                    new ArrayList<>(ConnectionSpec.MODERN_TLS.cipherSuites());
            cipherSuites.add(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA);
            cipherSuites.add(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA);
            final ConnectionSpec legacyTLS = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .cipherSuites(cipherSuites.toArray(new CipherSuite[0]))
                    .build();

            builder.connectionSpecs(Arrays.asList(legacyTLS, ConnectionSpec.CLEARTEXT));
        } catch (final KeyManagementException | NoSuchAlgorithmException | KeyStoreException e) {
            if (DEBUG) {
                e.printStackTrace();
            }
        }
    }

    public String getCookies(final String url) {
        final List<String> resultCookies = new ArrayList<>();
        if (url.contains(YOUTUBE_DOMAIN)) {
            final String youtubeCookie = getCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY);
            if (youtubeCookie != null) {
                resultCookies.add(youtubeCookie);
            }
        }
        // Recaptcha cookie is always added TODO: not sure if this is necessary
        final String recaptchaCookie = getCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY);
        if (recaptchaCookie != null) {
            resultCookies.add(recaptchaCookie);
        }
        return CookieUtils.concatCookies(resultCookies);
    }

    public String getCookie(final String key) {
        return mCookies.get(key);
    }

    public void setCookie(final String key, final String cookie) {
        mCookies.put(key, cookie);
    }

    public void removeCookie(final String key) {
        mCookies.remove(key);
    }

    public void updateYoutubeRestrictedModeCookies(final Context context) {
        final boolean restrictedModeEnabled = false;
        updateYoutubeRestrictedModeCookies(restrictedModeEnabled);
    }

    public void updateYoutubeRestrictedModeCookies(final boolean youtubeRestrictedModeEnabled) {
        if (youtubeRestrictedModeEnabled) {
            setCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY,
                    YOUTUBE_RESTRICTED_MODE_COOKIE);
        } else {
            removeCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY);
        }
        InfoCache.getInstance().clearCache();
    }

    /**
     * Get the size of the content that the url is pointing by firing a HEAD request.
     *
     * @param url an url pointing to the content
     * @return the size of the content, in bytes
     */
    public long getContentLength(final String url) throws IOException {
        try {
            final Response response = head(url, BilibiliService.isBiliBiliDownloadUrl(url)?BilibiliService.getUserAgentHeaders(WWW_REFERER):null);
            return Long.parseLong(response.getHeader("Content-Length"));
        } catch (final NumberFormatException e) {
            throw new IOException("Invalid content length", e);
        } catch (final ReCaptchaException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Response execute(@NonNull final Request request)
            throws IOException, ReCaptchaException {
        final String httpMethod = request.httpMethod();
        final String url = request.url();
        final Map<String, List<String>> headers = request.headers();
        final byte[] dataToSend = request.dataToSend();

        RequestBody requestBody = null;
        if (dataToSend != null) {
            requestBody = RequestBody.create(null, dataToSend);
        } else if (httpMethod.equals("POST")) {
            requestBody = RequestBody.create("", null);
        }

        final okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder().method(httpMethod, requestBody).url(url);
        if (requestBuilder.getHeaders$okhttp().get("User-Agent") == null) {
            requestBuilder.header("User-Agent", USER_AGENT);
        }

        final String cookies = getCookies(url);
        if (!cookies.isEmpty() && requestBuilder.getHeaders$okhttp().get("Cookie") == null) {
            requestBuilder.header("Cookie", cookies);
        }

        for (final Map.Entry<String, List<String>> pair : headers.entrySet()) {
            final String headerName = pair.getKey();
            final List<String> headerValueList = pair.getValue();

            if (headerValueList.size() > 1) {
                requestBuilder.removeHeader(headerName);
                for (final String headerValue : headerValueList) {
                    requestBuilder.addHeader(headerName, headerValue);
                }
            } else if (headerValueList.size() == 1) {
                requestBuilder.header(headerName, headerValueList.get(0));
            }

        }

        OkHttpClient tmpClient = client;
        okhttp3.Response response = null;

        if(url.contains("pipepipe.dev")) {
            tmpClient = new OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
        } else if (customTimeout != null) {
            tmpClient = new OkHttpClient.Builder()
                    .readTimeout(customTimeout, TimeUnit.SECONDS)
                    .build();
        }

        int maxRetries = 2;
        int retryCount = 0;

        while (retryCount <= maxRetries && response == null) {
            try {
                response = tmpClient.newCall(requestBuilder.build()).execute();
            } catch (UnknownHostException e) {
                retryCount++;
                if (retryCount <= maxRetries) {
                    System.err.println("DNS lookup failed. Retrying (attempt " + retryCount + ")...");
                    try {
                        Thread.sleep(500); // Wait 0.5 second before retrying (optional)
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); // Preserve interrupt status
                        break; // Exit retry loop if interrupted
                    }
                } else {
                    System.err.println("DNS lookup failed after multiple retries.");
                    throw e;
                }
            }
        }

        if(response == null) {
            throw new IOException("Failed to execute request. We retried " + retryCount + " times.");
        }


        if (response.code() == 429) {
            response.close();

            throw new ReCaptchaException("reCaptcha Challenge requested", url);
        }

        final ResponseBody body = response.body();
        String responseBodyToReturn = null;
        byte[] rawBodyBytes = null;

        try {
            if (body != null) {
                rawBodyBytes = body.bytes(); // Read the raw bytes from the response body
                responseBodyToReturn = new String(rawBodyBytes, StandardCharsets.UTF_8); // Convert bytes to string
                // The body is closed after body.bytes() is called.
            }
        } finally {
            if (body != null) {
                body.close(); // Ensure the body is closed even if bytes() throws an IOException
            }
        }

        final String latestUrl = response.request().url().toString();
        return new Response(response.code(), response.message(), response.headers().toMultimap(),
                responseBodyToReturn, rawBodyBytes, latestUrl);
    }

    public CancellableCall executeAsync(@NonNull final Request request, @NonNull final Downloader.AsyncCallback callback) {
        final String httpMethod = request.httpMethod();
        final String url = request.url();
        final Map<String, List<String>> headers = request.headers();
        final byte[] dataToSend = request.dataToSend();

        RequestBody requestBody = null;
        if (dataToSend != null) {
            requestBody = RequestBody.create(null, dataToSend);
        }

        final okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .method(httpMethod, requestBody).url(url)
                .addHeader("User-Agent", USER_AGENT);

        final String cookies = getCookies(url);
        if (!cookies.isEmpty()) {
            requestBuilder.addHeader("Cookie", cookies);
        }

        for (final Map.Entry<String, List<String>> pair : headers.entrySet()) {
            final String headerName = pair.getKey();
            final List<String> headerValueList = pair.getValue();

            if (headerValueList.size() > 1) {
                requestBuilder.removeHeader(headerName);
                for (final String headerValue : headerValueList) {
                    requestBuilder.addHeader(headerName, headerValue);
                }
            } else if (headerValueList.size() == 1) {
                requestBuilder.header(headerName, headerValueList.get(0));
            }

        }

        OkHttpClient tmpClient = client;
        if (customTimeout != null) {
            tmpClient = new OkHttpClient.Builder()
                    .readTimeout(customTimeout, TimeUnit.SECONDS)
                    .build();
        }

        Call call = tmpClient.newCall(requestBuilder.build());
        CancellableCall cancellableCall = new CancellableCall(call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                cancellableCall.setFinished();
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, okhttp3.Response response) throws IOException {
                try {
                    if (response.code() == 429) {
                        callback.onError(new ReCaptchaException("reCaptcha Challenge requested", url));
                        return;
                    }

                    ResponseBody body = response.body();
                    String responseBodyToReturn = null;
                    byte[] rawBodyBytes = null;

                    if (body != null) {
                        rawBodyBytes = body.bytes();
                        responseBodyToReturn = new String(rawBodyBytes, StandardCharsets.UTF_8);
                    }

                    String latestUrl = response.request().url().toString();
                    Response newPipeResponse = new Response(response.code(), response.message(),
                            response.headers().toMultimap(), responseBodyToReturn, rawBodyBytes, latestUrl);

                    callback.onSuccess(newPipeResponse);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    response.close();
                    cancellableCall.setFinished();
                }
            }
        });
        return cancellableCall;
    }
}
