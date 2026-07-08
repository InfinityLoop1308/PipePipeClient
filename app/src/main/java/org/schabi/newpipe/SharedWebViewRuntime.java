package org.schabi.newpipe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single headless WebView used by local JavaScript services.
 *
 * <p>The runtime loads one blank first-party page, keeps it alive for the app process, and exposes
 * a stable JavaScript bridge. Callers keep their own JS namespaces and serialize their public entry
 * points on the caller side.</p>
 */
public final class SharedWebViewRuntime {
    public interface InitializationFailureCallback {
        void onInitializationFailure(@NonNull Throwable throwable);
    }

    public interface SabrLocalDomCallbacks {
        void onJsInitializationError(@NonNull String error);

        void onRunBotguardResult(@NonNull String botguardResponse);

        void onMinterReady();

        void onObtainPoTokenResult(@NonNull String identifier, @NonNull String poTokenU8);

        void onObtainPoTokenError(@NonNull String identifier, @NonNull String error);
    }

    public static final String BRIDGE_NAME = "PipePipeWebViewBridge";

    private static final String TAG = "SharedWebViewRuntime";
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3";
    private static volatile SharedWebViewRuntime instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object initLock = new Object();
    private final ConcurrentHashMap<String, SabrLocalDomCallbacks> sabrLocalDomCallbacks =
            new ConcurrentHashMap<>();

    @Nullable
    private CountDownLatch initLatch;
    @Nullable
    private AtomicReference<Throwable> initError;
    @Nullable
    private InitializationFailureCallback initializationFailureCallback;
    @Nullable
    private WebView webView;
    private volatile boolean ready;

    private SharedWebViewRuntime(final Context context) {
        appContext = context.getApplicationContext();
    }

    @NonNull
    public static SharedWebViewRuntime get(final Context context) {
        SharedWebViewRuntime runtime = instance;
        if (runtime == null) {
            synchronized (SharedWebViewRuntime.class) {
                runtime = instance;
                if (runtime == null) {
                    runtime = new SharedWebViewRuntime(context);
                    instance = runtime;
                }
            }
        }
        return runtime;
    }

    public static void warmUp(final Context context) {
        get(context).warmUp();
    }

    public void warmUp() {
        warmUp((InitializationFailureCallback) null);
    }

    public void warmUp(@Nullable final InitializationFailureCallback failureCallback) {
        final Throwable existingFailure;
        synchronized (initLock) {
            if (failureCallback != null) {
                initializationFailureCallback = failureCallback;
            }
            if (ready || initLatch != null) {
                existingFailure = initError == null ? null : initError.get();
                if (existingFailure == null) {
                    return;
                }
            } else {
                startInitializationLocked();
                return;
            }
        }
        if (failureCallback != null) {
            failureCallback.onInitializationFailure(existingFailure);
        }
    }

    public void ensureReady(final long timeoutMs, @NonNull final String operation)
            throws Exception {
        final CountDownLatch latch;
        final AtomicReference<Throwable> error;
        synchronized (initLock) {
            if (ready) {
                return;
            }
            if (initLatch == null) {
                startInitializationLocked();
            }
            latch = initLatch;
            error = initError;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(operation + " cannot wait on the main thread");
        }
        if (latch == null || error == null) {
            throw new IllegalStateException(operation + " did not start WebView initialization");
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(operation + " timed out waiting for WebView runtime");
        }
        final Throwable failure = error.get();
        if (failure != null) {
            throw new IllegalStateException(operation + " failed to initialize WebView runtime",
                    failure);
        }
    }

    @NonNull
    public String evaluateJavascriptBlocking(@NonNull final String script,
                                             final long timeoutMs,
                                             @NonNull final String operation) throws Exception {
        ensureReady(timeoutMs, operation);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(operation + " cannot wait on the main thread");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        if (!mainHandler.post(() -> {
            try {
                final WebView view = webView;
                if (view == null) {
                    throw new IllegalStateException("WebView runtime is not initialized");
                }
                view.evaluateJavascript(script, value -> {
                    result.set(value);
                    latch.countDown();
                });
            } catch (final Throwable throwable) {
                error.set(throwable);
                latch.countDown();
            }
        })) {
            throw new IllegalStateException(operation + " could not post JavaScript evaluation");
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(operation + " timed out");
        }
        final Throwable failure = error.get();
        if (failure != null) {
            throw new IllegalStateException(operation + " failed", failure);
        }
        return result.get();
    }

    public boolean evaluateJavascript(@NonNull final String script,
                                      @Nullable final ValueCallback<String> callback,
                                      @Nullable final ValueCallback<Throwable> errorCallback) {
        try {
            ensureReady(DEFAULT_TIMEOUT_MS, "async JavaScript evaluation");
        } catch (final Throwable throwable) {
            if (errorCallback != null) {
                errorCallback.onReceiveValue(throwable);
            }
            return false;
        }
        return mainHandler.post(() -> {
            try {
                final WebView view = webView;
                if (view == null) {
                    throw new IllegalStateException("WebView runtime is not initialized");
                }
                view.evaluateJavascript(script, callback);
            } catch (final Throwable throwable) {
                if (errorCallback != null) {
                    errorCallback.onReceiveValue(throwable);
                }
            }
        });
    }

