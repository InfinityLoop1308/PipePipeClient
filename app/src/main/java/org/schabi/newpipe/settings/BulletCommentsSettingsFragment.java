package org.schabi.newpipe.settings;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.SeekBarPreference;
import com.google.android.material.snackbar.Snackbar;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.PermissionHelper;

import java.util.LinkedList;
import java.util.List;

public class BulletCommentsSettingsFragment extends BasePreferenceFragment {

    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        listener = (sharedPreferences, s) -> {
            // add listeners to show the current float duration of regular bullet comments and top_bottom bullet comments
            if (s.equals(getString(R.string.top_bottom_bullet_comments_duration_key))){
                final int newSetting = sharedPreferences.getInt(s, 8);
                final SeekBarPreference topBottomBulletCommentsDuration = findPreference(s);
                assert topBottomBulletCommentsDuration != null;
                topBottomBulletCommentsDuration.setSummary(newSetting + " seconds");
            }
            else if (s.equals(getString(R.string.regular_bullet_comments_duration_key))){
                final int newSetting = sharedPreferences.getInt(s, 8);
                final SeekBarPreference regularBulletCommentsDuration = findPreference(s);
                assert regularBulletCommentsDuration != null;
                regularBulletCommentsDuration.setSummary(newSetting + " seconds");
            }
        };
        final SeekBarPreference regularBulletCommentsDuration = findPreference(getString(R.string.regular_bullet_comments_duration_key));
        assert regularBulletCommentsDuration != null;
        regularBulletCommentsDuration.setMin(5);
        final SeekBarPreference topBottomBulletCommentsDuration = findPreference(getString(R.string.top_bottom_bullet_comments_duration_key));
        assert topBottomBulletCommentsDuration != null;
        topBottomBulletCommentsDuration.setMin(5);
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(listener);

    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(listener);
    }
}