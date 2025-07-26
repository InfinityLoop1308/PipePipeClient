package org.schabi.newpipe.settings;

import android.os.Bundle;
import android.webkit.CookieManager;
import android.widget.Toast;
import androidx.preference.Preference;
import org.schabi.newpipe.R;

public class AccountSettingsFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResource(R.xml.account_settings);

        final Preference clearWebViewCookies = findPreference("clear_webview_cookies");
        if (clearWebViewCookies != null) {
            clearWebViewCookies.setOnPreferenceClickListener(preference -> {
                clearWebViewCookies();
                return true;
            });
        }
    }

    private void clearWebViewCookies() {
        final CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(success -> {
            if (getContext() != null) {
                final String message = success 
                    ? getString(R.string.webview_cookies_cleared)
                    : getString(R.string.webview_cookies_clear_failed);
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
        cookieManager.flush();
    }
}
