package org.schabi.newpipe.plugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.plugin.PluginServiceEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.settings.SettingsActivity
import org.schabi.newpipe.util.NavigationHelper

/**
 * Guides users whose subscriptions or bookmarked playlists belong to a service that is not
 * available, because its plugin is disabled, not installed or failed to load. Happens after
 * upgrading from a version where the service was built in, or after importing a backup.
 */
object MissingPluginHelper {
    private const val TAG = "MissingPluginHelper"
    private const val STATE_FILE = "plugin_guide"
    private const val KEY_IGNORED = "ignored_service_ids"

    /** Services that used to be built in. Their ids are fixed, so no record is needed. */
    private val LEGACY = mapOf(
        1 to PluginServiceEntity(1, "org.pipepipe.plugin.soundcloud", "SoundCloud", PluginManager.BUILTIN_REPOSITORY),
        2 to PluginServiceEntity(2, "org.pipepipe.plugin.mediaccc", "media.ccc.de", PluginManager.BUILTIN_REPOSITORY),
        3 to PluginServiceEntity(3, "org.pipepipe.plugin.peertube", "PeerTube", PluginManager.BUILTIN_REPOSITORY),
        4 to PluginServiceEntity(4, "org.pipepipe.plugin.bandcamp", "Bandcamp", PluginManager.BUILTIN_REPOSITORY),
    )

    /** A service the user has data for. [plugin] is null when the owning plugin is unknown. */
    data class Missing(
        val serviceId: Int,
        val plugin: PluginServiceEntity?,
        val subscriptions: Int,
        val playlists: Int,
    )

    /** Remember which plugin owns each loaded service id. Call from a background thread. */
    @JvmStatic
    fun recordLoadedPlugins(context: Context) {
        val installed = PluginManager.getInstalled(context)
        val dao = NewPipeDatabase.getInstance(context).pluginServiceDAO()
        for (service in ServiceList.all()) {
            val manifest = PluginManager.getLoadedManifest(service.serviceId) ?: continue
            val repo = installed.firstOrNull { it.pluginId == manifest.pluginId }?.repositoryUrl ?: continue
            dao.upsert(PluginServiceEntity(service.serviceId, manifest.pluginId, manifest.name, repo))
        }
    }

    /** Call from a background thread. */
    private fun findMissing(context: Context): List<Missing> {
        val dao = NewPipeDatabase.getInstance(context).pluginServiceDAO()
        val known = dao.getAll().associateBy { it.serviceId }
        val subscriptions = dao.subscriptionCounts().associate { it.serviceId to it.count }
        val playlists = dao.remotePlaylistCounts().associate { it.serviceId to it.count }
        return (subscriptions.keys + playlists.keys)
            .filter { ServiceList.getById(it) == null }
            .sorted()
            .map { id ->
                Missing(id, LEGACY[id] ?: known[id], subscriptions[id] ?: 0, playlists[id] ?: 0)
            }
    }

