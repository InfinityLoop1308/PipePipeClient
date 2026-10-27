package org.schabi.newpipe.plugin

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import dalvik.system.DexClassLoader
import okhttp3.Request
import org.schabi.newpipe.App
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.plugin.PluginContext
import org.schabi.newpipe.extractor.plugin.PluginException
import org.schabi.newpipe.extractor.plugin.PluginLoader
import org.schabi.newpipe.extractor.plugin.PluginManifest
import org.schabi.newpipe.extractor.plugin.PluginPackage
import org.schabi.newpipe.extractor.plugin.PluginPreferences
import org.schabi.newpipe.extractor.plugin.PluginRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Installs, loads and removes external service plugins.
 *
 * Security model:
 * - Plugins are disabled until the user opts in ([isEnabled]), as required by F-Droid's
 *   inclusion policy for apps downloading executable code.
 * - Packages are verified against the sha256 and Ed25519 signature published by the
 *   repository. The public key is pinned on first install; updates signed with another key
 *   are rejected.
 * - Plugins run in-process with the app's permissions. Verification proves origin, not
 *   good behaviour, which is why only trusted repositories should be added.
 *
 * Loading happens once per process in [loadInstalledPlugins]; install, update and removal
 * take effect after an app restart because a class loader cannot be unloaded.
 */
object PluginManager {
    private const val TAG = "PluginManager"
    private const val STATE_FILE = "plugins"
    private const val KEY_INSTALLED = "installed"
    private const val KEY_ID_MAP = "id_map"
    private const val KEY_REPOSITORIES = "repositories"
    private const val MAX_PACKAGE_SIZE = 32L * 1024 * 1024

    /** Official repository, always listed while plugins are enabled and not removable. */
    const val BUILTIN_REPOSITORY =
        "https://github.com/InfinityLoop1308/PipePipe-builtin-plugins/releases/download/repo/index.json"

    /** Community repository, offered as a one-tap addition in the plugin settings. */
    const val AWESOME_REPOSITORY =
        "https://raw.githubusercontent.com/InfinityLoop1308/awesome-pipepipe-plugins/main/index.json"

    /** Ids of services that used to be built in, so existing data keeps working. */
    private val LEGACY_IDS = mapOf(
        "org.pipepipe.plugin.soundcloud" to 1,
        "org.pipepipe.plugin.mediaccc" to 2,
        "org.pipepipe.plugin.peertube" to 3,
        "org.pipepipe.plugin.bandcamp" to 4,
    )

    data class InstalledPlugin(
        val pluginId: String,
        val name: String,
        val versionCode: Int,
        val versionName: String,
        val publicKey: String,
        val repositoryUrl: String,
        val fileName: String,
        /** Empty for plugins installed before authors were declared. */
        val author: String = "",
    )

    /** Plugins loaded in this process, by service id. */
    private val loaded = ConcurrentHashMap<Int, PluginManifest>()

    /** Decoded icons of the loaded plugins, by service id. */
    private val icons = ConcurrentHashMap<Int, Bitmap>()

    /** Class loaders of the plugins loaded in this process. */
    private val classLoaders = CopyOnWriteArrayList<ClassLoader>()

    /** Load errors of this process, by plugin id. */
    private val loadErrors = ConcurrentHashMap<String, String>()

    private fun appContext(): Context = App.getApp()

