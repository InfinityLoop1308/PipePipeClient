package org.schabi.newpipe.settings;

import android.content.SharedPreferences;
import android.os.Bundle;

public class AdvancedSettingsFragment extends BasePreferenceFragment {

    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();
    }
}