    private fun ignored(context: Context): Set<String> =
        context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_IGNORED, emptySet())!!

    /**
     * Check on every start, not only after upgrades: importing a backup restarts the app.
     */
    @JvmStatic
    fun checkOnStart(activity: Activity) {
        val context = activity.applicationContext
        Single.fromCallable {
            recordLoadedPlugins(context)
            val ignored = ignored(context)
            findMissing(context).filter { it.serviceId.toString() !in ignored }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ missing ->
                if (missing.isNotEmpty() && !activity.isFinishing) {
                    showDialog(activity, missing, allowIgnore = true)
                }
            }, { Log.e(TAG, "Checking missing plugins failed", it) })
    }

    /**
     * Show the guide instead of opening an item of an unavailable service.
     *
     * @return true if the service is unavailable and the guide was shown
     */
    @JvmStatic
    fun interceptIfMissing(activity: Activity, serviceId: Int): Boolean {
        if (ServiceList.getById(serviceId) != null) {
            return false
        }
        val context = activity.applicationContext
        Single.fromCallable {
            val plugin = LEGACY[serviceId]
                ?: NewPipeDatabase.getInstance(context).pluginServiceDAO().getAll()
                    .firstOrNull { it.serviceId == serviceId }
            listOf(Missing(serviceId, plugin, 0, 0))
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ missing ->
                if (!activity.isFinishing) {
                    showDialog(activity, missing, allowIgnore = false)
                }
            }, { Log.e(TAG, "Looking up plugin failed", it) })
        return true
    }

    private fun describe(context: Context, missing: Missing): String {
        val name = missing.plugin?.name
            ?: context.getString(R.string.missing_plugin_unknown, missing.serviceId)
        val parts = mutableListOf<String>()
        if (missing.subscriptions > 0) {
            parts += context.resources.getQuantityString(
                R.plurals.missing_plugin_subscriptions, missing.subscriptions, missing.subscriptions
            )
        }
        if (missing.playlists > 0) {
            parts += context.resources.getQuantityString(
                R.plurals.missing_plugin_playlists, missing.playlists, missing.playlists
            )
        }
        return if (parts.isEmpty()) "• $name" else "• $name: ${parts.joinToString(", ")}"
    }

    private fun showDialog(activity: Activity, missing: List<Missing>, allowIgnore: Boolean) {
        val context = activity
        val installable = missing.filter { it.plugin != null && !isInstalled(context, it.plugin.pluginId) }
        val brokenOrUnknown = missing - installable.toSet()
        val message = buildString {
            append(context.getString(R.string.missing_plugin_message))
            append("\n\n")
            append(missing.joinToString("\n") { describe(context, it) })
            if (brokenOrUnknown.isNotEmpty()) {
                append("\n\n")
                append(context.getString(R.string.missing_plugin_manual))
            }
        }
        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.missing_plugin_title)
            .setMessage(message)
        if (installable.isNotEmpty()) {
            builder.setPositiveButton(R.string.install) { _, _ ->
                ensureEnabled(activity) { install(activity, installable.map { it.plugin!! }) }
            }
            builder.setNeutralButton(R.string.settings) { _, _ -> openPluginSettings(activity) }
        } else {
            builder.setPositiveButton(R.string.settings) { _, _ -> openPluginSettings(activity) }
        }
        if (allowIgnore) {
            builder.setNegativeButton(R.string.missing_plugin_ignore) { _, _ ->
                context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE).edit {
                    putStringSet(KEY_IGNORED, ignored(context) + missing.map { it.serviceId.toString() })
                }
            }
        } else {
            builder.setNegativeButton(R.string.cancel, null)
        }
        builder.show()
    }

    private fun isInstalled(context: Context, pluginId: String) =
        PluginManager.isEnabled(context) && PluginManager.getInstalled(context).any { it.pluginId == pluginId }

    /** Enabling plugins always needs the user to accept the warning (F-Droid inclusion policy). */
    private fun ensureEnabled(activity: Activity, then: () -> Unit) {
        if (PluginManager.isEnabled(activity)) {
            then()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.plugins_warning_title)
            .setMessage(R.string.plugins_warning_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.plugins_warning_accept) { _, _ ->
                PluginManager.setEnabled(activity, true)
                then()
            }
            .show()
    }

    private fun install(activity: Activity, plugins: List<PluginServiceEntity>) {
        val context = activity.applicationContext
        Toast.makeText(
            context,
            context.getString(R.string.plugin_installing, plugins.joinToString { it.name }),
            Toast.LENGTH_SHORT
        ).show()
        Single.fromCallable {
            val repositories = plugins.map { it.repositoryUrl }.distinct()
                .associateWith { runCatching { PluginManager.fetchRepository(it) } }
            plugins.map { plugin ->
                plugin to runCatching {
                    val repo = repositories.getValue(plugin.repositoryUrl).getOrThrow()
                    val entry = repo.plugins
                        .filter { it.pluginId == plugin.pluginId && it.isApiCompatible }
                        .maxByOrNull { it.versionCode }
                        ?: throw IllegalStateException(context.getString(R.string.missing_plugin_not_found))
                    PluginManager.install(context, plugin.repositoryUrl, entry)
                }
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ results ->
                val failed = results.filter { it.second.isFailure }
                if (activity.isFinishing) {
                    return@subscribe
                }
                val builder = AlertDialog.Builder(activity)
                if (failed.isEmpty()) {
                    builder.setMessage(R.string.plugin_restart_required)
                } else {
                    builder.setMessage(failed.joinToString("\n") { (plugin, result) ->
                        val e = result.exceptionOrNull()!!
                        context.getString(R.string.plugin_install_failed, plugin.name, e.message ?: e.javaClass.simpleName)
                    })
                    builder.setNeutralButton(R.string.settings) { _, _ -> openPluginSettings(activity) }
                }
                if (failed.size < results.size) {
                    builder.setPositiveButton(R.string.plugin_restart) { _, _ -> NavigationHelper.restartApp(activity) }
                    builder.setNegativeButton(R.string.later, null)
                } else {
                    builder.setPositiveButton(R.string.ok, null)
                }
                builder.show()
            }, { Log.e(TAG, "Installing plugins failed", it) })
    }

    private fun openPluginSettings(activity: Activity) {
        activity.startActivity(
            Intent(activity, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_PLUGINS, true)
        )
    }
}
