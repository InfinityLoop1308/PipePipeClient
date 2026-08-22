package org.schabi.newpipe.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.local.subscription.SubscriptionManager;
import org.schabi.newpipe.local.subscription.SubscriptionsImportExportHelper;
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PicassoHelper;
import org.schabi.newpipe.util.ZipHelper;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

import static org.schabi.newpipe.extractor.utils.Utils.isBlank;
import static org.schabi.newpipe.util.Localization.assureCorrectAppLanguage;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class BackupSettingsFragment extends BasePreferenceFragment {
    private static final String ZIP_MIME_TYPE = "application/zip";

    private final SimpleDateFormat exportDateFormat
            = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private ContentSettingsManager manager;
    private SubscriptionManager subscriptionManager;
    private final CompositeDisposable disposables = new CompositeDisposable();

    private String importExportDataPathKey;
    private SubscriptionsImportExportHelper subscriptionsImportExportHelper;

    private final ActivityResultLauncher<Intent> requestImportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::requestImportPathResult);
    private final ActivityResultLauncher<Intent> requestExportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::requestExportPathResult);

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);
        subscriptionsImportExportHelper = new SubscriptionsImportExportHelper(this);
        subscriptionManager = new SubscriptionManager(context);
    }

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        final File homeDir = ContextCompat.getDataDir(requireContext());
        Objects.requireNonNull(homeDir);
        manager = new ContentSettingsManager(new NewPipeFileLocator(homeDir));
        manager.deleteSettingsFile();

        importExportDataPathKey = getString(R.string.import_export_data_path);

        addPreferencesFromResourceRegistry();

        final Preference importDataPreference = requirePreference(R.string.import_data);
        importDataPreference.setOnPreferenceClickListener((Preference p) -> {
            NoFileManagerSafeGuard.launchSafe(
                    requestImportPathLauncher,
                    StoredFileHelper.getPicker(requireContext(),
                            ZIP_MIME_TYPE, getImportExportDataUri()),
                    TAG,
                    getContext()
            );

            return true;
        });

        final Preference exportDataPreference = requirePreference(R.string.export_data);
        exportDataPreference.setOnPreferenceClickListener((final Preference p) -> {
            NoFileManagerSafeGuard.launchSafe(
                    requestExportPathLauncher,
                    StoredFileHelper.getNewPicker(requireContext(),
                            "PipePipeData-" + exportDateFormat.format(new Date()) + ".zip",
                            ZIP_MIME_TYPE, getImportExportDataUri()),
                    TAG,
                    getContext()
            );

            return true;
        });

        final Preference importSubscriptionsPreference =
                requirePreference(R.string.import_subscriptions_key);
        importSubscriptionsPreference.setOnPreferenceClickListener((final Preference p) -> {
            subscriptionsImportExportHelper.importSubscriptions();
            return true;
        });

        final Preference importYouTubeSubscriptionsPreference =
                requirePreference(R.string.import_youtube_subscriptions_key);
        importYouTubeSubscriptionsPreference.setOnPreferenceClickListener((final Preference p) -> {
            NavigationHelper.openSubscriptionsImportFragment(getParentFragmentManager(),
                    R.id.settings_fragment_holder, ServiceList.YouTube.getServiceId());
            return true;
        });

        final Preference importSoundCloudSubscriptionsPreference =
                requirePreference(R.string.import_soundcloud_subscriptions_key);
        importSoundCloudSubscriptionsPreference.setOnPreferenceClickListener(
                (final Preference p) -> {
                    NavigationHelper.openSubscriptionsImportFragment(getParentFragmentManager(),
                            R.id.settings_fragment_holder, ServiceList.SoundCloud.getServiceId());
                    return true;
                });

        final Preference exportSubscriptionsPreference =
                requirePreference(R.string.export_subscriptions_key);
        exportSubscriptionsPreference.setOnPreferenceClickListener((final Preference p) -> {
            subscriptionsImportExportHelper.exportSubscriptions();
            return true;
        });

        final Preference clearSubscriptionsPreference =
                requirePreference(R.string.clear_subscriptions_key);
        clearSubscriptionsPreference.setOnPreferenceClickListener((final Preference p) -> {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.clear_subscriptions_title)
                    .setMessage(R.string.clear_subscriptions_confirmation)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.clear, (dialog, which) ->
                            clearSubscriptions())
                    .show();
            return true;
        });

    }

    private void clearSubscriptions() {
        disposables.add(subscriptionManager.deleteAllSubscriptions()
                .subscribe(
                        () -> Toast.makeText(requireContext(), R.string.subscriptions_cleared,
                                Toast.LENGTH_SHORT).show(),
                        e -> ErrorUtil.showUiErrorSnackbar(this, "Clearing subscriptions", e)));
    }

    @Override
    public void onDestroy() {
        disposables.clear();
        super.onDestroy();
    }

    private void requestExportPathResult(final ActivityResult result) {
        assureCorrectAppLanguage(getContext());
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            // will be saved only on success
            final Uri lastExportDataUri = result.getData().getData();

            final StoredFileHelper file
                    = new StoredFileHelper(getContext(), result.getData().getData(), ZIP_MIME_TYPE);

            exportDatabase(file, lastExportDataUri);
        }
    }

    private void requestImportPathResult(final ActivityResult result) {
        assureCorrectAppLanguage(getContext());
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            // will be saved only on success
            final Uri lastImportDataUri = result.getData().getData();

            final StoredFileHelper file
                    = new StoredFileHelper(getContext(), result.getData().getData(), ZIP_MIME_TYPE);

            new AlertDialog.Builder(requireActivity())
                    .setMessage(R.string.override_current_data)
                    .setPositiveButton(R.string.ok, (d, id) ->
                            importDatabase(file, lastImportDataUri))
                    .setNegativeButton(R.string.cancel, (d, id) ->
                            d.cancel())
                    .create()
                    .show();
        }
    }

    private void exportDatabase(final StoredFileHelper file, final Uri exportDataUri) {
        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(requireContext());
        disposables.add(Completable.fromAction(() -> {
            NewPipeDatabase.checkpoint();
            manager.exportDatabase(preferences, file);
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    saveLastImportExportDataUri(exportDataUri);
                    Toast.makeText(getContext(), R.string.export_complete_toast,
                            Toast.LENGTH_SHORT).show();
                }, e -> ErrorUtil.showUiErrorSnackbar(this, "Exporting database", e)));
    }

    private void importDatabase(final StoredFileHelper file, final Uri importDataUri) {
        // check if file is supported
        if (!ZipHelper.isValidZipFile(file)) {
            Toast.makeText(getContext(), R.string.no_valid_zip_file, Toast.LENGTH_SHORT)
                    .show();
            return;
        }

        try {
            if (!manager.ensureDbDirectoryExists()) {
                throw new IOException("Could not create databases dir");
            }

            if (!manager.extractDb(file)) {
                Toast.makeText(getContext(), R.string.could_not_import_all_files, Toast.LENGTH_LONG)
                        .show();
            }

            // if settings file exist, ask if it should be imported.
            if (manager.extractSettings(file)) {
                final AlertDialog.Builder alert = new AlertDialog.Builder(requireContext());
                alert.setTitle(R.string.import_settings);

                alert.setNegativeButton(R.string.cancel, (dialog, which) -> {
                    dialog.dismiss();
                    finishImport(importDataUri);
                });
                alert.setPositiveButton(R.string.ok, (dialog, which) -> {
                    dialog.dismiss();
                    SharedPreferences sharedPreferences = PreferenceManager
                            .getDefaultSharedPreferences(requireContext());
                    manager.loadSharedPreferences(sharedPreferences);
                    finishImport(importDataUri);
                });
                alert.show();
            } else {
                finishImport(importDataUri);
            }
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Importing database", e);
        }
    }

    /**
     * Save import path and restart system.
     *
     * @param importDataUri The import path to save
     */
    private void finishImport(final Uri importDataUri) {
        // save import path only on success
        saveLastImportExportDataUri(importDataUri);
        // restart app to properly load db
        NavigationHelper.restartApp(requireActivity());
    }

    private Uri getImportExportDataUri() {
        final String path = defaultPreferences.getString(importExportDataPathKey, null);
        return isBlank(path) ? null : Uri.parse(path);
    }

    private void saveLastImportExportDataUri(final Uri importExportDataUri) {
        final SharedPreferences.Editor editor = defaultPreferences.edit()
                .putString(importExportDataPathKey, importExportDataUri.toString());
        editor.apply();
    }
}
