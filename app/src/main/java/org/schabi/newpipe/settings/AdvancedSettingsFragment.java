package org.schabi.newpipe.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import androidx.preference.Preference;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ServiceHelper;

public class AdvancedSettingsFragment extends BasePreferenceFragment {

    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();
        Preference enableCompatibilityMode = findPreference(getString(R.string.enable_compatibility_mode_key));
        enableCompatibilityMode.setOnPreferenceChangeListener((preference, newValue) -> {
            // Return true first to allow the value to be saved
            new Handler().postDelayed(() -> {
                // Now SharedPreferences will have the new value
                ServiceHelper.initServices(getContext());
            }, 100); // 100ms delay should be enough, you can adjust if needed
            return true;
        });
    }
}