package org.schabi.newpipe.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ServiceHelper;

public class AdvancedSettingsFragment extends BasePreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener{

    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if(key.equals(getString(R.string.loading_timeout_key))) {
            ServiceHelper.initServices(this.getContext());
        }
    }
}