package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import org.schabi.newpipe.R;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Migrates old comma-separated filter preferences to the new StringSet format
 */
public class FilterPreferenceMigrator {
    private static final String MIGRATION_COMPLETED_KEY = "filter_migration_completed";

    public static void migrateIfNeeded(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Check if migration has already been performed
        if (prefs.getBoolean(MIGRATION_COMPLETED_KEY, false)) {
            return;
        }

        // Migrate keyword filters
        String keywordKey = context.getString(R.string.filter_by_keyword_key);
        String oldKeywords = prefs.getString(keywordKey, "");
        if (!oldKeywords.isEmpty()) {
            Set<String> keywordSet = Arrays.asList(oldKeywords.replace("，", ",").split(",")).stream().map(String::trim).collect(Collectors.toSet());
            prefs.edit().putStringSet(keywordKey + "_set", keywordSet).apply();
        }

        // Migrate channel filters
        String channelKey = context.getString(R.string.filter_by_channel_key);
        String oldChannels = prefs.getString(channelKey, "");
        if (!oldChannels.isEmpty()) {
            Set<String> channelSet = Arrays.asList(oldChannels.replace("，", ",").split(",")).stream().map(String::trim).collect(Collectors.toSet());
            prefs.edit().putStringSet(channelKey + "_set", channelSet).apply();
        }

        // Mark migration as completed
        prefs.edit().putBoolean(MIGRATION_COMPLETED_KEY, true).apply();
    }

}
