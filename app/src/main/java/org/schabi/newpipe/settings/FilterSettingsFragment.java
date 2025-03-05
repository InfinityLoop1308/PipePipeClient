package org.schabi.newpipe.settings;

import android.os.Bundle;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ServiceHelper;

public class FilterSettingsFragment extends BasePreferenceFragment {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResourceRegistry();
        Preference filter_by_keyword = findPreference(getString(R.string.filter_by_keyword_key));
        Preference filter_by_channel = findPreference(getString(R.string.filter_by_channel_key));
        Preference filter_shorts = findPreference(getString(R.string.filter_shorts_key));
        Preference filter_type = findPreference(getString(R.string.filter_type_key));

        filter_by_keyword.setOnPreferenceChangeListener((preference, newValue) -> {
            // Return true first to allow the value to be saved
            new Handler().postDelayed(() -> {
                // Now SharedPreferences will have the new value
                ServiceHelper.initServices(getContext());
            }, 100); // 100ms delay should be enough, you can adjust if needed
            return true;
        });
        filter_by_channel.setOnPreferenceChangeListener((preference, newValue) -> {
            new Handler().postDelayed(() -> {
                // Now SharedPreferences will have the new value
                ServiceHelper.initServices(getContext());
            }, 100); // 100ms delay should be enough, you can adjust if needed
            return true;
        });
        filter_shorts.setOnPreferenceChangeListener((preference, newValue) -> {
            new Handler().postDelayed(() -> {
                // Now SharedPreferences will have the new value
                ServiceHelper.initServices(getContext());
            }, 100); // 100ms delay should be enough, you can adjust if needed
            return true;
        });
        filter_type.setOnPreferenceChangeListener((preference, newValue) -> {
            new Handler().postDelayed(() -> {
                // Now SharedPreferences will have the new value
                ServiceHelper.initServices(getContext());
            }, 100); // 100ms delay should be enough, you can adjust if needed
            return true;
        });
    }
}
