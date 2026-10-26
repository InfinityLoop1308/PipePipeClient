package org.schabi.newpipe.error

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Cookies obtained by solving a challenge (reCAPTCHA, Cloudflare, ...) in [ReCaptchaActivity].
 *
 * They are stored per registrable domain (eTLD+1, e.g. `youtube.com` for
 * `www.youtube.com`) and only sent back to that domain, so a challenge solved for one site is
 * never exposed to another site, whichever service or plugin made the request.
 */
object ChallengeCookies {
    private const val PREFS_NAME = "challenge_cookies"

    /** WebView profile used by [ReCaptchaActivity], kept apart from the default profile. */
    const val WEBVIEW_PROFILE = "challenge"

    private val cookies = ConcurrentHashMap<String, String>()

    @Volatile
    private var prefs: SharedPreferences? = null

    @JvmStatic
    fun init(context: Context) {
        val store = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        cookies.clear()
        store.all.forEach { (domain, value) ->
            if (value is String && value.isNotEmpty()) {
                cookies[domain] = value
            }
        }
        migrateLegacyRecaptchaCookies(context)
    }

    /** Cookies solved before they were stored per domain were all YouTube/Google ones. */
    private fun migrateLegacyRecaptchaCookies(context: Context) {
        val defaults = PreferenceManager.getDefaultSharedPreferences(context)
        val key = context.getString(R.string.recaptcha_cookies_key)
        if (!defaults.contains(key)) {
            return
        }
        val legacy = defaults.getString(key, null)
        if (!legacy.isNullOrEmpty() && !cookies.containsKey("youtube.com")) {
            put("youtube.com", legacy)
        }
        defaults.edit { remove(key) }
    }

    /**
     * @return the registrable domain of [url], its host if it has none (IP, localhost, ...),
     * or null if [url] is not a valid http(s) url
     */
    @JvmStatic
    fun domainOf(url: String?): String? {
        val httpUrl = url?.toHttpUrlOrNull() ?: return null
        return httpUrl.topPrivateDomain() ?: httpUrl.host
    }

    /** @return the cookies to send with a request to [url], or null if there are none */
    @JvmStatic
    fun get(url: String): String? = domainOf(url)?.let { cookies[it] }

    /** Replace the cookies of [domain]; an empty value removes them. */
    @JvmStatic
    fun put(domain: String, cookie: String?) {
        if (cookie.isNullOrEmpty()) {
            cookies.remove(domain)
            prefs?.edit { remove(domain) }
        } else {
            cookies[domain] = cookie
            prefs?.edit { putString(domain, cookie) }
        }
    }

    @JvmStatic
    fun isEmpty(): Boolean = cookies.isEmpty()

    @JvmStatic
    fun clear() {
        cookies.clear()
        prefs?.edit { clear() }
    }
}
