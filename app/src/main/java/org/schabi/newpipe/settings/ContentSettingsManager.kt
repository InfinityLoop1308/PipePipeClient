package org.schabi.newpipe.settings

import android.content.SharedPreferences
import android.util.Log
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.SharpOutputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ZipHelper
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.util.zip.ZipOutputStream

class ContentSettingsManager(private val fileLocator: NewPipeFileLocator) {
    companion object {
        const val TAG = "ContentSetManager"
    }

    /**
     * Exports given [SharedPreferences] to the file in given outputPath.
     * It also creates the file.
     */
    @Throws(Exception::class)
    fun exportDatabase(preferences: SharedPreferences, file: StoredFileHelper) {
        file.create()
        ZipOutputStream(BufferedOutputStream(SharpOutputStream(file.stream)))
            .use { outZip ->
                ZipHelper.addFileToZip(outZip, fileLocator.db.path, "newpipe.db")

                try {
                    ObjectOutputStream(FileOutputStream(fileLocator.settings)).use { output ->
                        output.writeObject(preferences.all)
                        output.flush()
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Unable to exportDatabase", e)
                }

                // legacy format, kept so that older versions and NewPipe can still read it
                ZipHelper.addFileToZip(outZip, fileLocator.settings.path, "newpipe.settings")

                writePreferencesJson(preferences, FileOutputStream(fileLocator.settingsJson))
                ZipHelper.addFileToZip(outZip, fileLocator.settingsJson.path, "preferences.json")
            }
    }

    /** Exports only the given [SharedPreferences], as JSON (same as `preferences.json` in the full backup). */
    @Throws(Exception::class)
    fun exportSettingsOnly(preferences: SharedPreferences, file: StoredFileHelper) {
        file.create()
        writePreferencesJson(preferences, SharpOutputStream(file.stream))
    }

    /** Replaces all preferences by the ones read from the JSON [file]. Throws if the file is not valid. */
    @Throws(Exception::class)
    fun importSettingsOnly(preferences: SharedPreferences, file: StoredFileHelper) {
        SharpInputStream(file.stream).use { applyJson(preferences, it) }
    }

    fun resetSettings(preferences: SharedPreferences) {
        preferences.edit().clear().commit()
    }

    fun deleteSettingsFile() {
        fileLocator.settings.delete()
        fileLocator.settingsJson.delete()
    }

    /**
     * Tries to create database directory if it does not exist.
     *
     * @return Whether the directory exists afterwards.
     */
    fun ensureDbDirectoryExists(): Boolean {
        return fileLocator.dbDir.exists() || fileLocator.dbDir.mkdir()
    }

    fun extractDb(file: StoredFileHelper): Boolean {
        val success = ZipHelper.extractFileFromZip(file, fileLocator.db.path, "newpipe.db")
        if (success) {
            fileLocator.dbJournal.delete()
            fileLocator.dbWal.delete()
            fileLocator.dbShm.delete()
        }

        return success
    }

    /** Extracts the settings from the backup, preferring the JSON ones over the legacy ones. */
    fun extractSettings(file: StoredFileHelper): Boolean {
        fileLocator.settings.delete()
        fileLocator.settingsJson.delete()
        val hasJson = ZipHelper.extractFileFromZip(
            file, fileLocator.settingsJson.path, "preferences.json"
        )
        return hasJson ||
            ZipHelper.extractFileFromZip(file, fileLocator.settings.path, "newpipe.settings")
    }

    fun loadSharedPreferences(preferences: SharedPreferences) {
        try {
            if (fileLocator.settingsJson.exists()) {
                FileInputStream(fileLocator.settingsJson).use { applyJson(preferences, it) }
            } else {
                FileInputStream(fileLocator.settings).use { applyLegacy(preferences, it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to loadSharedPreferences", e)
        }
    }

    private fun writePreferencesJson(preferences: SharedPreferences, out: OutputStream) {
        out.buffered().use {
            JsonWriter.indent("").on(it).`object`(preferences.all).done()
        }
    }

    private fun applyJson(preferences: SharedPreferences, stream: InputStream) {
        val json = JsonParser.`object`().from(stream)
        val editor = preferences.edit()
        editor.clear()
        for ((key, value) in json) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                is JsonArray -> editor.putStringSet(
                    key, value.mapNotNull { it as? String }.toSet()
                )
            }
        }
        if (!editor.commit()) {
            throw IOException("Unable to commit preferences")
        }
    }

    /** Only for the `newpipe.settings` entry of old backups. */
    @Throws(IOException::class, ClassNotFoundException::class)
    private fun applyLegacy(preferences: SharedPreferences, stream: InputStream) {
        PreferencesObjectInputStream(stream).use { input ->
            @Suppress("UNCHECKED_CAST")
            val entries = input.readObject() as Map<String, *>
            val editor = preferences.edit()
            editor.clear()
            for ((key, value) in entries) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    // There are currently only Sets with type String possible
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, value as Set<String>?)
                    }
                }
            }
            editor.commit()
        }
    }
}
