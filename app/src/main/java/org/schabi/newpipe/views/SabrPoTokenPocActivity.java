package org.schabi.newpipe.views;

import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import org.schabi.newpipe.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Local POC activity: runs the SABR PO token pipeline inside a WebView on www.youtube.com and
 * reports the result. INTERNAL / LOCAL ONLY, not wired into any user-facing flow.
 *
 * <p>Launch on a debug build:</p>
 * <pre>
 *   adb shell am start -n &lt;applicationId&gt;/org.schabi.newpipe.views.SabrPoTokenPocActivity \
 *       -e videoId aqz-KE-bpKQ
 * </pre>
 *
 * <p>The full result (including the session-bound token) is written to the app-private files dir as
 * {@code sabr_poc_result.json}; a token-free summary is logged under the {@code SABR_POC} tag.</p>
 */
public class SabrPoTokenPocActivity extends AppCompatActivity {

    private static final String TAG = "SABR_POC";
    private static final String DEFAULT_VIDEO_ID = "aqz-KE-bpKQ";
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

    private WebView webView;
    private boolean injected = false;
    private String videoId = DEFAULT_VIDEO_ID;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.login_webview);

        final String extra = getIntent() != null ? getIntent().getStringExtra("videoId") : null;
        if (extra != null && !extra.isEmpty()) {
            videoId = extra;
        }

        webView = findViewById(R.id.login_webview);
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Desktop UA so YouTube serves www.youtube.com (consistent with the research mint context).
        settings.setUserAgentString(DESKTOP_UA);
        webView.addJavascriptInterface(new PocBridge(), "SabrPocBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view,
                                                              final WebResourceRequest request) {
                final String url = request.getUrl().toString();
                // The BotGuard interpreter is served cross-origin (www.google.com/js/th/...), which
                // a page-context fetch cannot read (no CORS header). Re-fetch it natively (no CORS)
                // and hand it back with a permissive ACAO header so the pipeline's fetch succeeds.
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
                Log.i(TAG, "page finished, injecting SABR PO token pipeline for videoId=" + videoId);
                view.evaluateJavascript(
                        "window.__SABR_POC_VIDEO_ID=" + jsString(videoId) + ";", null);
                view.evaluateJavascript(loadPipelineScript(), null);
            }
        });
        webView.loadUrl("https://www.youtube.com/");
    }

    private static String jsString(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String loadPipelineScript() {
        try (InputStream in = getAssets().open("sabr_potoken_poc.js");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            final StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } catch (final Exception e) {
            Log.e(TAG, "could not read pipeline asset", e);
            return "";
        }
    }

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
            Log.e(TAG, "interpreter native fetch failed for " + url, e);
            return null;
        }
    }

    private final class PocBridge {
        @JavascriptInterface
        public void onResult(final String json) {
            try {
                final File out = new File(getFilesDir(), "sabr_poc_result.json");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                }
                Log.i(TAG, "result written to " + out.getAbsolutePath());
            } catch (final Exception e) {
                Log.e(TAG, "could not persist result", e);
            }
            Log.i(TAG, "result: " + redactToken(json));
        }
    }

    private static String redactToken(final String json) {
        return json.replaceAll("\"poToken\":\"[^\"]*\"", "\"poToken\":\"<redacted>\"");
    }
}
