package org.schabi.newpipe.player.datasource;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrPoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generates YouTube SABR PO tokens by running the official BotGuard challenge inside a headless
 * WebView (the legitimate attestation runtime), then handing the minted, videoId-bound token to the
 * extractor's SABR session via {@link SabrPoTokenProvider}.
 *
 * <p>Validated end to end (emulator + Pixel 8 / GrapheneOS Vanadium): the WebView produces a token
 * GenerateIT accepts and that flips SABR protection status 2 -> 1.</p>
 *
 * <p>The provider blocks the calling (loading) thread on a latch while the WebView, driven on the
 * main thread, runs the pipeline. Tokens are cached per videoId (~6h, well under the measured ~7-8h
 * lifetime).</p>
 */
public final class WebViewPoTokenProvider implements SabrPoTokenProvider {

    private static final String TAG = "WebViewPoToken";
    private static final String ASSET = "sabr_potoken_poc.js";
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";
    private static final long TOKEN_TTL_MS = 6L * 60L * 60L * 1000L; // 6 hours
    private static final long PIPELINE_TIMEOUT_MS = 45_000L;
    private static final int READY_RETRIES = 20;
    private static final long READY_POLL_MS = 250L;

    private static final class CachedToken {
        private final byte[] token;
        private final long mintedAtMs;

        CachedToken(final byte[] token, final long mintedAtMs) {
            this.token = token;
            this.mintedAtMs = mintedAtMs;
        }
    }

    private final Context appContext;
    private final Handler mainHandler;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();
    // one lock per videoId so two callers (pre-warm + pump) don't both fire the ~45s WebView mint
    // for the same video. second one just waits and takes the cached token.
    private final Map<String, Object> mintLocks = new ConcurrentHashMap<>();

    public WebViewPoTokenProvider(final Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Nullable
    @Override
    public byte[] getPoToken(final YoutubeSabrInfo info, final YoutubeSabrStreamState streamState) {
        return getPoToken(info, streamState, false);
    }

    @Nullable
    @Override
    public byte[] getPoToken(final YoutubeSabrInfo info, final YoutubeSabrStreamState streamState,
                             final boolean forceRefresh) {
        final String videoId = info.getVideoId();
        if (forceRefresh) {
            // Server rejected the cached token (expired): drop it and mint a fresh one.
            cache.remove(videoId);
        }
        synchronized (mintLocks.computeIfAbsent(videoId, k -> new Object())) {
            final long now = System.currentTimeMillis();
            final CachedToken cached = cache.get(videoId);
            if (cached != null && now - cached.mintedAtMs < TOKEN_TTL_MS) {
                return cached.token;
            }
            final String tokenB64 = mintBlocking(videoId);
            if (tokenB64 == null || tokenB64.isEmpty()) {
                return null;
            }
            final byte[] token;
            try {
                token = Base64.getUrlDecoder().decode(tokenB64);
            } catch (final IllegalArgumentException e) {
                Log.e(TAG, "could not decode PO token", e);
                return null;
            }
            cache.put(videoId, new CachedToken(token, now));
            return token;
        }
    }

    @Nullable
    private String mintBlocking(final String videoId) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> tokenRef = new AtomicReference<>();
        final AtomicReference<WebView> webViewRef = new AtomicReference<>();

        mainHandler.post(() -> {
            try {
                webViewRef.set(createWebView(videoId, tokenRef, latch));
            } catch (final Exception e) {
                Log.e(TAG, "failed to start WebView pipeline", e);
                latch.countDown();
            }
        });

        try {
            if (!latch.await(PIPELINE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "PO token pipeline timed out for " + videoId);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            mainHandler.post(() -> destroyWebView(webViewRef.getAndSet(null)));
        }
        return tokenRef.get();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView(final String videoId,
                                  final AtomicReference<String> tokenRef,
                                  final CountDownLatch latch) {
        final WebView webView = new WebView(appContext);
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(DESKTOP_UA);
        webView.addJavascriptInterface(new Bridge(tokenRef, latch), "SabrPocBridge");
        webView.setWebViewClient(new WebViewClient() {
            private boolean injected = false;

            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view,
                                                              final WebResourceRequest request) {
                final String url = request.getUrl().toString();
                if (url.contains("/js/th/")) {
                    return fetchWithCors(url);
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(final WebView view, final String url) {
                super.onPageFinished(view, url);
                if (injected || url == null || !url.contains("youtube.com")) {
                    return;
                }
                injected = true;
                waitForReadyThenInject(view, videoId, 0);
            }
        });
        webView.loadUrl("https://www.youtube.com/");
        return webView;
    }

    private void waitForReadyThenInject(final WebView view, final String videoId, final int attempt) {
        view.evaluateJavascript("document.readyState", value -> {
            final boolean complete = value != null && value.contains("complete");
            if (complete || attempt >= READY_RETRIES) {
                view.evaluateJavascript(
                        "window.__SABR_POC_VIDEO_ID=" + jsString(videoId) + ";", null);
                view.evaluateJavascript(loadPipelineScript(), null);
            } else {
                mainHandler.postDelayed(
                        () -> waitForReadyThenInject(view, videoId, attempt + 1), READY_POLL_MS);
            }
        });
    }

    private static void destroyWebView(@Nullable final WebView webView) {
        if (webView == null) {
            return;
        }
        try {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
        } catch (final Exception ignored) {
            // best effort
        }
    }

    private static String jsString(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String loadPipelineScript() {
        try (InputStream in = appContext.getAssets().open(ASSET);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            return out.toString("UTF-8");
        } catch (final Exception e) {
            Log.e(TAG, "could not read pipeline asset", e);
            return "";
        }
    }

    @Nullable
    private static WebResourceResponse fetchWithCors(final String url) {
        try {
            final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("User-Agent", DESKTOP_UA);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            final int code = connection.getResponseCode();
            final InputStream body = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            final String contentType = connection.getContentType();
            String mime = "application/javascript";
            if (contentType != null) {
                final int sep = contentType.indexOf(';');
                mime = sep > 0 ? contentType.substring(0, sep).trim() : contentType.trim();
            }
            final Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            final WebResourceResponse response = new WebResourceResponse(mime, "UTF-8", body);
            response.setStatusCodeAndReasonPhrase(code, code >= 400 ? "ERROR" : "OK");
            response.setResponseHeaders(headers);
            return response;
        } catch (final Exception e) {
            Log.e(TAG, "interpreter native fetch failed", e);
            return null;
        }
    }

    private static final class Bridge {
        private final AtomicReference<String> tokenRef;
        private final CountDownLatch latch;

        Bridge(final AtomicReference<String> tokenRef, final CountDownLatch latch) {
            this.tokenRef = tokenRef;
            this.latch = latch;
        }

        @JavascriptInterface
        public void onResult(final String json) {
            try {
                final JSONObject obj = new JSONObject(json);
                if (obj.optBoolean("ok", false)) {
                    tokenRef.set(obj.optString("poToken", null));
                } else {
                    Log.w(TAG, "PO token pipeline failed: " + obj.optString("error", "unknown"));
                }
            } catch (final Exception e) {
                Log.e(TAG, "could not parse pipeline result", e);
            } finally {
                latch.countDown();
            }
        }
    }
}
