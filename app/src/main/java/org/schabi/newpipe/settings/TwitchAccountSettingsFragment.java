package org.schabi.newpipe.settings;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;

import org.schabi.newpipe.R;
import org.schabi.newpipe.views.TwitchLoginWebViewActivity;

// Webview token fetching is currently disabled since Twitch login refuses to work in a webview
public class TwitchAccountSettingsFragment extends BaseAccountSettingsFragment {

    private Preference tokenPreference;
    private Preference useTokenKioskPreference;
    private Preference useTokenSearchPreference;
    private Preference useTokenCategoriesPreference;
    private Preference useTokenClipsPreference;
    private Preference useTokenVodsPreference;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        tokenPreference = findPreference(getString(R.string.twitch_cookies_key));
        useTokenKioskPreference = findPreference(getString(R.string.twitch_use_token_kiosk));
        useTokenSearchPreference = findPreference(getString(R.string.twitch_use_token_search));
        useTokenCategoriesPreference = findPreference(getString(R.string.twitch_use_token_categories));
        useTokenClipsPreference = findPreference(getString(R.string.twitch_use_token_clips));
        useTokenVodsPreference = findPreference(getString(R.string.twitch_use_token_vods));

        if(defaultPreferences.getString(getString(R.string.twitch_cookies_key), null) == null)
        {
            logout.setEnabled(false);
            disableAllSettings();
        }
        else
        {
            tokenPreference.setEnabled(false);
            enableAllSettings();
        }

        logout.setOnPreferenceClickListener(_ -> {
            performLogout();
            disableAllSettings();
            logout.setEnabled(false);
            tokenPreference.setEnabled(true);
            Toast.makeText(getContext(), "Token deleted", Toast.LENGTH_SHORT).show();
            return true;
        });

        tokenPreference.setOnPreferenceChangeListener((_, value) -> {
            if(value == null)
                return false;
            defaultPreferences
                    .edit()
                    .putString(getString(R.string.twitch_cookies_key), (String) value)
                    .apply();
            enableAllSettings();
            logout.setEnabled(true);
            tokenPreference.setEnabled(false);
            return true;
        });
    }

    private void disableAllSettings() {
        useTokenKioskPreference.setEnabled(false);
        useTokenSearchPreference.setEnabled(false);
        useTokenCategoriesPreference.setEnabled(false);
        useTokenClipsPreference.setEnabled(false);
        useTokenVodsPreference.setEnabled(false);
    }

    private void enableAllSettings() {
        useTokenKioskPreference.setEnabled(true);
        useTokenSearchPreference.setEnabled(true);
        useTokenCategoriesPreference.setEnabled(true);
        useTokenClipsPreference.setEnabled(true);
        useTokenVodsPreference.setEnabled(true);
    }

    @Override
    protected int getPreferenceResource() {
        return R.xml.account_settings_twitch;
    }

    @Override
    protected Class<?> getLoginActivityClass() {
        return TwitchLoginWebViewActivity.class;
    }

    @Override
    protected String getCookiesKey() {
        return getString(R.string.twitch_cookies_key);
    }

    @Override
    protected String getOverrideSwitchKey() {
        return getString(R.string.override_cookies_twitch_key);
    }

    @Override
    protected String getOverrideValueKey() {
        return getString(R.string.override_cookies_twitch_value_key);
    }

    @Override
    protected boolean shouldCheckOverrideKeys() {
        return false;
    }

    @Override
    protected void handleLoginResult(Intent data) {
        final var cookie = data.getStringExtra("cookie");
        defaultPreferences.edit()
                .putString(cookie, getCookiesKey())
                .apply();
    }

    @Override
    protected void performLogout() {
        defaultPreferences.edit()
                .remove(getString(R.string.twitch_cookies_key))
                .apply();
    }
}
