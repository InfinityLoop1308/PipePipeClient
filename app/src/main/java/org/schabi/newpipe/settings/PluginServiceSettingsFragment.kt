package org.schabi.newpipe.settings

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.plugin.PluginSetting
import org.schabi.newpipe.plugin.AndroidPluginPreferences
import org.schabi.newpipe.plugin.PluginManager
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.util.ThemeHelper

/**
 * Renders the settings a plugin declares in plugin.json. Values are stored in the plugin's
 * own SharedPreferences file, which the plugin observes through PluginPreferences.
 */
class PluginServiceSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val serviceId = requireArguments().getInt(ARG_SERVICE_ID)
        val manifest = PluginManager.getLoadedManifest(serviceId) ?: return
        val context = preferenceManager.context

        preferenceManager.sharedPreferencesName = AndroidPluginPreferences.fileName(manifest.pluginId)
        val screen = preferenceManager.createPreferenceScreen(context)
        screen.title = getString(R.string.plugin_settings_title, manifest.name)

        for (setting in manifest.settings) {
            val pref: Preference = when (setting.type) {
                PluginSetting.Type.BOOLEAN -> SwitchPreferenceCompat(context).apply {
                    setDefaultValue(setting.defaultValue?.toBoolean() ?: false)
                    summary = setting.summary
                }
                PluginSetting.Type.STRING -> EditTextPreference(context).apply {
                    setDefaultValue(setting.defaultValue)
                    dialogTitle = setting.title
                    // show the value, falling back to the description when unset
                    setSummaryProvider { p ->
                        (p as EditTextPreference).text?.takeIf { it.isNotBlank() } ?: setting.summary
                    }
                }
            }
            pref.key = setting.key
            pref.title = setting.title
            pref.isIconSpaceReserved = false
            pref.isSingleLineTitle = false
            pref.isPersistent = true
            screen.addPreference(pref)
        }
        preferenceScreen = screen
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(null)
        ThemeHelper.setTitleToAppCompatActivity(activity, preferenceScreen?.title)
    }

    override fun onDestroy() {
        // plugins may read values that the host also caches in services
        context?.let { ServiceHelper.initServices(it) }
        super.onDestroy()
    }

    companion object {
        private const val ARG_SERVICE_ID = "service_id"

        fun newInstance(serviceId: Int) = PluginServiceSettingsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_SERVICE_ID, serviceId) }
        }
    }
}
