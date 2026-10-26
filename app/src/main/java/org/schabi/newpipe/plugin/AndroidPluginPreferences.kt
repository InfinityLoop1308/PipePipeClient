package org.schabi.newpipe.plugin

import android.content.Context
import android.content.SharedPreferences
import org.schabi.newpipe.extractor.plugin.PluginPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [PluginPreferences] backed by a SharedPreferences file private to one plugin.
 * The plugin settings screen writes to the same file, so plugins see changes immediately.
 */
class AndroidPluginPreferences(context: Context, pluginId: String) : PluginPreferences {

    val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(fileName(pluginId), Context.MODE_PRIVATE)

    private val listeners = CopyOnWriteArrayList<PluginPreferences.ChangeListener>()

    // SharedPreferences keeps only a weak reference, so hold a strong one here
    private val bridge = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) {
            listeners.forEach { listener ->
                // a misbehaving plugin listener must not crash the settings screen
                runCatching { listener.onChanged(key) }
            }
        }
    }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(bridge)
    }

    override fun getString(key: String, defaultValue: String?): String? =
        sharedPreferences.getString(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        sharedPreferences.getBoolean(key, defaultValue)

    override fun putString(key: String, value: String?) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    override fun putBoolean(key: String, value: Boolean) {
        sharedPreferences.edit().putBoolean(key, value).apply()
    }

    override fun addChangeListener(listener: PluginPreferences.ChangeListener) {
        listeners.add(listener)
    }

    companion object {
        fun fileName(pluginId: String) = "plugin_$pluginId"
    }
}
