package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.util.*;

public final class StreamDataLimit {
    private StreamDataLimit() { }

    public static boolean isLimitingDataUsage(final Context context) {
        return getResolutionLimit(context) != null;
    }

    /**
     * The maximum resolution allowed.
     *
     * @param context App context
     * @return maximum resolution allowed or null if there is no maximum
     */
    @Nullable
    public static String getResolutionLimit(@NonNull final Context context) {
        String resolutionLimit = null;
        if (isMeteredNetwork(context)) {
            final SharedPreferences preferences
                    = PreferenceManager.getDefaultSharedPreferences(context);
            final String defValue = context.getString(R.string.limit_data_usage_none_key);
            final String value = preferences.getString(
                    context.getString(R.string.limit_mobile_data_usage_key), defValue);
            resolutionLimit = defValue.equals(value) ? null : value;
        }
        return resolutionLimit;
    }

    /**
     * The current network is metered (like mobile data)?
     *
     * @param context App context
     * @return {@code true} if connected to a metered network
     */
    public static boolean isMeteredNetwork(@NonNull final Context context) {
        final ConnectivityManager manager
                = ContextCompat.getSystemService(context, ConnectivityManager.class);
        if (manager == null || manager.getActiveNetworkInfo() == null) {
            return false;
        }

        return manager.isActiveNetworkMetered();
    }
}
