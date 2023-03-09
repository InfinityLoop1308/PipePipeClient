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
            if (url.equals("https://sp.nicovideo.jp/")) {
                String cookies = CookieManager.getInstance().getCookie(url);
                Intent intent = new Intent();
                intent.putExtra("cookies", cookies);
                setResult(RESULT_OK, intent);
                finish();
            }
        }
    }
}
