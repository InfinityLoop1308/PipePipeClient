package org.schabi.newpipe.settings;

import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.PreferenceScreen;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceInstances;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.util.Constants;

public class ContentSettingsFragment extends BasePreferenceFragment {
    private Localization initialSelectedLocalization;
    private ContentCountry initialSelectedContentCountry;
    private String initialLanguage;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();
        addInstancePreferences();

        initialSelectedLocalization = org.schabi.newpipe.util.Localization
                .getPreferredLocalization(requireContext());
        initialSelectedContentCountry = org.schabi.newpipe.util.Localization
                .getPreferredContentCountry(requireContext());
        initialLanguage = defaultPreferences.getString(getString(R.string.app_language_key), "en");
    }

    /**
     * Add an entry for every service whose instances the user can manage, which is usually a
     * plugin. Services without the {@link ServiceInstances} capability do not get an entry,
     * so nothing is shown while no such plugin is installed and enabled.
     */
    private void addInstancePreferences() {
        for (final StreamingService service : NewPipe.getServices()) {
            if (!(service instanceof ServiceInstances)) {
                continue;
            }
            final PreferenceScreen preference =
                    getPreferenceManager().createPreferenceScreen(requireContext());
            preference.setKey("service_instances_" + service.getServiceId());
            preference.setPersistent(false);
            preference.setTitle(getString(R.string.service_instances_title,
                    service.getServiceInfo().getName()));
            preference.setSummary(R.string.service_instances_summary);
            preference.setFragment(ServiceInstanceListFragment.class.getName());
            preference.getExtras().putInt(Constants.KEY_SERVICE_ID, service.getServiceId());
            getPreferenceScreen().addPreference(preference);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        final Localization selectedLocalization = org.schabi.newpipe.util.Localization
                .getPreferredLocalization(requireContext());
        final ContentCountry selectedContentCountry = org.schabi.newpipe.util.Localization
                .getPreferredContentCountry(requireContext());
        final String selectedLanguage =
                defaultPreferences.getString(getString(R.string.app_language_key), "en");

        if (!selectedLocalization.equals(initialSelectedLocalization)
                || !selectedContentCountry.equals(initialSelectedContentCountry)
                || !selectedLanguage.equals(initialLanguage)) {
            Toast.makeText(requireContext(), R.string.localization_changes_requires_app_restart,
                    Toast.LENGTH_LONG).show();

            NewPipe.setupLocalization(selectedLocalization, selectedContentCountry);
        }
    }
}
