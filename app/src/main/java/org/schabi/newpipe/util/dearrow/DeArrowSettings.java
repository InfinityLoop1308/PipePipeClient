package org.schabi.newpipe.util.dearrow;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

/**
 * Reads the user's DeArrow preferences into a {@link DeArrowConfig}.
 *
 * <p>This is the only place that touches {@code SharedPreferences}, which is what lets the rest of
 * the feature be plain testable Java.</p>
 *
 * <p>The result is <b>cached</b>, because {@link #read} is called on every row bind on the main
 * thread and reading it fresh costs nine resource lookups and eight preference reads each time.
 * A preference listener drops the cache — along with the branding cache, whose entries have the
 * title and thumbnail switches baked into them at parse time — so toggling a setting still takes
 * effect on the next bind with no app restart.</p>
 */
public final class DeArrowSettings {

    /**
     * The last read settings, and the listener that invalidates them.
     *
     * <p><b>Cached because this is called on every row bind, on the main thread.</b> Reading
     * it fresh each time is nine {@code getString} resource lookups, eight
     * {@code SharedPreferences} reads and an allocation, inside {@code onBindViewHolder}
     * during a fling — paid on every row of every list whether or not anything changed.</p>
     *
     * <p>Kept correct rather than merely fast: a preference listener clears it, so a toggle
     * still takes effect on the next bind with no app restart, which is the behaviour the
     * per-read version existed to provide.</p>
     */
    private static volatile DeArrowConfig cached;
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;

    private DeArrowSettings() {
    }

    /**
     * @param context any context
     * @return the user's current DeArrow settings; disabled unless they have opted in
     */
    @NonNull
    public static DeArrowConfig read(@NonNull final Context context) {
        final DeArrowConfig snapshot = cached;
        if (snapshot != null) {
            return snapshot;
        }
        final DeArrowConfig fresh = readUncached(context);
        cached = fresh;
        return fresh;
    }

    /**
     * Registers the invalidation listener. Idempotent.
     *
     * @param context any context
     */
    private static synchronized void watch(@NonNull final Context context) {
        if (listener != null) {
            return;
        }
        listener = (p, key) -> {
            if (key != null && key.startsWith("dearrow_")) {
                cached = null;
                // The branding cache has to go too, not just the settings snapshot.
                //
                // DeArrowParser bakes the title/thumbnail switches into each DeArrowBranding
                // as it parses, so an entry cached while "replace titles" was on carries a
                // title regardless of what the setting says now. Without this, turning that
                // switch off leaves every already-seen video — up to five thousand of them —
                // still showing its DeArrow title until the app restarts, which reads as the
                // setting being ignored.
                DeArrowCache.getInstance().clear();
                DeArrowFrameCache.clearAll();
            }
        };
        PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .registerOnSharedPreferenceChangeListener(listener);
    }

    /**
     * @param context any context
     * @return the settings, read from disk
     */
    @NonNull
    private static DeArrowConfig readUncached(@NonNull final Context context) {
        watch(context);
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final boolean enabled = prefs.getBoolean(
                context.getString(R.string.dearrow_enable_key), false);
        if (!enabled) {
            return DeArrowConfig.disabled();
        }
        return new DeArrowConfig(
                true,
                prefs.getBoolean(context.getString(R.string.dearrow_replace_titles_key), true),
                prefs.getBoolean(context.getString(R.string.dearrow_replace_thumbnails_key), true),
                prefs.getBoolean(context.getString(R.string.dearrow_random_frame_key), true),
                // Defaults false, unlike every other switch here: a live frame costs a player
                // request and a media decode per row, where an upload's costs one small image
                // fetch. See DeArrowConfig#shouldUseLiveFrames.
                prefs.getBoolean(context.getString(R.string.dearrow_live_frame_key), false),
                // On by default: loading the uploader's thumbnail first and painting over it
                // is what produces the visible flip on a first pass through a list, and the
                // whole point of the feature is not to show the clickbait.
                prefs.getBoolean(
                        context.getString(R.string.dearrow_skip_original_thumbnail_key), true),
                prefs.getBoolean(context.getString(R.string.dearrow_auto_format_key), true),
                prefs.getString(context.getString(R.string.dearrow_api_url_key),
                        DeArrowConfig.DEFAULT_API_URL),
                prefs.getString(context.getString(R.string.dearrow_thumbnail_api_url_key),
                        DeArrowConfig.DEFAULT_THUMBNAIL_API_URL));
    }
}
