package org.schabi.newpipe.views;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import org.schabi.newpipe.R;

public class NicoNicoLoginWebViewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.login_webview);

        WebView webView = findViewById(R.id.login_webview);
        webView.setWebViewClient(new MyWebViewClient());
        webView.loadUrl("https://account.nicovideo.jp/login?site=niconico");
    }

    private class MyWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && cookies.contains("user_session")) {
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
            }
        }
    }
}
