package org.schabi.newpipe.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.plugin.PluginManifest
import org.schabi.newpipe.plugin.AndroidPluginPreferences
import org.schabi.newpipe.plugin.PluginManager
import org.schabi.newpipe.youtube.LocalDomPoTokenProvider

/**
 * Keeps cookie based logins alive. The cookies are a snapshot taken at login, while sites
 * rotate short lived session cookies in the browser. The WebView cookie store still holds the
 * real session, so loading the site in a hidden WebView lets the site rotate them, and the
 * fresh cookie header is written back. If the login cookie is gone, the session has expired.
 */
object LoginSessions {

    private const val TAG = "LoginSessions"
    private const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val START_DELAY_MS = 15_000L
    private const val SETTLE_DELAY_MS = 5_000L
    private const val TIMEOUT_MS = 45_000L

    private const val YOUTUBE_UA = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"

    class Account(
        val name: String,
        val prefs: SharedPreferences,
        val cookiesKey: String,
        /** page loaded to let the site rotate its session cookies */
        val refreshUrl: String,
        /** url whose cookie header is stored, the same one the login screen stores */
        val cookieUrl: String,
        /** name of the cookie whose presence means logged in */
        val loginCookie: String,
        /** urls whose WebView cookies make up the web session */
        val webUrls: List<String>,
        val userAgent: String? = null,
    ) {
        fun isLoggedIn() = !prefs.getString(cookiesKey, null).isNullOrEmpty()
    }

    private fun defaultPrefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    @JvmStatic
    fun youtube(context: Context) = Account(
        "YouTube", defaultPrefs(context), context.getString(R.string.youtube_cookies_key),
        "https://music.youtube.com/", "https://music.youtube.com/watch", "SID",
        listOf(
            "https://www.youtube.com/", "https://music.youtube.com/", "https://m.youtube.com/",
            "https://accounts.google.com/", "https://www.google.com/",
        ),
        YOUTUBE_UA,
    )

    @JvmStatic
    fun bilibili(context: Context) = Account(
        "BiliBili", defaultPrefs(context), context.getString(R.string.bilibili_cookies_key),
        "https://www.bilibili.com/", "https://www.bilibili.com/", "SESSDATA",
        listOf("https://www.bilibili.com/", "https://passport.bilibili.com/"),
    )

    @JvmStatic
    fun niconico(context: Context) = Account(
        "NicoNico", defaultPrefs(context), context.getString(R.string.niconico_cookies_key),
        "https://www.nicovideo.jp/", "https://www.nicovideo.jp/", "user_session",
        listOf("https://www.nicovideo.jp/", "https://account.nicovideo.jp/"),
    )

    fun plugin(context: Context, manifest: PluginManifest): Account? {
        val login = manifest.login ?: return null
        return Account(
            manifest.name,
            context.getSharedPreferences(
                AndroidPluginPreferences.fileName(manifest.pluginId), Context.MODE_PRIVATE
            ),
            login.key, login.url, login.url, login.cookie, listOf(login.url),
        )
    }

    private fun all(context: Context): List<Account> =
        listOf(youtube(context), bilibili(context), niconico(context)) +
            PluginManager.getLoadedManifests().mapNotNull { plugin(context, it) }

    /**
     * Called after an account's stored cookies were cleared by a logout. Also ends the web
     * session unless the user chose to keep it.
     */
    @JvmStatic
    fun onLogout(context: Context, account: Account) {
        val keep = defaultPrefs(context).getBoolean(
            context.getString(R.string.logout_keep_webview_session_key), false
        )
        if (!keep) {
            clearWebCookies(account.webUrls)
        }
    }

    /** Clears the stored cookies of every account. The WebView cookies are cleared by the caller. */
    @JvmStatic
    fun logoutAll(context: Context) {
        all(context).forEach { it.prefs.edit { putString(it.cookiesKey, "") } }
        defaultPrefs(context).edit { putString(context.getString(R.string.youtube_po_token_key), "") }
        LocalDomPoTokenProvider.invalidate()
        ServiceHelper.initServices(context)
        InfoCache.getInstance().clearCache()
        App.reconcileYoutubePlayerClient(context)
    }

    /** CookieManager can't remove by domain, so expire every cookie these urls see. */
    private fun clearWebCookies(urls: List<String>) {
        val manager = CookieManager.getInstance()
        for (url in urls) {
            val header = manager.getCookie(url) ?: continue
            val host = Uri.parse(url).host ?: continue
            val parent = host.split('.').takeLast(2).joinToString(".")
            val names = header.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
            for (name in names) {
                // host only cookies (including __Host-) need no Domain attribute
                for (domain in listOf(null, host, ".$parent")) {
                    val attrs = domain?.let { "; Domain=$it" } ?: ""
                    manager.setCookie(url, "$name=; Max-Age=0; Path=/; Secure$attrs")
                }
            }
        }
        manager.flush()
    }

    private fun hasCookie(header: String?, name: String) =
        header?.split(';')?.any { it.trim().startsWith("$name=") } == true

    @JvmStatic
    fun scheduleRefresh(context: Context) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                refreshIfDue(app)
            } catch (e: Throwable) {
                // WebView may be missing or being updated
                Log.w(TAG, "Login refresh failed", e)
            }
        }, START_DELAY_MS)
    }

    private fun refreshIfDue(context: Context) {
        val prefs = defaultPrefs(context)
        val timeKey = context.getString(R.string.login_session_refresh_time_key)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(timeKey, 0) < REFRESH_INTERVAL_MS) {
            return
        }
        val accounts = all(context).filter { it.isLoggedIn() }
        if (accounts.isEmpty()) {
            return
        }
        prefs.edit { putLong(timeKey, now) }
        refreshNext(context, ArrayDeque(accounts))
    }

    private fun refreshNext(context: Context, queue: ArrayDeque<Account>) {
        val account = queue.removeFirstOrNull() ?: return
        refresh(context, account) { refreshNext(context, queue) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun refresh(context: Context, account: Account, done: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val webView = WebView(context)
        var finished = false
        var evaluating = false

        fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.destroy()
            done()
        }

        fun evaluate() {
            if (finished) return
            val cookies = CookieManager.getInstance().getCookie(account.cookieUrl)
            if (hasCookie(cookies, account.loginCookie)) {
                if (cookies != account.prefs.getString(account.cookiesKey, null)) {
                    account.prefs.edit { putString(account.cookiesKey, cookies) }
                    ServiceHelper.initServices(context)
                }
            } else {
                Toast.makeText(
                    context, context.getString(R.string.login_expired_toast, account.name),
                    Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        account.userAgent?.let { webView.settings.userAgentString = it }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (evaluating) return
                evaluating = true
                // give the page scripts time to rotate the cookies
                handler.postDelayed({ evaluate() }, SETTLE_DELAY_MS)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                // offline or blocked: no conclusion about the session
                if (request.isForMainFrame) finish()
            }
        }
        handler.postDelayed({ finish() }, TIMEOUT_MS)
        webView.loadUrl(account.refreshUrl)
    }
}