    private fun state(context: Context): SharedPreferences =
        context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)

    private fun pluginDir(context: Context) = File(context.filesDir, "plugins")

    private fun codeCacheDir(context: Context, pluginId: String) =
        File(context.codeCacheDir, "plugins/$pluginId")

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.plugins_enabled_key), false)

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(context.getString(R.string.plugins_enabled_key), enabled)
        }
    }

    // ---- Persisted state ----

    @Synchronized
    fun getInstalled(context: Context = appContext()): List<InstalledPlugin> {
        val json = state(context).getString(KEY_INSTALLED, null) ?: return emptyList()
        return runCatching {
            val array = JsonParser.array().from(json)
            (0 until array.size).map { i ->
                val o = array.getObject(i)
                InstalledPlugin(
                    o.getString("pluginId"), o.getString("name"), o.getInt("versionCode"),
                    o.getString("versionName"), o.getString("publicKey"),
                    o.getString("repositoryUrl"), o.getString("fileName"),
                    o.getString("author", ""),
                )
            }
        }.getOrElse {
            Log.e(TAG, "Corrupted plugin state", it)
            emptyList()
        }
    }

    @Synchronized
    private fun saveInstalled(context: Context, plugins: List<InstalledPlugin>) {
        val array = JsonArray()
        plugins.forEach {
            array.add(
                JsonObject().apply {
                    put("pluginId", it.pluginId)
                    put("name", it.name)
                    put("versionCode", it.versionCode)
                    put("versionName", it.versionName)
                    put("publicKey", it.publicKey)
                    put("repositoryUrl", it.repositoryUrl)
                    put("fileName", it.fileName)
                    put("author", it.author)
                }
            )
        }
        // commit: the state must be on disk before the app restarts
        state(context).edit(commit = true) { putString(KEY_INSTALLED, JsonWriter.string(array)) }
    }

    @Synchronized
    private fun idMap(context: Context): MutableMap<String, Int> {
        val json = state(context).getString(KEY_ID_MAP, null) ?: return mutableMapOf()
        return runCatching {
            val o = JsonParser.`object`().from(json)
            o.keys.associateWith { o.getInt(it) }.toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    /**
     * Service ids are stable for the lifetime of the install: they are stored in the
     * database (subscriptions, history, playlists), so they are never reassigned, even
     * after uninstalling a plugin.
     */
    @Synchronized
    fun serviceIdFor(context: Context, manifest: PluginManifest): Int {
        val map = idMap(context)
        map[manifest.pluginId]?.let { return it }

        val legacy = LEGACY_IDS[manifest.pluginId]
        val id = if (legacy != null && legacy == manifest.legacyServiceId) {
            legacy
        } else {
            maxOf(ServiceList.FIRST_DYNAMIC_PLUGIN_ID, (map.values.maxOrNull() ?: 0) + 1)
        }
        map[manifest.pluginId] = id
        state(context).edit(commit = true) {
            putString(KEY_ID_MAP, JsonWriter.string(JsonObject(map.toMap())))
        }
        return id
    }

    /** The built-in repository followed by the ones added by the user. */
    @JvmStatic
    fun getRepositories(context: Context): List<String> =
        (listOf(BUILTIN_REPOSITORY) + getUserRepositories(context)).distinct()

    private fun getUserRepositories(context: Context): List<String> {
        val stored = state(context).getString(KEY_REPOSITORIES, null)
            ?: return emptyList()
        return runCatching {
            val array = JsonParser.array().from(stored)
            (0 until array.size).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    fun addRepository(context: Context, url: String) {
        val list = getUserRepositories(context).toMutableList()
        if (url != BUILTIN_REPOSITORY && url !in list) {
            list.add(url)
            state(context).edit { putString(KEY_REPOSITORIES, JsonWriter.string(JsonArray(list))) }
        }
    }

    @JvmStatic
    fun removeRepository(context: Context, url: String) {
        if (url == BUILTIN_REPOSITORY) {
            return
        }
        val list = getUserRepositories(context).filter { it != url }
        state(context).edit { putString(KEY_REPOSITORIES, JsonWriter.string(JsonArray(list))) }
    }

    // ---- Loading ----

    /**
     * Register the services of all installed plugins. Called once from [App.onCreate],
     * before anything reads the service list. Never throws: a broken plugin is skipped.
     */
    @JvmStatic
    fun loadInstalledPlugins(context: Context) {
        if (!isEnabled(context)) {
            return
        }
        migrateLegacySettings(context)
        for (plugin in getInstalled(context)) {
            try {
                load(context, plugin)
            } catch (e: Throwable) {
                // plugins are untrusted, also catch errors such as LinkageError
                Log.e(TAG, "Failed to load plugin ${plugin.pluginId}", e)
                loadErrors[plugin.pluginId] = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun load(context: Context, plugin: InstalledPlugin) {
        val file = File(pluginDir(context), plugin.fileName)
        if (!file.isFile) {
            throw PluginException("Package file missing")
        }
        val manifest = PluginPackage.readManifest(file)
        if (manifest.pluginId != plugin.pluginId) {
            throw PluginException("Package does not match installed plugin")
        }
        val serviceId = serviceIdFor(context, manifest)
        val optimizedDir = codeCacheDir(context, plugin.pluginId).apply { mkdirs() }
        val classLoader = DexClassLoader(
            file.absolutePath, optimizedDir.absolutePath, null, PluginManager::class.java.classLoader
        )
        val prefs = AndroidPluginPreferences(context, manifest.pluginId)
        val service = PluginLoader.createService(
            classLoader, manifest, serviceId,
            object : PluginContext {
                override fun getPluginId(): String = manifest.pluginId
                override fun getPreferences(): PluginPreferences = prefs
            }
        )
        ServiceList.register(service)
        loaded[serviceId] = manifest
        loadIcon(file, manifest, serviceId)
        classLoaders.add(classLoader)
        Log.i(TAG, "Loaded plugin ${manifest.pluginId} ${manifest.versionName} as $serviceId")
    }

    /** A broken icon must not keep the plugin from working, the default icon is used then. */
    private fun loadIcon(file: File, manifest: PluginManifest, serviceId: Int) {
        try {
            val bytes = PluginPackage.readIcon(file, manifest) ?: return
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val max = PluginManifest.ICON_MAX_DIMENSION
            if (bounds.outWidth !in 1..max || bounds.outHeight !in 1..max) {
                throw PluginException("Icon must be at most ${max}x$max")
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw PluginException("Icon cannot be decoded")
            icons[serviceId] = bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring icon of ${manifest.pluginId}", e)
        }
    }

    /**
     * @return the icon a plugin ships, or null if the service is not a plugin or has none
     */
    @JvmStatic
    fun getIcon(context: Context, serviceId: Int): Drawable? =
        // a new drawable per call: drawables are tinted by their users
        icons[serviceId]?.let { BitmapDrawable(context.resources, it) }

    @JvmStatic
    fun getLoadedManifest(serviceId: Int): PluginManifest? = loaded[serviceId]

    /**
     * The selected PeerTube instance used to be an app preference. Copy it into the
     * PeerTube plugin's settings once, so that users keep their instance.
     */
    private fun migrateLegacySettings(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val legacyKey = context.getString(R.string.peertube_selected_instance_key)
        val json = prefs.getString(legacyKey, null) ?: return
        runCatching {
            val url = JsonParser.`object`().from(json).getString("url")
            val pluginPrefs = AndroidPluginPreferences(context, "org.pipepipe.plugin.peertube")
            if (url != null && pluginPrefs.getString("instance_url", null) == null) {
                pluginPrefs.putString("instance_url", url)
            }
        }
        prefs.edit { remove(legacyKey) }
    }

    @JvmStatic
    fun isPluginService(service: StreamingService): Boolean = loaded.containsKey(service.serviceId)

    fun getLoadError(pluginId: String): String? = loadErrors[pluginId]

    fun isLoaded(pluginId: String): Boolean = loaded.values.any { it.pluginId == pluginId }

    /**
     * Resolve a class defined by a loaded plugin, for deserializing objects (filters, link
     * handlers, ...) that plugins put into saved state. Returns null if no plugin defines it.
     */
    @JvmStatic
    fun findPluginClass(name: String): Class<*>? {
        for (loader in classLoaders) {
            try {
                return Class.forName(name, false, loader)
            } catch (_: ClassNotFoundException) {
                // try the next plugin
            }
        }
        return null
    }

    /**
     * Resolves host classes first, then classes of loaded plugins. Set it on Bundles that may
     * hold plugin defined Serializable objects, so that they survive process death.
     */
    @JvmStatic
    val hostAndPluginsClassLoader: ClassLoader =
        object : ClassLoader(PluginManager::class.java.classLoader) {
            override fun findClass(name: String): Class<*> =
                findPluginClass(name) ?: throw ClassNotFoundException(name)
        }

    // ---- Network operations, call from a background thread ----

    @Throws(IOException::class, PluginException::class)
    fun fetchRepository(url: String): PluginRepository {
        val body = download(url, 2L * 1024 * 1024).toString(Charsets.UTF_8)
        return PluginRepository.parse(url, body)
    }

    /**
     * Download, verify and install a plugin package. Takes effect after a restart.
     */
    @Throws(IOException::class, PluginException::class)
    fun install(context: Context, repositoryUrl: String, entry: PluginRepository.Entry) {
        if (!isEnabled(context)) {
            throw PluginException("Plugins are disabled")
        }
        if (!entry.isApiCompatible) {
            throw PluginException("Plugin requires API ${entry.apiVersion}")
        }
        val existing = getInstalled(context).firstOrNull { it.pluginId == entry.pluginId }
        if (existing != null && existing.publicKey != entry.publicKey) {
            throw PluginException(context.getString(R.string.plugin_key_changed))
        }

        val dir = pluginDir(context).apply { mkdirs() }
        val tmp = File(dir, "${entry.pluginId}.tmp")
        val target = File(dir, "${entry.pluginId}-${entry.versionCode}${PluginPackage.FILE_EXTENSION}")
        val author: String
        try {
            tmp.writeBytes(download(entry.packageUrl, MAX_PACKAGE_SIZE))
            PluginPackage.verify(tmp, entry.sha256, entry.signature, entry.publicKey)
            val manifest = PluginPackage.readManifest(tmp)
            if (manifest.pluginId != entry.pluginId || manifest.versionCode != entry.versionCode ||
                manifest.name != entry.name
            ) {
                throw PluginException("Package does not match repository entry")
            }
            if (entry.author != null && manifest.author != entry.author) {
                throw PluginException("Package does not match repository entry")
            }
            if (!manifest.isApiCompatible) {
                throw PluginException("Plugin requires API ${manifest.apiVersion}")
            }
            author = manifest.author
            if (!PluginPackage.hasDex(tmp)) {
                throw PluginException("Package has no Android code")
            }
            // a service id must be known before the first load
            serviceIdFor(context, manifest)
            target.delete()
            if (!tmp.renameTo(target)) {
                throw IOException("Cannot move plugin package")
            }
            // Android 14+ refuses to load writable dex files
            target.setWritable(false, false)
        } finally {
            tmp.delete()
        }

        val updated = getInstalled(context).filter { it.pluginId != entry.pluginId } +
            InstalledPlugin(
                entry.pluginId, entry.name, entry.versionCode, entry.versionName,
                entry.publicKey, repositoryUrl, target.name, author,
            )
        saveInstalled(context, updated)
        if (existing != null && existing.fileName != target.name) {
            File(dir, existing.fileName).delete()
        }
        codeCacheDir(context, entry.pluginId).deleteRecursively()
    }

    /**
     * Remove a plugin package. Its service id, settings and the user's data stay, so
     * reinstalling restores everything. Takes effect after a restart.
     */
    fun uninstall(context: Context, pluginId: String) {
        val installed = getInstalled(context)
        val plugin = installed.firstOrNull { it.pluginId == pluginId } ?: return
        saveInstalled(context, installed.filter { it.pluginId != pluginId })
        File(pluginDir(context), plugin.fileName).delete()
        codeCacheDir(context, pluginId).deleteRecursively()
    }

    @Throws(IOException::class)
    private fun download(url: String, maxBytes: Long): ByteArray {
        val request = Request.Builder().url(url)
            .header("User-Agent", DownloaderImpl.USER_AGENT)
            .build()
        DownloaderImpl.getInstance().client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            val body = response.body
            val length = body.contentLength()
            if (length > maxBytes) {
                throw IOException("Response too large")
            }
            val source = body.source()
            // request() returns true when at least maxBytes + 1 bytes are available
            if (source.request(maxBytes + 1)) {
                throw IOException("Response too large")
            }
            return source.readByteArray()
        }
    }
}
