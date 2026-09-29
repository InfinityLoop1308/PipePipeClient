package org.schabi.newpipe.settings;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.dearrow.DeArrowCache;
import org.schabi.newpipe.util.dearrow.DeArrowExclusions;
import org.schabi.newpipe.util.dearrow.DeArrowFrameCache;

import java.util.List;

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

        final Preference exclusionsPreference =
                findPreference(getString(R.string.dearrow_exclusions_key));
        if (exclusionsPreference != null) {
            updateExclusionsSummary(exclusionsPreference);
            exclusionsPreference.setOnPreferenceClickListener(p -> {
                showExclusionsDialog(p);
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

    /**
     * Shows what is currently excluded, and lets the user take things off the list.
     *
     * <p>A multi-choice dialog rather than its own preference screen: the list is short by
     * nature — an exclusion is something the user added one at a time, deliberately — and the
     * only operation it needs is "undo one of these". Channels are listed first and prefixed,
     * because a channel exclusion is the one with the wide blast radius and is what someone
     * opening this screen is usually looking for.</p>
     *
     * @param preference the row that was clicked, so its summary can be refreshed afterwards
     */
    private void showExclusionsDialog(@NonNull final Preference preference) {
        final DeArrowExclusions exclusions = DeArrowExclusions.getInstance(requireContext());
        final List<DeArrowExclusions.Entry> channels = exclusions.listChannels();
        final List<DeArrowExclusions.Entry> videos = exclusions.listVideos();
        if (channels.isEmpty() && videos.isEmpty()) {
            Toast.makeText(getContext(), R.string.dearrow_exclusions_empty_toast,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final CharSequence[] labels = new CharSequence[channels.size() + videos.size()];
        for (int i = 0; i < channels.size(); i++) {
            labels[i] = getString(R.string.dearrow_channel_label, channels.get(i).label);
        }
        for (int i = 0; i < videos.size(); i++) {
            labels[channels.size() + i] = videos.get(i).label;
        }
        final boolean[] checked = new boolean[labels.length];

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dearrow_exclusions_title)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton(R.string.dearrow_exclusions_remove, (d, which) -> {
                    for (int i = 0; i < checked.length; i++) {
                        if (!checked[i]) {
                            continue;
                        }
                        if (i < channels.size()) {
                            exclusions.removeChannel(channels.get(i));
                        } else {
                            exclusions.removeVideo(videos.get(i - channels.size()));
                        }
                    }
                    updateExclusionsSummary(preference);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * @param preference the exclusions row, whose summary is the only place the counts appear
     */
    private void updateExclusionsSummary(@NonNull final Preference preference) {
        final DeArrowExclusions exclusions = DeArrowExclusions.getInstance(requireContext());
        if (exclusions.isEmpty()) {
            preference.setSummary(R.string.dearrow_exclusions_summary_empty);
        } else {
            preference.setSummary(getString(R.string.dearrow_exclusions_summary,
                    exclusions.listChannels().size(), exclusions.listVideos().size()));
        }
    }
}
