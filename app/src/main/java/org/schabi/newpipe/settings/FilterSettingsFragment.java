package org.schabi.newpipe.settings;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ServiceHelper;

public class FilterSettingsFragment extends BasePreferenceFragment {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResourceRegistry();

        Preference filter_by_keyword = findPreference(getString(R.string.filter_by_keyword_key));
        Preference filter_by_channel = findPreference(getString(R.string.filter_by_channel_key));
        Preference filter_shorts = findPreference(getString(R.string.filter_shorts_key));
        Preference filter_paid_contents = findPreference(getString(R.string.filter_paid_contents_key));
        Preference filter_future_items = findPreference(getString(R.string.filter_future_items_key));
        Preference filter_type = findPreference(getString(R.string.filter_type_key));

        filter_by_keyword.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(getActivity(), KeywordFilterListActivity.class);
            startActivity(intent);
            return true;
        });

        filter_by_channel.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(getActivity(), ChannelFilterListActivity.class);
            startActivity(intent);
            return true;
        });

        filter_shorts.setOnPreferenceChangeListener((preference, newValue) -> {
            new Handler().postDelayed(() -> {
                ServiceHelper.initServices(getContext());
            }, 100);
            return true;
        });

        filter_paid_contents.setOnPreferenceChangeListener((preference, newValue) -> {
            new Handler().postDelayed(() -> {
                ServiceHelper.initServices(getContext());
            }, 100);
            return true;
        });

        filter_future_items.setOnPreferenceChangeListener((preference, newValue) -> {
            new Handler().postDelayed(() -> {
                ServiceHelper.initServices(getContext());
            }, 100);
            return true;
        });

        filter_type.setOnPreferenceChangeListener((preference, newValue) -> {
            new Handler().postDelayed(() -> {
                ServiceHelper.initServices(getContext());
            }, 100);
            return true;
        });

        findPreference(getString(R.string.filter_by_channel_id_key))
                .setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(getActivity(),
                            ChannelIdFilterListActivity.class));
                    return true;
                });

        findPreference(getString(R.string.comment_filter_by_user_id_key))
                .setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(getActivity(),
                            CommentUserIdFilterListActivity.class));
                    return true;
                });

        findPreference(getString(R.string.comment_filter_by_keyword_key))
                .setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(getActivity(),
                            CommentKeywordFilterListActivity.class));
                    return true;
                });

        findPreference(getString(R.string.comment_filter_by_username_key))
                .setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(getActivity(),
                            CommentUsernameFilterListActivity.class));
                    return true;
                });

        final EditTextPreference commentMinLikes =
                findPreference(getString(R.string.comment_filter_min_likes_key));
        commentMinLikes.setOnBindEditTextListener(editText -> {
            editText.setInputType(InputType.TYPE_CLASS_NUMBER);
            editText.setSelection(editText.getText().length());
        });
        updateCommentMinLikesSummary(commentMinLikes,
                ServiceHelper.getCommentFilterMinLikes(requireContext()));
        commentMinLikes.setOnPreferenceChangeListener((preference, newValue) -> {
            final int value;
            try {
                value = Integer.parseInt(String.valueOf(newValue).trim());
            } catch (final NumberFormatException e) {
                return false;
            }
            if (value < 0) {
                return false;
            }
            updateCommentMinLikesSummary(commentMinLikes, value);
            new Handler().postDelayed(() -> {
                ServiceHelper.initServices(getContext());
            }, 100);
            return true;
        });
    }

    private void updateCommentMinLikesSummary(final EditTextPreference preference,
                                              final int value) {
        preference.setSummary(value > 0
                ? getString(R.string.comment_filter_min_likes_current, value)
                : getString(R.string.comment_filter_min_likes_summary));
    }
}
