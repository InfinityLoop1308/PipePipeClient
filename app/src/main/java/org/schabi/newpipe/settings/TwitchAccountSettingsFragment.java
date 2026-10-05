package org.schabi.newpipe.settings;

import android.content.Intent;
import android.os.Bundle;

import org.schabi.newpipe.R;
import org.schabi.newpipe.views.TwitchLoginWebViewActivity;

public class TwitchAccountSettingsFragment extends BaseAccountSettingsFragment {
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
    protected void performLogout() {}
}
