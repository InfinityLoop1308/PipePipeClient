package org.schabi.newpipe.util.dearrow

import android.content.Context
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R

/**
 * Reads [DeArrowConfig] from SharedPreferences.
 *
 * The result is cached because [read] runs on every row bind, on the main thread. A preference
 * listener drops the cache, so a toggle takes effect on the next bind without an app restart.
 */
object DeArrowSettings {
    @Volatile
    private var cached: DeArrowConfig? = null
    private var listening = false

    @JvmStatic
    fun read(context: Context): DeArrowConfig {
        cached?.let { return it }
        val config = readUncached(context.applicationContext)
        cached = config
        return config
    }

    private fun readUncached(context: Context): DeArrowConfig {
        watch(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(context.getString(R.string.dearrow_enable_key), false)) {
            return DeArrowConfig.DISABLED
        }
        return DeArrowConfig(
            enabled = true,
            replaceTitles = prefs.getBoolean(
                context.getString(R.string.dearrow_replace_titles_key), true),
            replaceThumbnails = prefs.getBoolean(
                context.getString(R.string.dearrow_replace_thumbnails_key), true),
            frameFallback = prefs.getBoolean(
                context.getString(R.string.dearrow_random_frame_key), true),
            autoFormatTitles = prefs.getBoolean(
                context.getString(R.string.dearrow_auto_format_key), true),
            originalFallback = prefs.getBoolean(
                context.getString(R.string.dearrow_original_fallback_key), true),
        )
    }

    @Synchronized
    private fun watch(context: Context) {
        if (listening) return
        listening = true
        PreferenceManager.getDefaultSharedPreferences(context)
            .registerOnSharedPreferenceChangeListener { _, _ -> cached = null }
    }
}
