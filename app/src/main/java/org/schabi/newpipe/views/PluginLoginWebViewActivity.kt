package org.schabi.newpipe.views

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import org.schabi.newpipe.R
import org.schabi.newpipe.plugin.AndroidPluginPreferences
import org.schabi.newpipe.plugin.PluginManager

/**
 * Cookie based web login for a plugin that declares `login` in plugin.json. The cookie
 * header is stored in the plugin's preferences, where the plugin reads it.
 */
class PluginLoginWebViewActivity : AppCompatActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manifest = PluginManager.getLoadedManifest(intent.getIntExtra(EXTRA_SERVICE_ID, -1))
        val login = manifest?.login
        if (manifest == null || login == null) {
            finish()
            return
        }
        setContentView(R.layout.login_webview)

        val view = findViewById<WebView>(R.id.login_webview)
        webView = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val cookies = CookieManager.getInstance().getCookie(url) ?: return
                if (cookies.split(';').any { it.trim().startsWith(login.cookie + "=") }) {
                    getSharedPreferences(
                        AndroidPluginPreferences.fileName(manifest.pluginId), MODE_PRIVATE
                    ).edit { putString(login.key, cookies) }
                    setResult(RESULT_OK)
                    finish()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                view.loadUrl(url)
                return true
            }
        }
        view.loadUrl(login.url)
    }

    override fun onDestroy() {
        webView?.let {
            it.stopLoading()
            it.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERVICE_ID = "service_id"
    }
}
