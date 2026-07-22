package org.schabi.newpipe.player.datasource;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Base64;

import androidx.test.core.app.ApplicationProvider;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrCompatibilityProfileDocument;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicyHost;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

public final class SabrPolicyRuntimeTest {
    private static final String CHANNEL = "test-cache";
    private static final String STABLE_CHANNEL = "test-stable";
    private static final String BETA_CHANNEL = "test-beta";
    private static final String CACHE_FILE = "sabr-compatibility-" + CHANNEL + ".bin";
    private static final String REVISION_FILE = "sabr-compatibility-" + CHANNEL + ".rev";
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        clearFiles();
    }

    @After
    public void tearDown() {
        SabrPolicyRuntime.setBenchmarkPolicyMode(SabrPolicyRuntime.BenchmarkPolicyMode.AUTO);
        SabrPolicyRuntime.initialize(context, "", CHANNEL, 0);
        clearFiles();
    }

    @Test
    public void publicKeyListSupportsRotation() {
        final byte[] first = new byte[32];
        final byte[] second = new byte[32];
        Arrays.fill(second, (byte) 7);
        final String encoded = "old=" + Base64.encodeToString(first, Base64.NO_WRAP)
                + ",current=" + Base64.encodeToString(second, Base64.NO_WRAP);

        final Map<String, byte[]> parsed = SabrPolicyRuntime.parsePublicKeys(encoded);

        assertArrayEquals(first, parsed.get("old"));
        assertArrayEquals(second, parsed.get("current"));
        assertThrows(IllegalArgumentException.class,
                () -> SabrPolicyRuntime.parsePublicKeys("broken"));
        assertThrows(IllegalArgumentException.class,
                () -> SabrPolicyRuntime.parsePublicKeys(encoded + ",old="
                        + Base64.encodeToString(first, Base64.NO_WRAP)));
    }

    @Test
    public void invalidReinitializationClearsThePreviousRegistry() throws Exception {
        final Ed25519PrivateKeyParameters key = key();
        final long now = System.currentTimeMillis();
        SabrPolicyRuntime.initialize(context, encodedKey("current", key), CHANNEL, 0);
        SabrPolicyRuntime.installDocument(SabrProfileTestDocuments.signed(
                3, now, "current", key, true), now);

        assertThrows(IllegalArgumentException.class,
                () -> SabrPolicyRuntime.initialize(context, "broken", CHANNEL, 0));
        assertEquals(-1, SabrPolicyRuntime.currentRevision());
    }

    @Test
    public void signedProfilePersistsOfflineAndRejectsRollback() throws Exception {
        final Ed25519PrivateKeyParameters key = key();
        final String keys = encodedKey("current", key);
        final long now = System.currentTimeMillis();
        final byte[] revisionFive = SabrProfileTestDocuments.signed(
                5, now, "current", key, true);

        SabrPolicyRuntime.initialize(context, keys, CHANNEL, 0);
        SabrPolicyRuntime.installDocument(revisionFive, now);
        SabrPolicyRuntime.setBenchmarkPolicyMode(SabrPolicyRuntime.BenchmarkPolicyMode.PROFILE);
        SabrPolicyRuntime.createSessionHost().close();
        SabrPolicyRuntime.initialize(context, keys, CHANNEL, 0);

        assertEquals(5, SabrPolicyRuntime.currentRevision());
        assertTrue(context.getFileStreamPath(CACHE_FILE).isFile());
        assertTrue(context.getFileStreamPath(REVISION_FILE).isFile());
        assertThrows(IllegalArgumentException.class, () -> SabrPolicyRuntime.installDocument(
                SabrProfileTestDocuments.signed(4, now, "current", key, true), now));
        final byte[] tampered = new String(revisionFive, StandardCharsets.UTF_8)
                .replace("\"maximumOmissions\":3", "\"maximumOmissions\":4")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> SabrPolicyRuntime.installDocument(tampered, now));
        assertEquals(5, SabrPolicyRuntime.currentRevision());
    }

    @Test
    public void keyRotationAcceptsDocumentsFromBothEmbeddedKeys() throws Exception {
        final Ed25519PrivateKeyParameters oldKey = key();
        final Ed25519PrivateKeyParameters currentKey = key();
        final String keys = encodedKey("old", oldKey) + ","
                + encodedKey("current", currentKey);
        final long now = System.currentTimeMillis();
        SabrPolicyRuntime.initialize(context, keys, CHANNEL, 0);

        SabrPolicyRuntime.installDocument(SabrProfileTestDocuments.signed(
                10, now, "old", oldKey, true), now);
        SabrPolicyRuntime.installDocument(SabrProfileTestDocuments.signed(
                11, now, "current", currentKey, true), now);

        assertEquals(11, SabrPolicyRuntime.currentRevision());
    }

    @Test
    public void profileFailurePromotesPreviousProfileAndPersistsFallback() throws Exception {
        final Ed25519PrivateKeyParameters key = key();
        final String keys = encodedKey("current", key);
        final long now = System.currentTimeMillis();
        SabrPolicyRuntime.initialize(context, keys, CHANNEL, 0);
        SabrPolicyRuntime.installDocument(SabrProfileTestDocuments.signed(
                7, now, "current", key, true), now);
        SabrPolicyRuntime.installDocument(SabrProfileTestDocuments.signed(
                8, now, "current", key, true), now);
        final SabrSessionPolicyHost host = SabrPolicyRuntime.createSessionHost();
        final byte[] bundledRequest = new byte[]{4, 5, 6};

        assertArrayEquals(bundledRequest, host.evaluate(new SabrSessionPolicy.State(0, 0, 0, 0),
                new SabrSessionPolicy.RequestEvent(0, 0, -1, 0, bundledRequest))
                .getRequestBody());
        assertEquals(7, SabrPolicyRuntime.currentRevision());
        host.close();

        SabrPolicyRuntime.initialize(context, keys, CHANNEL, 0);
        assertEquals(7, SabrPolicyRuntime.currentRevision());
        assertThrows(IllegalArgumentException.class, () -> SabrPolicyRuntime.installDocument(
                SabrProfileTestDocuments.signed(8, now, "current", key, true), now));
        assertThrows(IllegalArgumentException.class, () -> SabrPolicyRuntime.installDocument(
                SabrProfileTestDocuments.signed(6, now, "current", key, true), now));
    }

    @Test
    public void corruptedCacheDoesNotEraseRollbackFloor() throws Exception {
        final Ed25519PrivateKeyParameters key = key();
        final String keys = encodedKey("current", key);
        final long now = System.currentTimeMillis();
        SabrPolicyRuntime.initialize(context, keys, CHANNEL, 0);
        SabrPolicyRuntime.installDocument(SabrProfileTestDocuments.signed(
                20, now, "current", key, true), now);
        try (FileOutputStream output = context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE)) {
            output.write(new byte[]{1, 2, 3});
        }

        SabrPolicyRuntime.initialize(context, keys, CHANNEL, 0);

        assertEquals(-1, SabrPolicyRuntime.currentRevision());
        assertThrows(IllegalArgumentException.class, () -> SabrPolicyRuntime.installDocument(
                SabrProfileTestDocuments.signed(19, now, "current", key, true), now));
    }

    @Test
    public void oldSignedCacheNeedsAnExplicitCircuitBreakerMarker() throws Exception {
        final Ed25519PrivateKeyParameters key = key();
        final String keys = encodedKey("current", key);
        final long now = System.currentTimeMillis();
        final byte[] oldDocument = SabrProfileTestDocuments.signed(
                49, now, "current", key, true);
        SabrPolicyRuntime.initialize(context, keys, CHANNEL, 0);
        SabrPolicyRuntime.installDocument(SabrProfileTestDocuments.signed(
                50, now, "current", key, true), now);
        new SabrProfileCache(context, CHANNEL).writeState(
                new SabrProfileCache.State(oldDocument, null, 0));

        SabrPolicyRuntime.initialize(context, keys, CHANNEL, 0);

        assertEquals(-1, SabrPolicyRuntime.currentRevision());
        assertThrows(IllegalArgumentException.class,
                () -> SabrPolicyRuntime.installDocument(oldDocument, now));
    }

    @Test
    public void legacyNormalCacheMigratesWithoutLosingTheActiveProfile() throws Exception {
        final Ed25519PrivateKeyParameters key = key();
        final String keys = encodedKey("current", key);
        final long now = System.currentTimeMillis();
        final byte[] document = SabrProfileTestDocuments.signed(
                60, now, "current", key, true);
        SabrPolicyRuntime.initialize(context, keys, CHANNEL, 0);
        SabrPolicyRuntime.installDocument(document, now);
        writeLegacyState(document);

        SabrPolicyRuntime.initialize(context, keys, CHANNEL, 0);

        assertEquals(60, SabrPolicyRuntime.currentRevision());
    }

    @Test
    public void executableExpiredAndOversizedDocumentsAreRejected() throws Exception {
        final Ed25519PrivateKeyParameters key = key();
        final long now = System.currentTimeMillis();
        SabrPolicyRuntime.initialize(context, encodedKey("current", key), CHANNEL, 0);

        assertThrows(IllegalArgumentException.class, () -> SabrPolicyRuntime.installDocument(
                "{\"format\":1,\"source\":\"function(){}\"}"
                        .getBytes(StandardCharsets.UTF_8), now));
        assertThrows(IllegalArgumentException.class, () -> SabrPolicyRuntime.installDocument(
                SabrProfileTestDocuments.signedExpired(30, now, "current", key), now));
        assertThrows(IllegalArgumentException.class, () -> SabrPolicyRuntime.installDocument(
                new byte[SabrCompatibilityProfileDocument.MAX_DOCUMENT_BYTES + 1], now));
        assertEquals(-1, SabrPolicyRuntime.currentRevision());
    }

    @Test
    public void betaVersionSelectsBetaProfileChannel() {
        assertEquals(BuildConfig.VERSION_NAME.contains("-beta") ? "beta" : "stable",
                BuildConfig.SABR_COMPATIBILITY_PROFILE_CHANNEL);
    }

    @Test
    public void stableAndBetaCachesAndRevisionFloorsAreIsolated() throws Exception {
        final Ed25519PrivateKeyParameters key = key();
        final String keys = encodedKey("current", key);
        final long now = System.currentTimeMillis();
        SabrPolicyRuntime.initialize(context, keys, STABLE_CHANNEL, 0);
        SabrPolicyRuntime.installDocument(SabrProfileTestDocuments.signed(
                40, now, "current", key, true), now);

        SabrPolicyRuntime.initialize(context, keys, BETA_CHANNEL, 0);
        assertEquals(-1, SabrPolicyRuntime.currentRevision());
        SabrPolicyRuntime.installDocument(SabrProfileTestDocuments.signed(
                2, now, "current", key, true), now);

        SabrPolicyRuntime.initialize(context, keys, STABLE_CHANNEL, 0);
        assertEquals(40, SabrPolicyRuntime.currentRevision());
    }

    private void clearFiles() {
        clearChannel(CHANNEL);
        clearChannel(STABLE_CHANNEL);
        clearChannel(BETA_CHANNEL);
    }

    private void clearChannel(final String channel) {
        context.getFileStreamPath("sabr-compatibility-" + channel + ".bin").delete();
        context.getFileStreamPath("sabr-compatibility-" + channel + ".rev").delete();
    }

    private void writeLegacyState(final byte[] document) throws Exception {
        try (DataOutputStream output = new DataOutputStream(
                context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE))) {
            output.writeInt(0x53435043);
            output.writeByte(1);
            output.writeInt(document.length);
            output.write(document);
            output.writeInt(-1);
        }
    }

    private static Ed25519PrivateKeyParameters key() {
        return new Ed25519PrivateKeyParameters(new SecureRandom());
    }

    private static String encodedKey(final String id,
                                     final Ed25519PrivateKeyParameters key) {
        return id + "=" + Base64.encodeToString(
                key.generatePublicKey().getEncoded(), Base64.NO_WRAP);
    }
}
