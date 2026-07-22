package org.schabi.newpipe.player.datasource;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.extractor.services.youtube.sabr.BuiltinSabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.CompatibilitySabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.FallbackSabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrCompatibilityProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicyHost;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicyTranscript;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Client-owned delivery boundary for signed declarative SABR compatibility profiles. */
public final class SabrPolicyRuntime {
    public enum BenchmarkPolicyMode { AUTO, BUILTIN, PROFILE }

    private static final String TAG = "SabrProfileRuntime";
    private static final int SESSION_TRANSCRIPT_CAPACITY = 512;
    @NonNull private static final SabrSessionPolicy BUILTIN = new BuiltinSabrSessionPolicy();
    @Nullable private static volatile SabrProfileRegistry registry;
    @NonNull private static volatile BenchmarkPolicyMode benchmarkPolicyMode =
            BenchmarkPolicyMode.AUTO;

    private SabrPolicyRuntime() {
    }

    @NonNull
    public static SabrSessionPolicyHost createSessionHost() {
        final BenchmarkPolicyMode mode = benchmarkPolicyMode;
        if (mode == BenchmarkPolicyMode.BUILTIN) {
            return createHost(BUILTIN);
        }
        final SabrProfileRegistry localRegistry = registry;
        final SabrCompatibilityProfile profile = localRegistry == null
                ? null : localRegistry.current(System.currentTimeMillis());
        if (profile == null) {
            if (mode == BenchmarkPolicyMode.PROFILE) {
                throw new IllegalStateException("No active SABR compatibility profile");
            }
            return createHost(BUILTIN);
        }
        Log.i(TAG, "Using SABR compatibility profile revision=" + profile.getRevision());
        final SabrSessionPolicy primary = new CompatibilitySabrSessionPolicy(profile);
        return createHost(new FallbackSabrSessionPolicy(primary, BUILTIN,
                failure -> disable(localRegistry, profile, failure)));
    }

    public static void setBenchmarkPolicyMode(@NonNull final BenchmarkPolicyMode mode) {
        if (!BuildConfig.DEBUG) {
            throw new IllegalStateException("SABR benchmark override requires a debug build");
        }
        benchmarkPolicyMode = mode;
    }

    public static synchronized void initialize(@NonNull final Context context,
                                               @Nullable final String encodedPublicKeys,
                                               @NonNull final String channel,
                                               final long minimumRevision) {
        registry = null;
        final Map<String, byte[]> keys = parsePublicKeys(encodedPublicKeys);
        registry = keys.isEmpty() ? null
                : SabrProfileRegistry.restore(context, keys, channel, minimumRevision);
    }

    /** Verifies, atomically persists, then activates a downloaded profile. */
    public static synchronized void installDocument(@NonNull final byte[] document,
                                                    final long nowMs) throws IOException {
        final SabrProfileRegistry current = registry;
        if (current == null) {
            throw new IllegalStateException("SABR profile runtime is not initialized");
        }
        current.install(document, nowMs);
    }

    public static synchronized long currentRevision() {
        final SabrProfileRegistry current = registry;
        return current == null ? -1 : current.currentRevision(System.currentTimeMillis());
    }

    @NonNull
    static Map<String, byte[]> parsePublicKeys(@Nullable final String encoded) {
        final Map<String, byte[]> keys = new LinkedHashMap<>();
        if (encoded == null || encoded.trim().isEmpty()) {
            return keys;
        }
        for (final String entry : encoded.split(",", -1)) {
            final int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("Invalid SABR profile public-key entry");
            }
            final String keyId = entry.substring(0, separator);
            final byte[] key;
            try {
                key = Base64.decode(entry.substring(separator + 1), Base64.DEFAULT);
            } catch (final IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid SABR profile public key", failure);
            }
            if (keys.put(keyId, key) != null) {
                throw new IllegalArgumentException("Duplicate SABR profile public key");
            }
        }
        return keys;
    }

    @NonNull
    private static SabrSessionPolicyHost createHost(@NonNull final SabrSessionPolicy policy) {
        return new SabrSessionPolicyHost(policy,
                new SabrSessionPolicyTranscript(SESSION_TRANSCRIPT_CAPACITY));
    }

    private static synchronized void disable(@NonNull final SabrProfileRegistry expectedRegistry,
                                             @NonNull final SabrCompatibilityProfile profile,
                                             @NonNull final Throwable failure) {
        if (registry != expectedRegistry || !expectedRegistry.disable(profile)) {
            return;
        }
        Log.w(TAG, "Disabled SABR profile revision=" + profile.getRevision()
                + " failure=" + failure.getClass().getSimpleName());
    }
}
