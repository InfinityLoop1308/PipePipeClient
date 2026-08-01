package org.schabi.newpipe.local.cache;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * A small rolling debug log for the "cache for offline viewing" feature, kept separate from
 * ACRA/logcat because those aren't easily accessible to a user reporting "I tapped Cache and
 * nothing happened" - this keeps a plain-text, in-memory + on-disk trail of every step of a
 * cache attempt (button tap, service start, stream selection, each download, success/failure)
 * that can be viewed and shared from {@link CacheLogActivity} without adb.
 */
public final class CacheLogger {

    private static final String TAG = "CacheDebug";
    private static final String LOG_FILE_NAME = "cache_debug_log.txt";
    private static final int MAX_LINES = 1000;
    private static final long MAX_FILE_BYTES = 512L * 1024L;

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private CacheLogger() {
    }

    public static void d(@NonNull final Context context, @NonNull final String tag,
                         @NonNull final String message) {
        Log.d(TAG, tag + ": " + message);
        append(context, "D", tag, message, null);
    }

    public static void w(@NonNull final Context context, @NonNull final String tag,
                         @NonNull final String message) {
        Log.w(TAG, tag + ": " + message);
        append(context, "W", tag, message, null);
    }

    public static void e(@NonNull final Context context, @NonNull final String tag,
                         @NonNull final String message, @Nullable final Throwable throwable) {
        Log.e(TAG, tag + ": " + message, throwable);
        append(context, "E", tag, message, throwable);
    }

    private static synchronized void append(@NonNull final Context context,
                                            @NonNull final String level,
                                            @NonNull final String tag,
                                            @NonNull final String message,
                                            @Nullable final Throwable throwable) {
        final String timestamp = TIME_FORMAT.format(new Date());
        final StringBuilder line = new StringBuilder()
                .append(timestamp).append(' ').append(level).append('/').append(tag)
                .append(": ").append(message);
        if (throwable != null) {
            final StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            line.append('\n').append(sw);
        }
        final String entry = line.toString();

        LINES.addLast(entry);
        while (LINES.size() > MAX_LINES) {
            LINES.removeFirst();
        }

        try {
            final File file = logFile(context);
            if (file.length() > MAX_FILE_BYTES) {
                // Rewrite from the in-memory ring buffer instead of appending forever.
                try (FileWriter writer = new FileWriter(file, false)) {
                    for (final String kept : LINES) {
                        writer.write(kept);
                        writer.write("\n");
                    }
                }
                return;
            }
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(entry);
                writer.write("\n");
            }
        } catch (final IOException e) {
            Log.e(TAG, "Failed to persist cache debug log entry", e);
        }
    }

    @NonNull
    public static synchronized String getLogText() {
        if (LINES.isEmpty()) {
            return "(no cache activity logged yet in this app session or on disk)";
        }
        return String.join("\n", LINES);
    }

    public static synchronized void clear(@NonNull final Context context) {
        LINES.clear();
        final File file = logFile(context);
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * Reloads the log from disk, which is the single source of truth: every line handed to
     * {@link #append} is written there as well as kept in memory.
     *
     * <p>This used to <em>merge</em> the file's contents in front of the in-memory lines, but
     * those same lines were already in the file - so every visit to the log screen (and every tap
     * of Refresh) duplicated everything logged in the current process, compounding each time.</p>
     */
    public static synchronized void loadFromDisk(@NonNull final Context context) {
        final File file = logFile(context);
        if (!file.exists()) {
            return;
        }
        try {
            final java.util.List<String> onDisk = java.nio.file.Files.readAllLines(file.toPath());
            LINES.clear();
            LINES.addAll(onDisk);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        } catch (final IOException e) {
            Log.e(TAG, "Failed to load persisted cache debug log", e);
        }
    }

    @NonNull
    private static File logFile(@NonNull final Context context) {
        final File base = context.getExternalFilesDir(null) != null
                ? context.getExternalFilesDir(null) : context.getFilesDir();
        return new File(base, LOG_FILE_NAME);
    }
}
