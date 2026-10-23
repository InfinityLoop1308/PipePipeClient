package org.schabi.newpipe.views;

import android.content.Intent;
import android.graphics.Bitmap;

import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.schabi.newpipe.extractor.services.twitch.TwitchService;
import org.schabi.newpipe.extractor.utils.Utils;

import java.util.Arrays;

public class TwitchLoginWebViewActivity extends BaseLoginWebViewActivity {

    @Override
    protected String getLoginUrl() {
        return "https://twitch.tv/login";
    }

    @Override
    protected String getSuccessCookieIndicator() {
        return "";
    }

    @Override
    protected void configureWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
    }

    @Override
    protected WebViewClient createWebViewClient() {
        return new TwitchWebViewClient();
    }

    @Override
    protected void handleSuccessfulLogin(String cookies) {}

      private final class TwitchWebViewClient extends WebViewClient {

          @Override
          public void onPageStarted(WebView view, String url, Bitmap favicon) {
              if(!Utils.removeMAndWWWFromUrl(url).equalsIgnoreCase(TwitchService.BaseUrl))
                return;
              final var cookies =
                      CookieManager.getInstance().getCookie(TwitchService.BaseUrl).split("; ");

              final var apiTokenCookie = Arrays
                      .stream(cookies)
                      .filter(cookie -> cookie.split("=", 2)[0].trim().equals("api_token"))
                      .findFirst();

              if(apiTokenCookie.isEmpty())
                  return;

              final var apiTokenCookieValue = apiTokenCookie.get().split("=", 2)[1].trim();
              if(apiTokenCookieValue.startsWith("twilight."))
                  return;

              if(apiTokenCookieValue.length() != 30)
                  throw new RuntimeException("Expected Twitch token cookie to be of length 30, but got " + apiTokenCookieValue.length());

              final var intent = new Intent();
              intent.putExtra("cookie", apiTokenCookieValue);
              finishWithResult(intent);
          }
      }
}
