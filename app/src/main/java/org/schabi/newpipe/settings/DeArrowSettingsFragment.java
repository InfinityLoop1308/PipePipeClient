package org.schabi.newpipe.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.dearrow.DeArrowCache;
import org.schabi.newpipe.util.dearrow.DeArrowFrameCache;

/**
 * Settings for DeArrow, the crowdsourced replacement for clickbait titles and thumbnails.
 *
 * <p>Every switch on this screen depends on the master toggle, which is off by default: an
 * untouched install makes no DeArrow requests at all.</p>
 */
public class DeArrowSettingsFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        final Preference websitePreference =
                findPreference(getString(R.string.dearrow_home_page_key));
        if (websitePreference != null) {
            websitePreference.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(getString(R.string.dearrow_homepage_url))));
                return true;
            });
        }

        final Preference clearCachePreference =
                findPreference(getString(R.string.dearrow_clear_cache_key));
        if (clearCachePreference != null) {
            clearCachePreference.setOnPreferenceClickListener(p -> {
                // Both, not just the titles. The preference says it forgets every downloaded
                // title AND thumbnail, and clearing only the title map left the stale
                // thumbnail the user was trying to get rid of exactly where it was — while
                // also being the only way to hand back the memory the frames occupy.
                DeArrowCache.getInstance().clear();
                DeArrowFrameCache.clearAll();
                Toast.makeText(getContext(), R.string.dearrow_cache_cleared_toast,
                        Toast.LENGTH_SHORT).show();
                return true;
            });
        }
    }

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
