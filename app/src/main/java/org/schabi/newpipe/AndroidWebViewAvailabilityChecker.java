package org.schabi.newpipe;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.webkit.WebViewCompat;

import org.schabi.newpipe.extractor.WebViewAvailabilityChecker;
import org.schabi.newpipe.extractor.exceptions.WebViewUnavailableException;

public final class AndroidWebViewAvailabilityChecker implements WebViewAvailabilityChecker {
    private final Context appContext;
    private final SharedWebViewRuntime runtime;

    public AndroidWebViewAvailabilityChecker(@NonNull final Context context) {
        appContext = context.getApplicationContext();
        runtime = SharedWebViewRuntime.get(appContext);
    }

    public void warmUp() {
        if (WebViewCompat.getCurrentWebViewPackage(appContext) != null) {
            runtime.warmUp();
        }
    }

    @Override
    public void checkWebViewAvailable() throws WebViewUnavailableException {
        if (WebViewCompat.getCurrentWebViewPackage(appContext) == null) {
            throw new WebViewUnavailableException(
                    "No Android WebView provider is available", false, null);
        }
        final Throwable failure = runtime.getInitializationFailure();
        if (failure != null) {
            // The runtime may have failed a transient cold start. Allow the next attempt to
            // initialize it again instead of failing YouTube for the rest of the process.
            runtime.resetFailedInitialization();
            runtime.warmUp();
            throw new WebViewUnavailableException(
                    "Android WebView runtime failed to become ready", failure);
        }
    }
}
