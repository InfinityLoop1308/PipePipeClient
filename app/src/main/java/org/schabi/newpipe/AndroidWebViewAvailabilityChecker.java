package org.schabi.newpipe;

import android.content.Context;
import android.content.pm.PackageInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewCompat;

import org.schabi.newpipe.extractor.WebViewAvailabilityChecker;
import org.schabi.newpipe.extractor.exceptions.WebViewUnavailableException;

public final class AndroidWebViewAvailabilityChecker implements WebViewAvailabilityChecker {
    private static final int MIN_WEBVIEW_MAJOR_VERSION = 80;

    private final SharedWebViewRuntime runtime;
    @Nullable
    private final PackageInfo packageInfo;
    @Nullable
    private volatile WebViewUnavailableException unavailableException;

    public AndroidWebViewAvailabilityChecker(@NonNull final Context context) {
        final Context appContext = context.getApplicationContext();
        runtime = SharedWebViewRuntime.get(appContext);

        final PackageInfo currentPackageInfo = WebViewCompat.getCurrentWebViewPackage(appContext);
        packageInfo = currentPackageInfo;
        unavailableException = checkProvider(currentPackageInfo);
    }

    public void warmUp() {
        if (unavailableException == null) {
            runtime.warmUp(this::onInitializationFailure);
        }
    }

    @Override
    public void checkWebViewAvailable() throws WebViewUnavailableException {
        final WebViewUnavailableException exception = unavailableException;
        if (exception != null) {
            throw exception;
        }
    }

    private void onInitializationFailure(@NonNull final Throwable throwable) {
        final PackageInfo info = packageInfo;
        if (info == null) {
            unavailableException = new WebViewUnavailableException(
                    "Android WebView provider failed to initialize",
                    throwable);
            return;
        }
        unavailableException = new WebViewUnavailableException(
                "Android WebView provider " + info.packageName + " version "
                        + info.versionName + " failed to initialize",
                throwable);
    }

    @Nullable
    private static WebViewUnavailableException checkProvider(final PackageInfo packageInfo) {
        if (packageInfo == null) {
            return new WebViewUnavailableException("No Android WebView provider is available");
        }

        final String versionName = packageInfo.versionName;
        try {
            final int majorVersion = parseMajorVersion(versionName);
            if (majorVersion < MIN_WEBVIEW_MAJOR_VERSION) {
                return new WebViewUnavailableException("Android WebView provider "
                        + packageInfo.packageName + " version " + versionName
                        + " is lower than required major version "
                        + MIN_WEBVIEW_MAJOR_VERSION);
            }
        } catch (final WebViewUnavailableException e) {
            return e;
        }
        return null;
    }

    private static int parseMajorVersion(final String versionName) throws WebViewUnavailableException {
        if (versionName == null || versionName.isEmpty()) {
            throw new WebViewUnavailableException("Android WebView provider has no version name");
        }

        final int dotIndex = versionName.indexOf('.');
        final String major = dotIndex >= 0 ? versionName.substring(0, dotIndex) : versionName;
        try {
            return Integer.parseInt(major);
        } catch (final NumberFormatException e) {
            throw new WebViewUnavailableException(
                    "Could not parse Android WebView provider version " + versionName,
                    e);
        }
    }
}
