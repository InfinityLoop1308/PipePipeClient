package org.schabi.newpipe.settings

import android.content.SharedPreferences
import android.util.Log
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.SharpOutputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ZipHelper
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
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

                ZipHelper.addFileToZip(outZip, fileLocator.settings.path, "newpipe.settings")
            }
    }

    /** Exports only the given [SharedPreferences] (same serialized format as in the full backup). */
    @Throws(Exception::class)
    fun exportSettingsOnly(preferences: SharedPreferences, file: StoredFileHelper) {
        file.create()
        ObjectOutputStream(BufferedOutputStream(SharpOutputStream(file.stream))).use { output ->
            output.writeObject(HashMap(preferences.all))
            output.flush()
        }
    }

    /** Replaces all preferences by the ones read from [file]. Throws if the file is not valid. */
    @Throws(Exception::class)
    fun importSettingsOnly(preferences: SharedPreferences, file: StoredFileHelper) {
        SharpInputStream(file.stream).use { applyEntries(preferences, it) }
    }

    fun resetSettings(preferences: SharedPreferences) {
        preferences.edit().clear().commit()
    }

    fun deleteSettingsFile() {
        fileLocator.settings.delete()
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

    fun extractSettings(file: StoredFileHelper): Boolean {
        return ZipHelper.extractFileFromZip(file, fileLocator.settings.path, "newpipe.settings")
    }

    fun loadSharedPreferences(preferences: SharedPreferences) {
        try {
            FileInputStream(fileLocator.settings).use { applyEntries(preferences, it) }
        } catch (e: IOException) {
            Log.e(TAG, "Unable to loadSharedPreferences", e)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Unable to loadSharedPreferences", e)
        }
    }

    @Throws(IOException::class, ClassNotFoundException::class)
    private fun applyEntries(preferences: SharedPreferences, stream: InputStream) {
        ObjectInputStream(stream).use { input ->
            @Suppress("UNCHECKED_CAST")
            val entries = input.readObject() as Map<String, *>
            val preferenceEditor = preferences.edit()
            preferenceEditor.clear()
            for ((key, value) in entries) {
                when (value) {
                    is Boolean -> preferenceEditor.putBoolean(key, value)
                    is Float -> preferenceEditor.putFloat(key, value)
                    is Int -> preferenceEditor.putInt(key, value)
                    is Long -> preferenceEditor.putLong(key, value)
                    is String -> preferenceEditor.putString(key, value)
                    // There are currently only Sets with type String possible
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        preferenceEditor.putStringSet(key, value as Set<String>?)
                    }
                }
            }
            preferenceEditor.commit()
        }
    }
}
