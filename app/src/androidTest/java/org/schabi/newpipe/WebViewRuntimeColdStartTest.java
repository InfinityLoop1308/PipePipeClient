package org.schabi.newpipe;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * One process equals one WebView-runtime cold-start sample. The host runner must force-stop the
 * target process between invocations.
 */
@RunWith(AndroidJUnit4.class)
public final class WebViewRuntimeColdStartTest {
    private static final String TAG = "WebViewRuntimeColdStart";

    @Test
    public void runtimeBecomesReady() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        final Bundle arguments = InstrumentationRegistry.getArguments();
        final long timeoutMs = Long.parseLong(arguments.getString("timeoutMs", "15000"));
        final long startedAtMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "await start timeoutMs=" + timeoutMs);
        try {
            SharedWebViewRuntime.get(context).ensureReady(timeoutMs,
                    "cold-start instrumentation benchmark");
        } catch (final Throwable error) {
            Log.e(TAG, "await failed elapsedMs="
                    + (SystemClock.elapsedRealtime() - startedAtMs), error);
            throw error;
        }
        Log.i(TAG, "await ready elapsedMs="
                + (SystemClock.elapsedRealtime() - startedAtMs));
    }
}
