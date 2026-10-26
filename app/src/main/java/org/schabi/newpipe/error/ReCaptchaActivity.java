package org.schabi.newpipe.error;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NavUtils;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.schabi.newpipe.databinding.ActivityRecaptchaBinding;
import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ThemeHelper;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

/*
 * Created by beneth <bmauduit@beneth.fr> on 06.12.16.
 *
 * Copyright (C) Christian Schabesberger 2015 <chris.schabesberger@mailbox.org>
 * ReCaptchaActivity.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */
public class ReCaptchaActivity extends AppCompatActivity {
    public static final int RECAPTCHA_REQUEST = 10;
    public static final String RECAPTCHA_URL_EXTRA = "recaptcha_url_extra";
    public static final String TAG = ReCaptchaActivity.class.toString();
    public static final String YT_URL = "https://www.youtube.com";

    public static String sanitizeRecaptchaUrl(@Nullable final String url) {
        if (url == null || url.trim().isEmpty()) {
            return YT_URL; // YouTube is the most likely service to have thrown a recaptcha
        } else {
            // remove "pbj=1" parameter from YouYube urls, as it makes the page JSON and not HTML
            return url.replace("&pbj=1", "").replace("pbj=1&", "").replace("?pbj=1", "");
        }
    }

    private ActivityRecaptchaBinding recaptchaBinding;
    // registrable domain -> cookie name -> cookie value
    private final Map<String, Map<String, String>> foundCookies = new LinkedHashMap<>();
    private CookieManager cookieManager;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);

        recaptchaBinding = ActivityRecaptchaBinding.inflate(getLayoutInflater());
        setContentView(recaptchaBinding.getRoot());
        setSupportActionBar(recaptchaBinding.toolbar);

        final String url = sanitizeRecaptchaUrl(getIntent().getStringExtra(RECAPTCHA_URL_EXTRA));
        // set return to Cancel by default
        setResult(RESULT_CANCELED);

        // Use a dedicated profile when possible, so that the challenge neither sees nor wipes the
        // cookies of the default profile (e.g. the YouTube login page). Must be done before
        // anything else is done with the WebView.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.setProfile(recaptchaBinding.reCaptchaWebView,
                    ChallengeCookies.WEBVIEW_PROFILE);
            cookieManager = ProfileStore.getInstance()
                    .getOrCreateProfile(ChallengeCookies.WEBVIEW_PROFILE).getCookieManager();
        } else {
            cookieManager = CookieManager.getInstance();
        }
        // challenge widgets (e.g. Cloudflare Turnstile) may be embedded from another site
        cookieManager.setAcceptThirdPartyCookies(recaptchaBinding.reCaptchaWebView, true);

        // enable Javascript
        final WebSettings webSettings = recaptchaBinding.reCaptchaWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setUserAgentString(DownloaderImpl.USER_AGENT);

        recaptchaBinding.reCaptchaWebView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public boolean shouldOverrideUrlLoading(final WebView view, final String url) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "shouldOverrideUrlLoading: url=" + url);
                }

                handleCookiesFromUrl(url);
                return false;
            }

            @Override
            public void onPageFinished(final WebView view, final String url) {
                super.onPageFinished(view, url);
                handleCookiesFromUrl(url);
            }
        });

        // cleaning cache, history and cookies from webView, so that only cookies obtained by
        // this challenge are collected. Cookies of previous challenges are already stored.
        recaptchaBinding.reCaptchaWebView.clearCache(true);
        recaptchaBinding.reCaptchaWebView.clearHistory();
        cookieManager.removeAllCookies(value ->
                recaptchaBinding.reCaptchaWebView.loadUrl(url));
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_recaptcha, menu);

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
            actionBar.setTitle(R.string.title_activity_recaptcha);
            actionBar.setSubtitle(R.string.subtitle_activity_recaptcha);
        }

        return true;
    }

    @Override
    public void onBackPressed() {
        saveCookiesAndFinish();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == R.id.menu_item_done) {
            saveCookiesAndFinish();
            return true;
        }
        return false;
    }

    private void saveCookiesAndFinish() {
        // try to get cookies of unclosed page
        handleCookiesFromUrl(recaptchaBinding.reCaptchaWebView.getUrl());
        if (MainActivity.DEBUG) {
            Log.d(TAG, "saveCookiesAndFinish: foundCookies=" + foundCookies);
        }

        if (!foundCookies.isEmpty()) {
            // each domain only gets back its own cookies
            for (final Map.Entry<String, Map<String, String>> entry : foundCookies.entrySet()) {
                ChallengeCookies.put(entry.getKey(), joinCookies(entry.getValue()));
            }
            setResult(RESULT_OK);
        }

        // Navigate to blank page (unloads youtube to prevent background playback)
        recaptchaBinding.reCaptchaWebView.loadUrl("about:blank");

        final Intent intent = new Intent(this, org.schabi.newpipe.MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        NavUtils.navigateUpTo(this, intent);
    }


    private void handleCookiesFromUrl(@Nullable final String url) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "handleCookiesFromUrl: url=" + (url == null ? "null" : url));
        }

        if (url == null) {
            return;
        }
        final String domain = ChallengeCookies.domainOf(url);
        if (domain == null) {
            return;
        }

        handleCookies(domain, cookieManager.getCookie(url));

        // sometimes cookies are inside the url
        final int abuseStart = url.indexOf("google_abuse=");
        if (abuseStart != -1) {
            final int abuseEnd = url.indexOf("+path");

            try {
                String abuseCookie = url.substring(abuseStart + 13, abuseEnd);
                abuseCookie = URLDecoder.decode(abuseCookie, "UTF-8");
                handleCookies(domain, abuseCookie);
            } catch (UnsupportedEncodingException | StringIndexOutOfBoundsException e) {
                if (MainActivity.DEBUG) {
                    e.printStackTrace();
                    Log.d(TAG, "handleCookiesFromUrl: invalid google abuse starting at "
                            + abuseStart + " and ending at " + abuseEnd + " for url " + url);
                }
            }
        }
    }

    /**
     * Remember the cookies the WebView holds for {@code domain}, later values of a cookie
     * override earlier ones.
     */
    private void handleCookies(@NonNull final String domain, @Nullable final String cookies) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "handleCookies: domain=" + domain
                    + " cookies=" + (cookies == null ? "null" : cookies));
        }

        if (cookies == null || cookies.trim().isEmpty()) {
            return;
        }

        Map<String, String> domainCookies = foundCookies.get(domain);
        if (domainCookies == null) {
            domainCookies = new LinkedHashMap<>();
            foundCookies.put(domain, domainCookies);
        }
        for (final String pair : cookies.split(";")) {
            final String trimmed = pair.trim();
            final int separator = trimmed.indexOf('=');
            if (separator > 0) {
                domainCookies.put(trimmed.substring(0, separator),
                        trimmed.substring(separator + 1));
            }
        }
    }

    @NonNull
    private static String joinCookies(@NonNull final Map<String, String> cookies) {
        final StringBuilder builder = new StringBuilder();
        for (final Map.Entry<String, String> cookie : cookies.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(cookie.getKey()).append('=').append(cookie.getValue());
        }
        return builder.toString();
    }
}
