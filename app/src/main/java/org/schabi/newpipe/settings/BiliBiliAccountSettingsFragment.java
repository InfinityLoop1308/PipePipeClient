package org.schabi.newpipe.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;
import androidx.preference.Preference;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.views.BiliBiliLoginWebViewActivity;

import static android.app.Activity.RESULT_OK;

public class BiliBiliAccountSettingsFragment extends BasePreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int REQUEST_LOGIN = 1;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResource(R.xml.account_settings_bilibili);
        Preference login = findPreference(getString(R.string.login_key));
        Preference logout = findPreference(getString(R.string.logout_key));
        Preference override_switch = findPreference(getString(R.string.override_cookies_bilibili_key));
        Preference override_value = findPreference(getString(R.string.override_cookies_bilibili_value_key));
        override_value.setOnPreferenceClickListener(preference -> {
            ServiceHelper.initServices(this.getContext());
            return true;
        });
        override_switch.setOnPreferenceClickListener(preference -> {
            ServiceHelper.initServices(this.getContext());
            return true;
        });
        login.setOnPreferenceClickListener(preference -> {
            // Open a webview to login and then get cookies
            // and save them to the shared preferences
            Intent intent = new Intent(this.getContext(), BiliBiliLoginWebViewActivity.class);
            startActivityForResult(intent, REQUEST_LOGIN);
            return true;
        });
        logout.setOnPreferenceClickListener(preference -> {
            // Clear cookies
            defaultPreferences.edit().putString(getString(R.string.bilibili_cookies_key), "").apply();
            ServiceHelper.initServices(this.getContext());
            Toast.makeText(requireContext(), R.string.success, Toast.LENGTH_SHORT)
                    .show();
            login.setEnabled(true);
            logout.setEnabled(false);
            return true;
        });
        if (defaultPreferences.getString(getString(R.string.bilibili_cookies_key), "").equals("")) {
            logout.setEnabled(false);
        } else {
            login.setEnabled(false);
        }

        Preference override_cookies_bilibili_value = findPreference(getString(R.string.override_cookies_bilibili_value_key));
        override_cookies_bilibili_value.setEnabled(defaultPreferences.getBoolean(getString(R.string.override_cookies_bilibili_key), false));
    }
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if(key.equals(getString(R.string.override_cookies_bilibili_key)) || key.equals(getString(R.string.override_cookies_bilibili_value_key))) {
            ServiceHelper.initServices(this.getContext());
        }
    }
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_LOGIN && resultCode == RESULT_OK) {
            String cookies = data.getStringExtra("cookies");
            // save cookies to shared preferences
            defaultPreferences.edit().putString(getString(R.string.bilibili_cookies_key), cookies).apply();
            ServiceHelper.initServices(this.getContext());
            Toast.makeText(requireContext(), R.string.success, Toast.LENGTH_SHORT)
                    .show();
            Preference login = findPreference(getString(R.string.login_key));
            Preference logout = findPreference(getString(R.string.logout_key));
            login.setEnabled(false);
            logout.setEnabled(true);
        }
    }

}
