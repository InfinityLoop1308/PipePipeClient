package org.schabi.newpipe.settings

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.snackbar.Snackbar
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.DialogEditTextBinding
import org.schabi.newpipe.extractor.plugin.PluginRepository
import org.schabi.newpipe.plugin.PluginManager
import org.schabi.newpipe.util.NavigationHelper

/**
 * Opt-in switch, repositories, installed and available plugins.
 *
 * Plugins are off by default and enabling them requires confirming a warning that states
 * they bypass store review (F-Droid inclusion policy, "explicit user consent").
 */
class PluginSettingsFragment : BasePreferenceFragment() {

    private val disposables = CompositeDisposable()
    private var repositories: Map<String, Result<PluginRepository>> = emptyMap()
    private var restartSnackbar: Snackbar? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()

        val enabledSwitch = requirePreference(R.string.plugins_enabled_key) as SwitchPreferenceCompat
        enabledSwitch.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                confirmEnable(enabledSwitch)
                false // the dialog sets the value
            } else {
                // loaded plugins keep running until the process restarts
                showRestartHint()
                rebuild()
                true
            }
        }
        rebuild()
        refreshRepositories()
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    private fun confirmEnable(enabledSwitch: SwitchPreferenceCompat) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.plugins_warning_title)
            .setMessage(R.string.plugins_warning_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.plugins_warning_accept) { _, _ ->
                enabledSwitch.isChecked = true
                rebuild()
                refreshRepositories()
                if (PluginManager.getInstalled(requireContext()).isNotEmpty()) {
                    showRestartHint()
                }
            }
            .show()
    }

    /** Recreate everything below the switch from the current state. */
    private fun rebuild() {
        val screen = preferenceScreen
        for (i in screen.preferenceCount - 1 downTo 0) {
            val pref = screen.getPreference(i)
            if (pref.key != getString(R.string.plugins_enabled_key)) {
                screen.removePreference(pref)
            }
        }
        val context = requireContext()
        if (!PluginManager.isEnabled(context)) {
            return
        }

        val installed = PluginManager.getInstalled(context)
        val installedCategory = category(R.string.plugins_installed)
        if (installed.isEmpty()) {
            installedCategory.addPreference(info(getString(R.string.plugins_none_installed)))
        }
        for (plugin in installed) {
            val error = PluginManager.getLoadError(plugin.pluginId)
            val available = availableEntries().firstOrNull { it.second.pluginId == plugin.pluginId }
            val updatable = available != null &&
                available.second.versionCode > plugin.versionCode &&
                available.second.isApiCompatible
            installedCategory.addPreference(
                Preference(context).apply {
                    isIconSpaceReserved = false
                    isSingleLineTitle = false
                    title = plugin.name
                    summary = authorLine(plugin.author.ifEmpty { loadedAuthor(plugin.pluginId) }) + when {
                        error != null -> getString(R.string.plugin_status_error, plugin.versionName, error)
                        PluginManager.isLoaded(plugin.pluginId) -> getString(R.string.plugin_status_loaded, plugin.versionName)
                        else -> getString(R.string.plugin_status_pending, plugin.versionName)
                    } + if (updatable) " · " + getString(R.string.plugin_update) + " " + available!!.second.versionName else ""
                    setOnPreferenceClickListener {
                        showInstalledActions(plugin, if (updatable) available else null)
                        true
                    }
                }
            )
        }

        val availableCategory = category(R.string.plugins_available)
        val notInstalled = availableEntries()
            .filter { (_, entry) -> installed.none { it.pluginId == entry.pluginId } }
        for ((repoUrl, entry) in notInstalled) {
            availableCategory.addPreference(
                Preference(context).apply {
                    isIconSpaceReserved = false
                    isSingleLineTitle = false
                    title = entry.name + " " + entry.versionName
                    summary = authorLine(entry.author) + if (entry.isApiCompatible) {
                        entry.description ?: entry.pluginId
                    } else {
                        getString(R.string.plugin_incompatible)
                    }
                    isEnabled = entry.isApiCompatible
                    setOnPreferenceClickListener {
                        confirmInstall(repoUrl, entry)
                        true
                    }
                }
            )
        }

        val repoCategory = category(R.string.plugins_repositories)
        val repoUrls = PluginManager.getRepositories(context)
        if (repoUrls.isEmpty()) {
            repoCategory.addPreference(info(getString(R.string.plugins_no_repositories)))
        }
        for (url in repoUrls) {
            val result = repositories[url]
            repoCategory.addPreference(
                Preference(context).apply {
                    isIconSpaceReserved = false
                    isSingleLineTitle = false
                    title = result?.getOrNull()?.name ?: url
                    summary = result?.exceptionOrNull()?.let {
                        getString(R.string.plugin_repository_failed, url, it.message ?: it.javaClass.simpleName)
                    } ?: url
                    setOnPreferenceClickListener {
                        if (url == PluginManager.BUILTIN_REPOSITORY) {
                            return@setOnPreferenceClickListener true
                        }
                        AlertDialog.Builder(context)
                            .setTitle(R.string.plugins_remove_repository)
                            .setMessage(url)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.delete) { _, _ ->
                                PluginManager.removeRepository(context, url)
                                repositories = repositories - url
                                rebuild()
                            }
                            .show()
                        true
                    }
                }
            )
        }
        if (PluginManager.AWESOME_REPOSITORY !in repoUrls) {
            repoCategory.addPreference(
                Preference(context).apply {
                    isIconSpaceReserved = false
                    isSingleLineTitle = false
                    title = getString(R.string.plugins_add_awesome_repository)
                    summary = getString(R.string.plugins_add_awesome_repository_summary)
                    setOnPreferenceClickListener {
                        PluginManager.addRepository(context, PluginManager.AWESOME_REPOSITORY)
                        rebuild()
                        refreshRepositories()
                        true
                    }
                }
            )
        }
        repoCategory.addPreference(
            Preference(context).apply {
                isIconSpaceReserved = false
                title = getString(R.string.plugins_add_repository)
                setOnPreferenceClickListener {
                    showAddRepository()
                    true
                }
            }
        )
    }

    /** "by xxx" followed by a line break, or nothing when the author is unknown. */
    private fun authorLine(author: String?): String =
        if (author.isNullOrBlank()) "" else getString(R.string.plugin_author, author) + "\n"

    private fun loadedAuthor(pluginId: String): String? =
        installedPluginServiceId(pluginId)?.let { PluginManager.getLoadedManifest(it)?.author }

    private fun availableEntries(): List<Pair<String, PluginRepository.Entry>> =
        repositories.mapNotNull { (url, result) -> result.getOrNull()?.let { url to it } }
            .flatMap { (url, repo) -> repo.plugins.map { url to it } }
            // the first repository wins when several publish the same plugin
            .distinctBy { it.second.pluginId }

    private fun category(title: Int) = PreferenceCategory(requireContext()).also {
        it.layoutResource = R.layout.settings_category_header_layout
        it.title = getString(title)
        it.isIconSpaceReserved = false
        preferenceScreen.addPreference(it)
    }

    private fun info(text: String) = Preference(requireContext()).apply {
        summary = text
        isSelectable = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
    }

    private fun refreshRepositories() {
        val context = requireContext()
        if (!PluginManager.isEnabled(context)) {
            return
        }
        val urls = PluginManager.getRepositories(context)
        disposables.add(
            Single.fromCallable {
                urls.associateWith { url -> runCatching { PluginManager.fetchRepository(url) } }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ result ->
                    repositories = result
                    rebuild()
                }, { /* fetchRepository errors are captured per repository */ })
        )
    }

    private fun showAddRepository() {
        val binding = DialogEditTextBinding.inflate(layoutInflater)
        binding.dialogEditText.hint = getString(R.string.plugins_repository_url_hint)
        binding.dialogEditText.inputType = InputType.TYPE_TEXT_VARIATION_URI
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.plugins_add_repository)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val url = binding.dialogEditText.text.toString().trim()
                if (!url.startsWith("https://")) {
                    Toast.makeText(context, R.string.plugins_repository_https_only, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                PluginManager.addRepository(requireContext(), url)
                rebuild()
                refreshRepositories()
            }
            .show()
    }

    private fun confirmInstall(repoUrl: String, entry: PluginRepository.Entry) {
        AlertDialog.Builder(requireContext())
            .setTitle(entry.name)
            .setMessage(getString(R.string.plugin_install_from, entry.name, repoUrl))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.install) { _, _ -> install(repoUrl, entry) }
            .show()
    }

    private fun install(repoUrl: String, entry: PluginRepository.Entry) {
        val context = requireContext().applicationContext
        Toast.makeText(context, getString(R.string.plugin_installing, entry.name), Toast.LENGTH_SHORT).show()
        disposables.add(
            Single.fromCallable { PluginManager.install(context, repoUrl, entry) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    rebuild()
                    showRestartHint()
                }, { e ->
                    Toast.makeText(
                        context,
                        getString(R.string.plugin_install_failed, entry.name, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                })
        )
    }

    private fun showInstalledActions(
        plugin: PluginManager.InstalledPlugin,
        update: Pair<String, PluginRepository.Entry>?
    ) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (update != null) {
            actions += getString(R.string.plugin_update) to { install(update.first, update.second) }
        }
        val serviceId = PluginManager.getInstalled(requireContext())
            .firstOrNull { it.pluginId == plugin.pluginId }
            ?.let { installedPluginServiceId(plugin.pluginId) }
        if (serviceId != null && PluginManager.getLoadedManifest(serviceId)?.settings?.isNotEmpty() == true) {
            actions += getString(R.string.settings) to {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.settings_fragment_holder, PluginServiceSettingsFragment.newInstance(serviceId))
                    .addToBackStack(null)
                    .commit()
            }
        }
        actions += getString(R.string.plugin_uninstall) to {
            AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.plugin_uninstall_confirm, plugin.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.plugin_uninstall) { _, _ ->
                    PluginManager.uninstall(requireContext(), plugin.pluginId)
                    rebuild()
                    showRestartHint()
                }
                .show()
        }
        AlertDialog.Builder(requireContext())
            .setTitle(plugin.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun installedPluginServiceId(pluginId: String): Int? =
        org.schabi.newpipe.extractor.ServiceList.all()
            .firstOrNull { PluginManager.getLoadedManifest(it.serviceId)?.pluginId == pluginId }
            ?.serviceId

    private fun showRestartHint() {
        val view = view ?: return
        if (restartSnackbar?.isShown == true) {
            return
        }
        restartSnackbar = Snackbar.make(view, R.string.plugin_restart_required, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.plugin_restart) { NavigationHelper.restartApp(requireActivity()) }
            .also { it.show() }
    }
}
