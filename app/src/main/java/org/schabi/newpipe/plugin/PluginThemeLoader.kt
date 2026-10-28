package org.schabi.newpipe.plugin

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.preference.PreferenceManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.plugin.PluginManifest
import org.schabi.newpipe.extractor.plugin.PluginTheme
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap

/**
 * Applies the optional colors a plugin declares in plugin.json as a real Android theme overlay.
 *
 * A theme cannot be given arbitrary colors at runtime. To avoid tinting every view, a small
 * resource table containing one style per plugin theme variant is generated and added to the
 * app's AssetManager. The style only sets theme attributes, so all existing `?attr/...`
 * references, selectors and layouts follow it.
 *
 * Generated styles are applied in [org.schabi.newpipe.util.ThemeHelper.setTheme] after the
 * normal light/dark/black theme has been selected.
 */
object PluginThemeLoader {
    private const val TAG = "PluginThemeLoader"

    /** Style type id reserved for generated plugin themes. */
    private const val STYLE_TYPE_ID = 0x7e
    private const val STYLE_PACKAGE_ID = 0x7f

    private data class ServiceStyles(val light: Int, val dark: Int, val black: Int)

    private val styles = HashMap<Int, ServiceStyles>()
    private var loaded = false

    /** Path of the generated resource APK, null while there is none. */
    @Volatile
    private var apkPath: String? = null

    /** Asset managers that already contain [apkPath]. Activities may own their own manager. */
    private val attached: MutableSet<AssetManager> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /**
     * Generate one resource APK for all installed plugins that declare colors and add it to the
     * process-wide AssetManager. Safe to call more than once.
     */
    @JvmStatic
    @Synchronized
    fun load(context: Context, manifests: Map<Int, PluginManifest>) {
        if (loaded) {
            return
        }
        val themed = manifests.filterValues { it.theme != null }
        if (themed.isEmpty()) {
            loaded = true
            return
        }

        val generated = HashMap<Int, ServiceStyles>()
        try {
            // Entries are numbered consecutively in a stable order, so the same set of plugins
            // always produces the same file.
            val resources = ArrayList<ThemeResourceApk.Style>()
            for ((serviceId, manifest) in themed.toSortedMap()) {
                val theme = manifest.theme ?: continue
                val base = resources.size
                resources.add(style(serviceId, "Light", theme.light))
                resources.add(style(serviceId, "Dark", theme.dark))
                resources.add(style(serviceId, "Black", theme.black))
                generated[serviceId] = ServiceStyles(
                    styleId(base), styleId(base + 1), styleId(base + 2)
                )
            }
            if (generated.isEmpty()) {
                loaded = true
                return
            }

            val file = generatedApk(context, resources)
            if (!attach(context.assets, file.absolutePath)) {
                Log.e(TAG, "Cannot add generated plugin theme resources")
                loaded = true
                return
            }
            apkPath = file.absolutePath
            styles.putAll(generated)
            loaded = true
            Log.i(TAG, "Loaded ${generated.size} plugin theme(s) from ${file.name}")
        } catch (e: Throwable) {
            // A cosmetic theme must never prevent the app or a plugin from working.
            Log.e(TAG, "Failed to load plugin themes", e)
            styles.clear()
            apkPath = null
            loaded = true
        }
    }

    /**
     * Apply the overlay of the selected service, if it has one. Must be called after
     * [android.content.Context.setTheme] and before the first view is inflated.
     */
    @JvmStatic
    fun apply(context: Context, serviceId: Int, light: Boolean, black: Boolean) {
        val serviceStyles = styles[serviceId] ?: return
        val path = apkPath ?: return
        if (PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.enable_eye_protection_key), false)
        ) {
            // Collector is an accessibility theme; plugin colors could break its contrast.
            return
        }
        val style = when {
            black -> serviceStyles.black
            light -> serviceStyles.light
            else -> serviceStyles.dark
        }
        // The activity's resources can use another AssetManager than the application's.
        if (!attach(context.assets, path)) {
            return
        }
        context.theme.applyStyle(style, true)
    }

    private fun styleId(entryId: Int): Int =
        (STYLE_PACKAGE_ID shl 24) or (STYLE_TYPE_ID shl 16) or (entryId and 0xffff)

    private fun style(
        serviceId: Int,
        suffix: String,
        variant: PluginTheme.Variant
    ): ThemeResourceApk.Style {
        val items = ArrayList<ThemeResourceApk.Item>()
        fun color(name: Int, color: Int) =
            items.add(ThemeResourceApk.Item(name, ThemeResourceApk.TYPE_COLOR_ARGB8, color))

        color(R.attr.colorPrimary, variant.primary)
        color(R.attr.colorPrimaryDark, variant.primary)
        color(R.attr.colorAccent, variant.accent)
        val name = "PluginTheme_${serviceId.toUInt().toString(16)}_$suffix"
        return ThemeResourceApk.Style(name, items)
    }

    /** Write the resource APK, named by its content so a changed plugin set never reuses it. */
    private fun generatedApk(context: Context, resources: List<ThemeResourceApk.Style>): File {
        val bytes = ThemeResourceApk.build(
            context.packageName, STYLE_PACKAGE_ID, STYLE_TYPE_ID, resources
        )
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8).joinToString("") { "%02x".format(it) }
        val dir = File(context.codeCacheDir, "plugin-themes").apply { mkdirs() }
        val file = File(dir, "themes-$hash.apk")
        dir.listFiles()?.forEach { if (it != file) it.delete() }
        if (!file.exists() || file.length() != bytes.size.toLong()) {
            val temp = File(dir, "themes-$hash.tmp")
            temp.delete()
            temp.writeBytes(bytes)
            temp.setReadOnly()
            if (!temp.renameTo(file)) {
                throw java.io.IOException("Cannot create ${file.name}")
            }
        }
        return file
    }

    private fun attach(assets: AssetManager, path: String): Boolean {
        if (attached.contains(assets)) {
            return true
        }
        if (!addAssetPath(assets, path)) {
            return false
        }
        attached.add(assets)
        return true
    }

    private fun addAssetPath(assets: AssetManager, path: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/content/res/AssetManager;")
            }
            val method = AssetManager::class.java.getDeclaredMethod(
                "addAssetPath", String::class.java
            )
            method.isAccessible = true
            val cookie = method.invoke(assets, path) as Int
            cookie != 0
        } catch (e: Throwable) {
            Log.e(TAG, "addAssetPath failed", e)
            false
        }
    }
}
