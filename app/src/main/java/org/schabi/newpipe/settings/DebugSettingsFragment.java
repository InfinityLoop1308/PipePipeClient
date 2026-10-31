package org.schabi.newpipe.settings;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.local.feed.notifications.NotificationWorker;
import org.schabi.newpipe.sync.DebugSync;
import org.schabi.newpipe.util.PicassoHelper;

import java.util.Optional;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class DebugSettingsFragment extends BasePreferenceFragment {
    private static final String DUMMY = "Dummy";
    private final CompositeDisposable disposables = new CompositeDisposable();

    @Override
    public void onDestroy() {
        disposables.clear();
        super.onDestroy();
    }

    /**
     * The server is saved only after /pipepipe/hello answers, so a typo can't silently disable
     * sync. Clearing the field turns sync off. Only the entry point is debug-only.
     */
    private void setupSyncServerPreference() {
        final EditTextPreference pref = (EditTextPreference) requirePreference(R.string.debug_sync_server_key);
        final String clientName = DebugSync.clientName(requireContext());
        pref.setSummaryProvider(p -> {
            final String value = ((EditTextPreference) p).getText();
            return (value == null || value.isEmpty() ? "" : value + "\n")
                    + "Server URL, http allowed. Leave empty to disable. Client name: " + clientName;
        });
        pref.setOnPreferenceChangeListener((p, newValue) -> {
            final String input = ((String) newValue).trim();
            if (input.isEmpty()) {
                pref.setText("");
                DebugSync.schedule(requireContext(), false);
                return false;
            }
            Toast.makeText(requireContext(),
                    "Checking " + input + "…", Toast.LENGTH_SHORT).show();
            disposables.add(Single.fromCallable(() -> {
                        final String url = DebugSync.normalize(input);
                        DebugSync.probe(url);
                        return url;
                    })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(url -> {
                        pref.setText(url);
                        DebugSync.schedule(requireContext(), true);
                        Toast.makeText(requireContext(), "Sync server set",
                                Toast.LENGTH_SHORT).show();
                    }, e -> Toast.makeText(requireContext(),
                            "No reply from " + input + "/pipepipe/hello: "
                                    + e.getMessage(),
                            Toast.LENGTH_LONG).show()));
            return false;
        });
    }

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();
        setupSyncServerPreference();

        final Preference allowHeapDumpingPreference =
                findPreference(getString(R.string.allow_heap_dumping_key));
        final Preference showMemoryLeaksPreference =
                findPreference(getString(R.string.show_memory_leaks_key));
        final Preference showImageIndicatorsPreference =
                findPreference(getString(R.string.show_image_indicators_key));
        final Preference checkNewStreamsPreference =
                findPreference(getString(R.string.check_new_streams_key));
        final Preference crashTheAppPreference =
                findPreference(getString(R.string.crash_the_app_key));
        final Preference showErrorSnackbarPreference =
                findPreference(getString(R.string.show_error_snackbar_key));
        final Preference createErrorNotificationPreference =
                findPreference(getString(R.string.create_error_notification_key));
        final Preference visitorDataPreference =
                findPreference(getString(R.string.youtube_visitor_data));

        assert allowHeapDumpingPreference != null;
        assert showMemoryLeaksPreference != null;
        assert showImageIndicatorsPreference != null;
        assert checkNewStreamsPreference != null;
        assert crashTheAppPreference != null;
        assert showErrorSnackbarPreference != null;
        assert createErrorNotificationPreference != null;
        assert visitorDataPreference != null;

        final Optional<DebugSettingsBVDLeakCanaryAPI> optBVLeakCanary = getBVDLeakCanary();

        allowHeapDumpingPreference.setEnabled(optBVLeakCanary.isPresent());
        showMemoryLeaksPreference.setEnabled(optBVLeakCanary.isPresent());

        if (optBVLeakCanary.isPresent()) {
            final DebugSettingsBVDLeakCanaryAPI pdLeakCanary = optBVLeakCanary.get();

            showMemoryLeaksPreference.setOnPreferenceClickListener(preference -> {
                startActivity(pdLeakCanary.getNewLeakDisplayActivityIntent());
                return true;
            });
        } else {
            allowHeapDumpingPreference.setSummary(R.string.leak_canary_not_available);
            showMemoryLeaksPreference.setSummary(R.string.leak_canary_not_available);
        }

        showImageIndicatorsPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            PicassoHelper.setIndicatorsEnabled((Boolean) newValue);
            return true;
        });

        checkNewStreamsPreference.setOnPreferenceClickListener(preference -> {
            NotificationWorker.runNow(preference.getContext());
            return true;
        });

        crashTheAppPreference.setOnPreferenceClickListener(preference -> {
            throw new RuntimeException(DUMMY);
        });

        showErrorSnackbarPreference.setOnPreferenceClickListener(preference -> {
            ErrorUtil.showUiErrorSnackbar(DebugSettingsFragment.this,
                    DUMMY, new RuntimeException(DUMMY));
            return true;
        });

        createErrorNotificationPreference.setOnPreferenceClickListener(preference -> {
            ErrorUtil.createNotification(requireContext(),
                    new ErrorInfo(new RuntimeException(DUMMY), UserAction.UI_ERROR, DUMMY));
            return true;
        });
    }

    /**
     * Tries to find the {@link DebugSettingsBVDLeakCanaryAPI#IMPL_CLASS} and loads it if available.
     * @return An {@link Optional} which is empty if the implementation class couldn't be loaded.
     */
    private Optional<DebugSettingsBVDLeakCanaryAPI> getBVDLeakCanary() {
        try {
            // Try to find the implementation of the LeakCanary API
            return Optional.of((DebugSettingsBVDLeakCanaryAPI)
                    Class.forName(DebugSettingsBVDLeakCanaryAPI.IMPL_CLASS)
                            .getDeclaredConstructor()
                            .newInstance());
        } catch (final Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Build variant dependent (BVD) leak canary API for this fragment.
     * Why is LeakCanary not used directly? Because it can't be assured
     */
    public interface DebugSettingsBVDLeakCanaryAPI {
        String IMPL_CLASS =
                "org.schabi.newpipe.settings.DebugSettingsBVDLeakCanary";

        Intent getNewLeakDisplayActivityIntent();
    }
}