    @NonNull
    public String loadAsset(@NonNull final String path) {
        try (InputStream in = appContext.getAssets().open(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (final Exception e) {
            throw new IllegalStateException("Could not load asset " + path, e);
        }
    }

    @NonNull
    public String registerSabrLocalDomCallbacks(@NonNull final SabrLocalDomCallbacks callbacks) {
        final String id = UUID.randomUUID().toString();
        sabrLocalDomCallbacks.put(id, callbacks);
        return id;
    }

    public void unregisterSabrLocalDomCallbacks(@NonNull final String id) {
        sabrLocalDomCallbacks.remove(id);
    }

    private void startInitializationLocked() {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        initLatch = latch;
        initError = error;
        if (!mainHandler.post(() -> createWebView(latch, error))) {
            final IllegalStateException exception =
                    new IllegalStateException("Could not post WebView creation");
            error.set(exception);
            latch.countDown();
            notifyInitializationFailure(exception);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView(final CountDownLatch latch,
                               final AtomicReference<Throwable> error) {
        try {
            if (webView != null) {
                ready = true;
                latch.countDown();
                return;
            }
            final WebView view = new WebView(appContext);
            if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true);
            }
            final WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(false);
            settings.setUserAgentString(USER_AGENT);
            settings.setBlockNetworkLoads(true);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(settings, false);
            }
            view.addJavascriptInterface(new Bridge(), BRIDGE_NAME);
            view.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(final ConsoleMessage message) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "console " + message.messageLevel() + ' '
                                + message.message() + " @" + message.sourceId()
                                + ':' + message.lineNumber());
                    }
                    return true;
                }
            });
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(final WebView view, final String url) {
                    synchronized (initLock) {
                        webView = view;
                        ready = true;
                    }
                    Log.i(TAG, "ready url=" + url + " mainThread="
                            + (Looper.myLooper() == Looper.getMainLooper()));
                    latch.countDown();
                }

                @Override
                public void onReceivedError(final WebView view, final WebResourceRequest request,
                                            final WebResourceError webError) {
                    super.onReceivedError(view, request, webError);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame()) {
                        final IllegalStateException exception = new IllegalStateException(
                                "WebView runtime main frame error " + webError.getErrorCode()
                                        + ": " + webError.getDescription());
                        if (!error.compareAndSet(null, exception)) {
                            return;
                        }
                        latch.countDown();
                        notifyInitializationFailure(exception);
                    }
                }
            });
            view.loadDataWithBaseURL("https://www.youtube.com/",
                    "<!doctype html><html><head><title></title></head><body></body></html>",
                    "text/html", "UTF-8", null);
        } catch (final Throwable throwable) {
            error.compareAndSet(null, throwable);
            latch.countDown();
            notifyInitializationFailure(throwable);
        }
    }

    private void notifyInitializationFailure(@NonNull final Throwable throwable) {
        final InitializationFailureCallback callback;
        synchronized (initLock) {
            callback = initializationFailureCallback;
        }
        if (callback != null) {
            callback.onInitializationFailure(throwable);
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void onSabrLocalDomJsInitializationError(final String sessionId,
                                                        final String error) {
            final SabrLocalDomCallbacks callbacks = sabrLocalDomCallbacks.get(sessionId);
            if (callbacks != null) {
                callbacks.onJsInitializationError(error == null ? "" : error);
            }
        }

        @JavascriptInterface
        public void onSabrLocalDomRunBotguardResult(final String sessionId,
                                                    final String botguardResponse) {
            final SabrLocalDomCallbacks callbacks = sabrLocalDomCallbacks.get(sessionId);
            if (callbacks != null) {
                callbacks.onRunBotguardResult(botguardResponse == null ? "" : botguardResponse);
            }
        }

        @JavascriptInterface
        public void onSabrLocalDomMinterReady(final String sessionId) {
            final SabrLocalDomCallbacks callbacks = sabrLocalDomCallbacks.get(sessionId);
            if (callbacks != null) {
                callbacks.onMinterReady();
            }
        }

        @JavascriptInterface
        public void onSabrLocalDomObtainPoTokenResult(final String sessionId,
                                                      final String identifier,
                                                      final String poTokenU8) {
            final SabrLocalDomCallbacks callbacks = sabrLocalDomCallbacks.get(sessionId);
            if (callbacks != null) {
                callbacks.onObtainPoTokenResult(identifier == null ? "" : identifier,
                        poTokenU8 == null ? "" : poTokenU8);
            }
        }

        @JavascriptInterface
        public void onSabrLocalDomObtainPoTokenError(final String sessionId,
                                                     final String identifier,
                                                     final String error) {
            final SabrLocalDomCallbacks callbacks = sabrLocalDomCallbacks.get(sessionId);
            if (callbacks != null) {
                callbacks.onObtainPoTokenError(identifier == null ? "" : identifier,
                        error == null ? "" : error);
            }
        }
    }
}
