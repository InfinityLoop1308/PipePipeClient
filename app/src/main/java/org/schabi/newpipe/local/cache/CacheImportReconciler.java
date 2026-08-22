package org.schabi.newpipe.local.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.cache.dao.CachedStreamDAO;
import org.schabi.newpipe.database.cache.model.CachedStreamEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.InfoCache;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Keeps the {@code cached_streams} table honest across a settings/database import.
 *
 * <p>A backup only carries the database and the shared preferences - never the cached media
 * itself, which lives in app-private external storage ({@link CacheManager#getCacheRootDir}) and
 * is untouched by an import. So the imported table routinely claims videos are cached that this
 * device has no files for (a backup from another device, or from before the files were deleted),
 * and conversely this device may hold files for videos the imported table has never heard of.
 * Either way the "cached" badges, the Cached videos screen and the feed's cached-only filter lie,
 * and playing such an entry fails.</p>
 *
 * <p>The import therefore does two things. Before restarting the app it reads the freshly
 * extracted database file directly (see {@link #scanBackupForUncachedFlags}) so the user can be
 * asked what should happen to the entries whose media is missing; and after the restart
 * {@link #runPendingImportWork} reconciles the table against the directory - dropping rows with no
 * files, repairing rows whose files moved, deleting folders no row refers to - and then caches
 * again whatever the user asked for.</p>
 */
public final class CacheImportReconciler {

    private static final String TAG = "CacheImportReconciler";

    /** Set by the import, read once after the app restarts onto the imported database. */
    private static final String PREF_PENDING = "cache_import_reconcile_pending";
    /** {@link CacheManager#cacheKey} of every entry the user asked to cache again. */
    private static final String PREF_RECACHE = "cache_import_recache_keys";

    /** How long to let one re-cache run before giving up on it and starting the next. */
    private static final long RECACHE_TIMEOUT_MS = 45L * 60 * 1000;
    private static final long RECACHE_POLL_MS = 2000;

    private static volatile boolean workRunning;

    private CacheImportReconciler() {
        // no instance
    }

    /** A row in an imported backup that claims to be cached but has no media on this device. */
    public static final class MissingEntry {
        public final int serviceId;
        @NonNull public final String url;
        @Nullable public final String title;

        MissingEntry(final int serviceId, @NonNull final String url,
                     @Nullable final String title) {
            this.serviceId = serviceId;
            this.url = url;
            this.title = title;
        }
    }

    /**
     * Reads the just-extracted database file and returns the cache entries whose media files are
     * not on this device.
     *
     * <p>This runs before the app restarts, while Room is still open on the same path, so it
     * deliberately uses its own read-only connection and never writes: the imported file must
     * reach the restart exactly as the backup left it. Anything unexpected - no such table (a
     * backup from a build without the cache feature), an unreadable file - is reported as
     * "nothing to ask about"; {@link #runPendingImportWork} still reconciles afterwards.</p>
     */
    @NonNull
    public static List<MissingEntry> scanBackupForUncachedFlags(@NonNull final Context context,
                                                                @NonNull final File dbFile) {
        final List<MissingEntry> missing = new ArrayList<>();
        if (!dbFile.isFile()) {
            return missing;
        }

        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            if (!hasCachedStreamsTable(db)) {
                return missing;
            }
            try (Cursor cursor = db.rawQuery(
                    "SELECT * FROM " + CachedStreamEntity.CACHED_STREAM_TABLE, null)) {
                final int serviceIdColumn = cursor.getColumnIndex(CachedStreamEntity.SERVICE_ID);
                final int urlColumn = cursor.getColumnIndex(CachedStreamEntity.URL);
                final int streamIdColumn = cursor.getColumnIndex(CachedStreamEntity.STREAM_ID);
                final int titleColumn = cursor.getColumnIndex(CachedStreamEntity.TITLE);
                final int videoColumn = cursor.getColumnIndex(CachedStreamEntity.VIDEO_FILE_PATH);
                final int audioColumn = cursor.getColumnIndex(CachedStreamEntity.AUDIO_FILE_PATH);
                final int completeColumn = cursor.getColumnIndex(CachedStreamEntity.IS_COMPLETE);
                if (serviceIdColumn < 0 || urlColumn < 0 || streamIdColumn < 0) {
                    return missing;
                }

                while (cursor.moveToNext()) {
                    final int serviceId = cursor.getInt(serviceIdColumn);
                    final String url = cursor.getString(urlColumn);
                    final String streamId = cursor.getString(streamIdColumn);
                    if (url == null || streamId == null) {
                        continue;
                    }
                    final boolean complete = completeColumn < 0
                            || cursor.getInt(completeColumn) != 0;
                    final String videoPath =
                            videoColumn < 0 ? null : cursor.getString(videoColumn);
                    final String audioPath =
                            audioColumn < 0 ? null : cursor.getString(audioColumn);

                    if (!hasItsMedia(context, serviceId, streamId, videoPath, audioPath,
                            complete)) {
                        missing.add(new MissingEntry(serviceId, url,
                                titleColumn < 0 ? null : cursor.getString(titleColumn)));
                    }
                }
            }
        } catch (final Exception e) {
            Log.w(TAG, "could not read the cache table of the imported backup", e);
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (final Exception e) {
                    Log.w(TAG, "could not close the imported backup", e);
                }
            }
        }
        return missing;
    }

    private static boolean hasCachedStreamsTable(@NonNull final SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                new String[]{CachedStreamEntity.CACHED_STREAM_TABLE})) {
            return cursor.moveToFirst();
        }
    }

    /**
     * Records what should happen once the app has restarted onto the imported database. Must be
     * called after the imported preferences have been loaded, since loading them wipes every
     * existing preference.
     *
     * @param recache whether the entries in {@code missing} should be downloaded again; if not,
     *                the reconciliation after the restart simply drops their rows
     */
    public static void schedulePostImportWork(@NonNull final Context context,
                                              final boolean recache,
                                              @NonNull final List<MissingEntry> missing) {
        final Set<String> keys = new HashSet<>();
        if (recache) {
            for (final MissingEntry entry : missing) {
                keys.add(CacheManager.cacheKey(entry.serviceId, entry.url));
            }
        }
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(PREF_PENDING, true)
                .putStringSet(PREF_RECACHE, keys)
                .commit(); // the process is about to be killed and restarted
    }

    /**
     * Runs the reconciliation an import left pending, then caches again whatever the user asked
     * for. A no-op unless an import actually happened, so it is safe (and cheap) to call on every
     * start.
     */
    public static void runPendingImportWork(@NonNull final Context context) {
        final Context appContext = context.getApplicationContext();
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(appContext);
        if (!preferences.getBoolean(PREF_PENDING, false) || workRunning) {
            return;
        }
        workRunning = true;

        Schedulers.io().scheduleDirect(() -> {
            try {
                final Result result = reconcile(appContext);
                Log.i(TAG, "post-import reconciliation: " + result);
                // Only now: a crash before this point should leave the work to be redone.
                preferences.edit().putBoolean(PREF_PENDING, false).apply();

                final Set<String> keys = new HashSet<>(
                        preferences.getStringSet(PREF_RECACHE, Collections.emptySet()));
                if (keys.isEmpty()) {
                    if (result.rowsDropped > 0) {
                        toast(appContext, appContext.getString(
                                R.string.cache_import_cleared_toast, result.rowsDropped));
                    }
                } else {
                    toast(appContext, appContext.getString(
                            R.string.cache_import_recaching_toast, keys.size()));
                    recacheAll(appContext, preferences, new ArrayList<>(keys));
                }
            } catch (final Throwable t) {
                Log.e(TAG, "post-import cache reconciliation failed", t);
            } finally {
                workRunning = false;
            }
        });
    }

    /** What {@link #reconcile} had to change, for the log and the toast. */
    private static final class Result {
        private int rowsDropped;
        private int rowsRepaired;
        private int foldersDropped;

        @NonNull
        @Override
        public String toString() {
            return "dropped " + rowsDropped + " row(s), repaired " + rowsRepaired
                    + " row(s), deleted " + foldersDropped + " orphaned cache folder(s)";
        }
    }

    /**
     * Makes the cache table describe what is actually in the cache directory:
     *
     * <ul>
     *     <li>a row whose media is gone - or was never finished, since an import kills any mission
     *     that was in flight when the backup was taken - loses its row and any leftovers;</li>
     *     <li>a row whose media is present under a different path than the backup recorded (a
     *     backup from another device, or from the release build next to the debug one) is repaired
     *     rather than dropped;</li>
     *     <li>a folder in the cache directory that no surviving row points at is deleted: with its
     *     row gone there is no way to reach or play it, so it is only wasted space.</li>
     * </ul>
     */
    @NonNull
    private static Result reconcile(@NonNull final Context context) {
        final Result result = new Result();
        final CachedStreamDAO dao = NewPipeDatabase.getInstance(context).cachedStreamDAO();
        final List<CachedStreamEntity> entities = dao.getAll().blockingFirst(new ArrayList<>());

        final Set<String> liveFolders = new HashSet<>();
        for (final CachedStreamEntity entity : entities) {
            final File video = resolve(context, entity.getServiceId(), entity.getStreamId(),
                    entity.getVideoFilePath());
            final File audio = resolve(context, entity.getServiceId(), entity.getStreamId(),
                    entity.getAudioFilePath());
            final boolean playable = entity.isComplete()
                    && (isBlank(entity.getVideoFilePath()) || video != null)
                    && (isBlank(entity.getAudioFilePath()) || audio != null)
                    && (video != null || audio != null);

            if (!playable) {
                CacheManager.deleteFilesFor(entity);
                dao.delete(entity);
                InfoCache.getInstance().removeInfo(entity.getServiceId(), entity.getUrl(),
                        InfoItem.InfoType.STREAM);
                CacheManager.cacheChanges.onNext(new CacheManager.CacheChangeEvent(
                        entity.getServiceId(), entity.getUrl(), false));
                result.rowsDropped++;
                continue;
            }

            boolean repaired = false;
            if (video != null && !video.getAbsolutePath().equals(entity.getVideoFilePath())) {
                entity.setVideoFilePath(video.getAbsolutePath());
                repaired = true;
            }
            if (audio != null && !audio.getAbsolutePath().equals(entity.getAudioFilePath())) {
                entity.setAudioFilePath(audio.getAbsolutePath());
                repaired = true;
            }
            if (repaired) {
                dao.insert(entity); // REPLACE on the same uid, i.e. an update
                result.rowsRepaired++;
            }

            rememberFolder(liveFolders, video);
            rememberFolder(liveFolders, audio);
        }

        final File[] children = CacheManager.getCacheRootDir(context).listFiles();
        if (children != null) {
            for (final File child : children) {
                if (liveFolders.contains(child.getAbsolutePath())) {
                    continue;
                }
                final boolean wasFolder = child.isDirectory();
                if (deleteRecursively(child) && wasFolder) {
                    result.foldersDropped++;
                }
            }
        }

        if (result.rowsDropped > 0 || result.rowsRepaired > 0) {
            CacheManager.cacheDataChanged.onNext(Boolean.TRUE);
        }
        return result;
    }

    private static void rememberFolder(@NonNull final Set<String> folders,
                                       @Nullable final File file) {
        if (file == null) {
            return;
        }
        final File parent = file.getParentFile();
        if (parent != null) {
            folders.add(parent.getAbsolutePath());
        }
    }

    /**
     * Whether the media a cache row describes is really on this device, checked the same way for
     * the imported file and for the live table.
     */
    private static boolean hasItsMedia(@NonNull final Context context,
                                       final int serviceId,
                                       @NonNull final String streamId,
                                       @Nullable final String videoPath,
                                       @Nullable final String audioPath,
                                       final boolean complete) {
        if (!complete) {
            // A download that was still running when the backup was taken. Nothing is running now
            // - the import restarts the process - so it is a half-written file, not a cache entry.
            return false;
        }
        boolean any = false;
        if (!isBlank(videoPath)) {
            if (resolve(context, serviceId, streamId, videoPath) == null) {
                return false;
            }
            any = true;
        }
        if (!isBlank(audioPath)) {
            if (resolve(context, serviceId, streamId, audioPath) == null) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * The file a cache row's path really refers to on this device, or null if there is none.
     *
     * <p>The stored path is absolute and contains the package name, so a backup taken on another
     * device - or from the release build next to the debug one - points somewhere that does not
     * exist here even when the media itself was copied over. The same file name under this
     * device's own cache folder for that stream is therefore accepted as the same file, and the
     * row is repaired to match.</p>
     */
    @Nullable
    private static File resolve(@NonNull final Context context,
                                final int serviceId,
                                @Nullable final String streamId,
                                @Nullable final String path) {
        if (isBlank(path)) {
            return null;
        }
        final File stored = new File(path);
        if (stored.isFile() && stored.length() > 0) {
            return stored;
        }
        if (isBlank(streamId)) {
            return null;
        }
        final File local = new File(
                CacheManager.cacheDirPathFor(context, serviceId, streamId), stored.getName());
        return local.isFile() && local.length() > 0 ? local : null;
    }

    private static boolean deleteRecursively(@NonNull final File file) {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) {
                for (final File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        final boolean deleted = file.delete();
        if (!deleted) {
            Log.w(TAG, "could not delete " + file.getAbsolutePath());
        }
        return deleted;
    }

    /**
     * Caches the entries the user asked for again, one at a time: each needs its page extracted
     * from the network first, and running a dozen missions at once would only make them fight
     * over the connection.
     */
    private static void recacheAll(@NonNull final Context context,
                                   @NonNull final SharedPreferences preferences,
                                   @NonNull final List<String> keys) {
        for (final String key : keys) {
            // Drop it from the pending set before starting, so a stream that manages to kill the
            // process is not retried forever on every launch.
            final Set<String> remaining = new HashSet<>(
                    preferences.getStringSet(PREF_RECACHE, Collections.emptySet()));
            remaining.remove(key);
            preferences.edit().putStringSet(PREF_RECACHE, remaining).commit();

            final int separator = key.indexOf(' ');
            if (separator <= 0) {
                continue;
            }
            final int serviceId;
            try {
                serviceId = Integer.parseInt(key.substring(0, separator));
            } catch (final NumberFormatException e) {
                Log.w(TAG, "not a cache key: " + key);
                continue;
            }
            final String url = key.substring(separator + 1);

            try {
                final StreamInfo info =
                        ExtractorHelper.getStreamInfo(serviceId, url, true).blockingGet();
                if (!CacheManager.startCaching(context, info)) {
                    Log.w(TAG, "nothing cacheable for " + url);
                    continue;
                }
            } catch (final Throwable t) {
                Log.e(TAG, "could not cache " + url + " again after the import", t);
                continue;
            }
            awaitCompletion(serviceId, url);
        }
    }

    /**
     * Blocks until the mission for this stream is over - finished or failed, both of which clear
     * its progress marker - so the next one starts on a free connection.
     */
    private static void awaitCompletion(final int serviceId, @NonNull final String url) {
        final long deadline = System.currentTimeMillis() + RECACHE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (CacheManager.getProgressBlocking(serviceId, url)
                    == CacheManager.PROGRESS_FAILED) {
                return;
            }
            try {
                Thread.sleep(RECACHE_POLL_MS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.w(TAG, "gave up waiting for the re-cache of " + url);
    }

    private static void toast(@NonNull final Context context, @NonNull final String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    private static boolean isBlank(@Nullable final String value) {
        return value == null || value.trim().isEmpty();
    }
}
