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
 * the feature be plain testable Java. Settings are read on every lookup rather than cached, so
 * toggling one takes effect immediately without an app restart.</p>
 */
public final class DeArrowSettings {

    private DeArrowSettings() {
    }

    /**
     * @param context any context
     * @return the user's current DeArrow settings; disabled unless they have opted in
     */
    @NonNull
    public static DeArrowConfig read(@NonNull final Context context) {
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
                prefs.getBoolean(context.getString(R.string.dearrow_auto_format_key), true),
                prefs.getString(context.getString(R.string.dearrow_api_url_key),
                        DeArrowConfig.DEFAULT_API_URL),
                prefs.getString(context.getString(R.string.dearrow_thumbnail_api_url_key),
                        DeArrowConfig.DEFAULT_THUMBNAIL_API_URL));
    }
}
