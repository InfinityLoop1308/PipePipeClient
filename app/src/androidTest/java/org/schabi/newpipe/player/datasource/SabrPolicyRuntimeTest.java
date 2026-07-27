package org.schabi.newpipe.player.datasource;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicyDocument;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicyHost;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.BuiltinSabrSessionPolicy;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

public final class SabrPolicyRuntimeTest {
    @Test
    public void installEquivalentBuiltinPolicyForBenchmark() throws Exception {
        final String privateKeyBase64 = InstrumentationRegistry.getArguments()
                .getString("sabrPolicyPrivateKeyBase64");
        assertTrue("Missing benchmark SABR private key",
                privateKeyBase64 != null && !privateKeyBase64.isEmpty());
        assertTrue("Benchmark build has no SABR public key",
                !BuildConfig.SABR_POLICY_PUBLIC_KEY_BASE64.isEmpty());
        final android.content.Context context = ApplicationProvider.getApplicationContext();
        context.getFileStreamPath("sabr-cloud-policy.bin").delete();
        context.getFileStreamPath("sabr-cloud-policy.rev").delete();
        final InputStream sourceInput = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("equivalent-builtin-sabr-policy.js");
        final byte[] sourceBytes = new byte[sourceInput.available()];
        int offset = 0;
        while (offset < sourceBytes.length) {
            final int read = sourceInput.read(sourceBytes, offset, sourceBytes.length - offset);
            if (read < 0) break;
            offset += read;
        }
        sourceInput.close();
        assertEquals(sourceBytes.length, offset);
        final long now = System.currentTimeMillis();
        final SabrScriptPolicy policy = new SabrScriptPolicy(1_000, now - 60_000,
                now + 24 * 60 * 60 * 1000L,
                new String(sourceBytes, StandardCharsets.UTF_8));
        final byte[] payload = policy.serialize();
        final Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(
                android.util.Base64.decode(privateKeyBase64, android.util.Base64.DEFAULT));
        final Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(payload, 0, payload.length);
        SabrPolicyRuntime.initialize(context, BuildConfig.SABR_POLICY_PUBLIC_KEY_BASE64, 0);
        SabrPolicyRuntime.install(payload, signer.generateSignature(), now);

        final SabrSessionPolicyHost host = SabrPolicyRuntime.createSessionHost();
        assertEquals(20, host.getMediaProtocol().getHeaderPartType());
        host.close();
    }

    @Test
    public void envelopeRoundTripsPayloadAndSignature() throws Exception {
        final byte[] payload = new byte[]{1, 2, 3, 4};
        final byte[] signature = new byte[]{5, 6, 7};

        final SabrPolicyRuntime.Envelope decoded = SabrPolicyRuntime.decodeEnvelope(
                SabrPolicyRuntime.encodeEnvelope(payload, signature));

        assertArrayEquals(payload, decoded.payload);
        assertArrayEquals(signature, decoded.signature);
    }

