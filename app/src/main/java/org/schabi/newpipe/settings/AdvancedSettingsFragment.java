package org.schabi.newpipe.settings;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.ServiceHelper;

public class AdvancedSettingsFragment extends BasePreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener{

    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();
        initializeAndroidAutoPreference();
    }
    
    private void initializeAndroidAutoPreference() {
        final SwitchPreferenceCompat androidAutoPref = findPreference(getString(R.string.disable_android_auto_key));
        if (androidAutoPref != null) {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            final String key = getString(R.string.disable_android_auto_key);
            final String initKey = key + "_initialized";

            if (!prefs.contains(initKey)) {
                final boolean defaultValue = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU;
                prefs.edit()
                    .putBoolean(key, defaultValue)
                    .putBoolean(initKey, true)
                    .apply();
                androidAutoPref.setChecked(defaultValue);
            }
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if(key.equals(getString(R.string.loading_timeout_key))) {
            ServiceHelper.initServices(this.getContext());
        } else if(key.equals(getString(R.string.disable_android_auto_key))) {
            DeviceUtils.updateAndroidAutoComponentState(requireContext());
        }
    }
}