package org.schabi.newpipe.plugin

import android.content.Context
import android.content.SharedPreferences
import org.schabi.newpipe.extractor.plugin.PluginPreferences
import org.schabi.newpipe.extractor.plugin.PluginSetting
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [PluginPreferences] backed by a SharedPreferences file private to one plugin.
 * The plugin settings screen writes to the same file, so plugins see changes immediately.
 */
class AndroidPluginPreferences(
    context: Context,
    pluginId: String,
    settings: List<PluginSetting> = emptyList()
) : PluginPreferences {

    /** Defaults declared in plugin.json, the same ones the settings screen shows. */
    private val declaredDefaults: Map<String, String?> =
        settings.associate { it.key to it.defaultValue }

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

    override fun getString(key: String): String? =
        sharedPreferences.getString(key, declaredDefaults[key])

    override fun getBoolean(key: String): Boolean =
        sharedPreferences.getBoolean(key, declaredDefaults[key]?.toBoolean() ?: false)

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