    @Test
    public void envelopeRejectsTrailingAndUnboundedData() {
        final byte[] valid = SabrPolicyRuntime.encodeEnvelope(
                new byte[]{1}, new byte[]{2});
        assertThrows(IOException.class, () -> SabrPolicyRuntime.decodeEnvelope(
                Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> SabrPolicyRuntime.encodeEnvelope(
                new byte[512 * 1024 + 1], new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> SabrPolicyRuntime.encodeEnvelope(
                new byte[]{1}, new byte[0]));
    }

    @Test
    public void signedPolicyPersistsAndRestoresOnAndroid() throws Exception {
        final android.content.Context context = ApplicationProvider.getApplicationContext();
        context.getFileStreamPath("sabr-cloud-policy.bin").delete();
        context.getFileStreamPath("sabr-cloud-policy.rev").delete();
        final Ed25519PrivateKeyParameters privateKey =
                new Ed25519PrivateKeyParameters(new SecureRandom());
        final long now = System.currentTimeMillis();
        final String source = "function createSabrPolicy(sabr){return{"
                + "describe:function(){return{media:{headerType:120,mediaType:121,endType:122}}},"
                + "initialRequest:function(e){return{body:e.fallbackBody}},"
                + "followUpRequest:function(e){return{body:e.fallbackBody}},"
                + "response:function(e){return{actions:['CONTINUE']}},"
                + "mediaHeader:function(e){return{headerId:1,itag:1}}}}";
        final SabrScriptPolicy policy = new SabrScriptPolicy(
                5, now - 1_000, now + 60_000, source);
        final byte[] payload = policy.serialize();
        final Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(payload, 0, payload.length);

        final String publicKey = android.util.Base64.encodeToString(
                privateKey.generatePublicKey().getEncoded(), android.util.Base64.NO_WRAP);
        SabrPolicyRuntime.initialize(context, publicKey, 0);
        SabrPolicyRuntime.installDocument(SabrScriptPolicyDocument.encode(
                policy, signer.generateSignature()), now);
        SabrPolicyRuntime.initialize(context, publicKey, 0);

        assertEquals(120, SabrPolicyRuntime.createSessionHost()
                .getMediaProtocol().getHeaderPartType());

        SabrPolicyRuntime.initialize(context, publicKey, 0);
        final SabrScriptPolicy rollback = new SabrScriptPolicy(
                4, now - 1_000, now + 60_000, source);
        final byte[] rollbackPayload = rollback.serialize();
        final Ed25519Signer rollbackSigner = new Ed25519Signer();
        rollbackSigner.init(true, privateKey);
        rollbackSigner.update(rollbackPayload, 0, rollbackPayload.length);
        assertThrows(IllegalArgumentException.class, () -> SabrPolicyRuntime.installDocument(
                SabrScriptPolicyDocument.encode(rollback,
                        rollbackSigner.generateSignature()), now));
    }

    @Test
    public void runtimeFailureDisablesCacheAndFallsBack() throws Exception {
        final android.content.Context context = ApplicationProvider.getApplicationContext();
        context.getFileStreamPath("sabr-cloud-policy.bin").delete();
        context.getFileStreamPath("sabr-cloud-policy.rev").delete();
        final Ed25519PrivateKeyParameters privateKey =
                new Ed25519PrivateKeyParameters(new SecureRandom());
        final long now = System.currentTimeMillis();
        final String source = "function createSabrPolicy(sabr){return{"
                + "describe:function(){return{media:{headerType:20,mediaType:21,endType:22,"
                + "headerDecoder:'builtin'}}},"
                + "initialRequest:function(){throw Error('broken')},"
                + "followUpRequest:function(){throw Error('broken')},"
                + "response:function(){return{actions:['CONTINUE']}},"
                + "mediaHeader:function(){return{headerId:1,itag:1}}}}";
        final SabrScriptPolicy policy = new SabrScriptPolicy(
                12, now - 1_000, now + 60_000, source);
        final byte[] payload = policy.serialize();
        final Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(payload, 0, payload.length);
        final String publicKey = android.util.Base64.encodeToString(
                privateKey.generatePublicKey().getEncoded(), android.util.Base64.NO_WRAP);
        SabrPolicyRuntime.initialize(context, publicKey, 0);
        SabrPolicyRuntime.install(payload, signer.generateSignature(), now);

        final SabrSessionPolicyHost host = SabrPolicyRuntime.createSessionHost();
        final byte[] fallback = new byte[]{4, 5, 6};
        assertArrayEquals(fallback, host.evaluate(new SabrSessionPolicy.State(0, 0, 0, 0),
                new SabrSessionPolicy.RequestEvent(0, 0, 0, 0, fallback)).getRequestBody());
        assertTrue(!context.getFileStreamPath("sabr-cloud-policy.bin").exists());

        final SabrSessionPolicyHost next = SabrPolicyRuntime.createSessionHost();
        final Field policyField = SabrSessionPolicyHost.class.getDeclaredField("policy");
        policyField.setAccessible(true);
        assertTrue(policyField.get(next) instanceof BuiltinSabrSessionPolicy);
        host.close();
        next.close();
    }
}
