package org.schabi.newpipe.error;

import org.schabi.newpipe.player.ChallengeAlert;

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
import android.text.TextUtils;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.schabi.newpipe.util.NavigationHelper;

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
    /** Finish back to whatever is below instead of navigating up to the main screen. */
    public static final String RETURN_TO_CALLER_EXTRA = "recaptcha_return_to_caller_extra";
    /** Link to open in the app once the challenge is solved, e.g. a link shared to the app. */
    public static final String OPEN_AFTER_SOLVE_EXTRA = "recaptcha_open_after_solve_extra";
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
    @Nullable
    private Disposable openLinkDisposable;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);

        recaptchaBinding = ActivityRecaptchaBinding.inflate(getLayoutInflater());
        setContentView(recaptchaBinding.getRoot());
        // the user responded to a challenge alert, if there is one
        ChallengeAlert.silence();
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

        final boolean solved = !foundCookies.isEmpty();
        if (solved) {
            // each domain only gets back its own cookies
            for (final Map.Entry<String, Map<String, String>> entry : foundCookies.entrySet()) {
                ChallengeCookies.put(entry.getKey(), joinCookies(entry.getValue()));
            }
            setResult(RESULT_OK);
        }
        // a player paused on this challenge retries once cookies are stored
        ChallengeAlert.onChallengeScreenClosed(foundCookies.keySet());

        // Navigate to blank page (unloads youtube to prevent background playback)
        recaptchaBinding.reCaptchaWebView.loadUrl("about:blank");

        if (getCallingActivity() != null
                || getIntent().getBooleanExtra(RETURN_TO_CALLER_EXTRA, false)) {
            // Go back to the screen that needed the challenge (e.g. an album page), which is
            // kept in the back stack and reloads on RESULT_OK. Navigating up to MainActivity
            // would reset it to the main screen.
            finish();
            return;
        }

        // Started from a shared link (RouterActivity, already finished)
        final String linkToOpen = getIntent().getStringExtra(OPEN_AFTER_SOLVE_EXTRA);
        if (solved && !TextUtils.isEmpty(linkToOpen)) {
            openLinkAndFinish(linkToOpen);
            return;
        }

        // nothing to go back to: open the app
        final Intent intent = new Intent(this, org.schabi.newpipe.MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        NavUtils.navigateUpTo(this, intent);
    }


    /**
     * Open the link the challenge interrupted, e.g. the album shared to the app, then finish.
     * Resolving the link may need the network (custom domains), so it runs in the background
     * while this activity stays visible, and starting the next screen from a visible activity
     * is always allowed. Falls back to the main screen if the link cannot be resolved.
     */
    private void openLinkAndFinish(@NonNull final String link) {
        if (openLinkDisposable != null) {
            return; // already opening, e.g. Done pressed twice
        }
        openLinkDisposable = Single.fromCallable(() -> NavigationHelper.getIntentByLink(this, link))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(intent -> {
                    startActivity(intent);
                    finish();
                }, throwable -> {
                    Log.e(TAG, "Cannot open " + link + " after the challenge", throwable);
                    final Intent intent = new Intent(this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    NavUtils.navigateUpTo(this, intent);
                });
    }

    @Override
    protected void onDestroy() {
        if (openLinkDisposable != null) {
            openLinkDisposable.dispose();
        }
        super.onDestroy();
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
