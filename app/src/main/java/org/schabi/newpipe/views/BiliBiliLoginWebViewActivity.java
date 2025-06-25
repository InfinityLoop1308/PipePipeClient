package org.schabi.newpipe.views;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import org.schabi.newpipe.R;

import java.util.HashMap;

public class BiliBiliLoginWebViewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.login_webview);

        WebView webView = findViewById(R.id.login_webview);
        webView.setWebViewClient(new MyWebViewClient());
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.131 Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "en-US,en;q=0.5");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("DNT", "1");
        headers.put("Connection", "keep-alive");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("Sec-Fetch-Dest", "document");
        headers.put("Sec-Fetch-Mode", "navigate");
        headers.put("Sec-Fetch-Site", "none");
        headers.put("Sec-Fetch-User", "?1");

        WebSettings settings = webView.getSettings();
        settings.setDomStorageEnabled(true);
        webView.getSettings().setJavaScriptEnabled(true);

        webView.loadUrl("https://passport.bilibili.com/login", headers);
    }

    private class MyWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && cookies.contains("SESSDATA=")) {
                Intent intent = new Intent();
                intent.putExtra("cookies", cookies);
                setResult(RESULT_OK, intent);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Now you are safely on the main UI thread
                        if (!isFinishing()) {
                            // It's also a good idea to tell the WebView to stop what it's doing
                            view.stopLoading();
                            view.loadUrl("about:blank");
                            view.onPause();
                            view.removeAllViews();
                            view.destroy();
                            finish();
                        }
                    }
                });
                // may need a clearHistory() here
            }
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            view.loadUrl(url);
            return true;
        }
    }
}
