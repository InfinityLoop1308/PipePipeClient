package org.schabi.newpipe.player.datasource;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.Bundle;
import android.util.Base64;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.BuildConfig;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class SabrProfileProcessRestartTest {
    private static final String CHANNEL = "process-restart";
    private static final long REVISION = 101;

    @Test
    public void signedProfileSurvivesProcessRestart() throws Exception {
        final Context context = ApplicationProvider.getApplicationContext();
        final Bundle arguments = InstrumentationRegistry.getArguments();
        final String phase = arguments.getString("phase", "");
        final Ed25519PrivateKeyParameters key = deterministicKey();
        final String publicKey = "restart=" + Base64.encodeToString(
                key.generatePublicKey().getEncoded(), Base64.NO_WRAP);

        if ("install-app".equals(phase)) {
            final String channel = BuildConfig.SABR_COMPATIBILITY_PROFILE_CHANNEL;
            clear(context, channel);
            final long now = System.currentTimeMillis();
            SabrPolicyRuntime.initialize(context, publicKey, channel, 0);
            SabrPolicyRuntime.installDocument(SabrProfileTestDocuments.signed(
                    REVISION, now, "restart", key, true), now);
            assertEquals(REVISION, SabrPolicyRuntime.currentRevision());
            return;
        }
        if ("clear-app".equals(phase)) {
            clear(context, BuildConfig.SABR_COMPATIBILITY_PROFILE_CHANNEL);
            return;
        }
        if ("install".equals(phase)) {
            clear(context, CHANNEL);
            final long now = System.currentTimeMillis();
            SabrPolicyRuntime.initialize(context, publicKey, CHANNEL, 0);
            SabrPolicyRuntime.installDocument(SabrProfileTestDocuments.signed(
                    REVISION, now, "restart", key, true), now);
            assertEquals(REVISION, SabrPolicyRuntime.currentRevision());
            return;
        }
        if (!"restore".equals(phase)) {
            throw new IllegalArgumentException(
                    "Expected install, restore, install-app, or clear-app phase");
        }
        try {
            SabrPolicyRuntime.initialize(context, publicKey, CHANNEL, 0);
            SabrPolicyRuntime.setBenchmarkPolicyMode(
                    SabrPolicyRuntime.BenchmarkPolicyMode.PROFILE);
            assertEquals(REVISION, SabrPolicyRuntime.currentRevision());
            SabrPolicyRuntime.createSessionHost().close();
        } finally {
            SabrPolicyRuntime.setBenchmarkPolicyMode(
                    SabrPolicyRuntime.BenchmarkPolicyMode.AUTO);
            SabrPolicyRuntime.initialize(context, "", CHANNEL, 0);
            clear(context, CHANNEL);
        }
    }

    private static Ed25519PrivateKeyParameters deterministicKey() {
        final byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x5a);
        return new Ed25519PrivateKeyParameters(seed);
    }

    private static void clear(final Context context, final String channel) {
        context.getFileStreamPath("sabr-compatibility-" + channel + ".bin").delete();
        context.getFileStreamPath("sabr-compatibility-" + channel + ".rev").delete();
    }
}
