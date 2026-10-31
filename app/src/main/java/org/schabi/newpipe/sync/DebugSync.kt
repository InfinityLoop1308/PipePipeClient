package org.schabi.newpipe.sync

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.JsonWriter
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.sync.DbDiff.reader
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Debug database sync with the server in ~/code/sync-server (syncserver/pipepipe.py). Every run
 * POSTs `/pipepipe/sync/<name>` with the rows that changed since the last database the server sent
 * (the "base"), each as its base and current version, and gets the server's merged database
 * back. The local database is then patched row by row to match it, so Room stays open.
 *
 * Files in `files/debug_sync/`: `base-<version>.db` is the last applied server database; its
 * version is in the file name so swapping it is a single rename.
 */
object DebugSync {
    private const val TAG = "DebugSync"
    private const val WORK_PERIODIC = "debug_sync"
    private const val WORK_NOW = "debug_sync_now"
    private const val PREFS = "debug_sync"
    private const val KEY_NAME = "client_name"
    private const val KEY_SERVER = "server"
    private const val RO_FLAGS = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS

    /** Tables keyed by position in a list, by list column; sent as whole lists (server: LISTS). */
    private val LIST_TABLES = mapOf("playlist_stream_join" to "playlist_id")
    private val lock = Any()

    /** Stable per-install writer id; kept out of default prefs so backups don't clone it. */
    @JvmStatic
    fun clientName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_NAME, null)?.let { return it }
        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(24).ifEmpty { "device" }
        val name = "$model-" + UUID.randomUUID().toString().take(8)
        prefs.edit { putString(KEY_NAME, name) }
        return name
    }

    @JvmStatic
    @Throws(IOException::class)
    fun normalize(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.contains("://")) s = "http://$s"
        val uri = try { URI(s) } catch (e: Exception) { throw IOException("Invalid URL", e) }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
            throw IOException("Invalid URL")
        }
        return s
    }

    /** Throws unless `<url>/pipepipe/hello` answers 2xx. Blocking. */
    @JvmStatic
    @Throws(IOException::class)
    fun probe(url: String) {
        val code = RawHttp.request("GET", URI("$url/pipepipe/hello")).code
        if (code !in 200..299) throw IOException("HTTP $code")
    }

    private fun serverUrl(context: Context): String? = PreferenceManager
        .getDefaultSharedPreferences(context)
        .getString(context.getString(R.string.debug_sync_server_key), null)
        ?.takeIf { it.isNotBlank() }

    /** Starts or stops syncing to match the preference. Independent of the build type. */
    @JvmStatic
    fun schedule(context: Context, runNow: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (serverUrl(context) == null) {
            wm.cancelUniqueWork(WORK_PERIODIC)
            wm.cancelUniqueWork(WORK_NOW)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        )
        if (runNow) {
            wm.enqueueUniqueWork(
                WORK_NOW, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
            )
        }
    }

    class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = try {
            sync(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            Result.failure() // the periodic run retries in 15 minutes
        }
    }

    private class Base(val file: File, val version: Long)

    private fun findBase(dir: File): Base? = dir.listFiles()
        ?.mapNotNull { f -> Regex("base-(\\d+)\\.db").matchEntire(f.name)?.let { Base(f, it.groupValues[1].toLong()) } }
        ?.maxByOrNull { it.version }

    private fun openRo(file: File) = SQLiteDatabase.openDatabase(file.path, null, RO_FLAGS)

    @JvmStatic
    @Throws(Exception::class)
    fun sync(context: Context): Unit = synchronized(lock) {
        val url = normalize(serverUrl(context) ?: return)
        val name = clientName(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dir = File(context.filesDir, "debug_sync").apply { mkdirs() }
        if (prefs.getString(KEY_SERVER, null) != url) {
            // another server has another history; start over with a full upload
            dir.listFiles()?.forEach { it.delete() }
            prefs.edit { putString(KEY_SERVER, url) }
        }
        // the app's writes wait for the transaction below, so don't take it for a server that is down
        probe(url)
        val room: AppDatabase = NewPipeDatabase.getInstance(context)
        try {
            // One write transaction around diff, round trip and patch: the app cannot write in
            // between, so what we apply is merged from exactly the state we sent. App writes queue
            // until it ends; in WAL mode the app's reads still run.
            val pending = room.runInTransaction(
                Callable { exchange(room.openHelper.writableDatabase, dir, url, name) }
            ) ?: return
            val base = File(dir, "base-${pending.version}.db")
            if (!pending.file.renameTo(base)) throw IOException("rename failed")
            dir.listFiles()?.filter { it.name.startsWith("base-") && it != base }?.forEach { it.delete() }
        } finally {
            listOf("request.json.gz", "incoming.db.gz", "incoming.db").forEach { File(dir, it).delete() }
            dir.listFiles()?.filter { it.name.endsWith(".pending") }?.forEach { it.delete() }
        }
    }

    /**
     * Sends our changes and patches [db] to the server's merged database, inside the caller's
     * transaction. Returns the server database as a pending base, to be promoted once committed;
     * null if the server had nothing new for us.
     */
    private fun exchange(db: SupportSQLiteDatabase, dir: File, url: String, name: String): Base? {
        val live = db.reader()
        // A leftover pending base means we died between applying it and promoting it. Keep it
        // only if the local db still equals it, otherwise the old base is the right one.
        dir.listFiles()?.filter { it.name.endsWith(".pending") }?.forEach { p ->
            val same = try {
                openRo(p).use { pdb -> DbDiff.tables(live).all { DbDiff.collect(live, pdb.reader(), it).isEmpty } }
            } catch (e: Exception) {
                false
            }
            if (!same || !p.renameTo(File(dir, p.name.removeSuffix(".pending")))) p.delete()
        }
        val latest = findBase(dir)?.file
        dir.listFiles()?.filter { it.name.startsWith("base-") && it != latest }?.forEach { it.delete() }
        val base = findBase(dir)
        val request = File(dir, "request.json.gz")
        val incomingGz = File(dir, "incoming.db.gz")
        val incoming = File(dir, "incoming.db")

        val tables = DbDiff.tables(live)
        base?.let { openRo(it.file) }.use { baseDb ->
            writeRequest(request, live, baseDb?.reader(), tables, base?.version ?: 0)
        }
        val resp = incomingGz.outputStream().use { out ->
            RawHttp.request(
                "POST", URI("$url/pipepipe/sync/$name"),
                mapOf(
                    "Content-Type" to "application/json", "Content-Encoding" to "gzip",
                    "Accept-Encoding" to "gzip"
                ),
                request, out
            )
        }
        if (resp.code == 204) return null // server has nothing newer than our base and took our diff
        val gzipped = resp.headers["content-encoding"]?.contains("gzip", true) == true
        if (resp.code != 200) {
            val msg = if (gzipped) GZIPInputStream(incomingGz.inputStream()).use { it.readBytes() }.decodeToString()
            else incomingGz.readText()
            throw IOException("HTTP ${resp.code}: " + msg.take(300))
        }
        val newVersion = resp.headers["x-sync-version"]?.toLongOrNull()
            ?: throw IOException("Missing X-Sync-Version")
        (if (gzipped) GZIPInputStream(incomingGz.inputStream()) else incomingGz.inputStream()).use { i ->
            incoming.outputStream().use { i.copyTo(it) }
        }

        val ops = openRo(incoming).use { incDb ->
            val inc = incDb.reader()
            if (DbDiff.userVersion(inc) != DbDiff.userVersion(live)) {
                throw IOException("Server schema version ${DbDiff.userVersion(inc)} != local")
            }
            inc.query("PRAGMA quick_check").use {
                it.moveToFirst()
                if (it.getString(0) != "ok") throw IOException("Server db is corrupt")
            }
            for (t in tables) {
                val cols = DbDiff.tableInfo(inc, t.name)?.columns
                if (cols == null || !cols.containsAll(t.columns)) {
                    throw IOException("Server db lacks columns of ${t.name}")
                }
            }
            tables.map { DbDiff.collect(live, inc, it) }.filter { !it.isEmpty }
        }
        val pending = File(dir, "base-$newVersion.db.pending")
        if (!incoming.renameTo(pending)) throw IOException("rename failed")

        if (ops.isNotEmpty()) {
            try {
                DbDiff.apply(db, ops)
            } catch (e: SQLiteConstraintException) {
                // only the failed statement was undone, the transaction is still open
                Log.w(TAG, "row patch hit a constraint, rewriting all tables", e)
                openRo(pending).use { DbDiff.replaceAll(db, it.reader(), tables) }
            }
        }
        Log.i(TAG, "synced to server version $newVersion, ${ops.sumOf { it.upserts.size + it.deletes.size }} local changes")
        return Base(pending, newVersion)
    }

    private fun writeRequest(
        file: File, live: DbDiff.Reader, base: DbDiff.Reader?, tables: List<DbDiff.Table>, baseVersion: Long
    ) {
        JsonWriter(GZIPOutputStream(file.outputStream()).bufferedWriter()).use { w ->
            w.beginObject()
            w.name("base_version").value(baseVersion)
            w.name("user_version").value(DbDiff.userVersion(live).toLong())
            w.name("schema").beginArray()
            DbDiff.schema(live).forEach { (type, sql) -> w.beginObject().name("type").value(type).name("sql").value(sql).endObject() }
            w.endArray()
            w.name("tables").beginObject()
            for (t in tables) {
                w.name(t.name).beginObject()
                w.name("columns").beginArray(); t.columns.forEach { w.value(it) }; w.endArray()
                w.name("pk").beginArray(); t.pk.forEach { w.value(it) }; w.endArray()
                w.name("changes").beginArray()
                DbDiff.changes(base, live, t, LIST_TABLES[t.name]) { old, new ->
                    w.beginArray()
                    for (row in listOf(old, new)) {
                        if (row == null) w.nullValue()
                        else { w.beginArray(); row.forEach { DbDiff.writeValue(w, it) }; w.endArray() }
                    }
                    w.endArray()
                }
                w.endArray()
                w.endObject()
            }
            w.endObject()
            w.endObject()
        }
    }
}
