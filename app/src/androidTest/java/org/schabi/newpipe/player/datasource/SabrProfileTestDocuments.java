package org.schabi.newpipe.player.datasource;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrCompatibilityProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrCompatibilityProfileClient;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrCompatibilityProfileDocument;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

public final class SabrProfileTestDocuments {
    private SabrProfileTestDocuments() {
    }

    public static byte[] signed(final long revision, final long now, final String keyId,
                                final Ed25519PrivateKeyParameters key,
                                final boolean includeMweb) {
        return signed(revision, now - 60_000, now + 3_600_000, keyId, key,
                includeMweb, false);
    }

    public static byte[] signedWithFailingMediaMapping(
            final long revision, final long now, final String keyId,
            final Ed25519PrivateKeyParameters key) {
        return signed(revision, now - 60_000, now + 3_600_000, keyId, key,
                true, true);
    }

    static byte[] signedExpired(final long revision, final long now, final String keyId,
                                final Ed25519PrivateKeyParameters key) {
        return signed(revision, now - 3_600_000, now - 1, keyId, key, true, false);
    }

    static SabrCompatibilityProfileClient mediaHeaderCollisionClient() {
        final long now = System.currentTimeMillis();
        final JsonObject root = document(1, now - 60_000, now + 60_000,
                "unused", false, false);
        final JsonObject client = root.getObject("clients").getObject("WEB");
        final JsonObject mapping = new JsonObject();
        mapping.put("partType", 20);
        mapping.put("target", "MEDIA_HEADER.SEQUENCE");
        mapping.put("path", new JsonArray(Collections.singletonList(2)));
        mapping.put("wireType", "VARINT");
        mapping.put("required", true);
        client.put("responseMappings", new JsonArray(Collections.singletonList(mapping)));
        return SabrCompatibilityProfileDocument.decode(
                JsonWriter.string(root).getBytes(StandardCharsets.UTF_8))
                .getProfile().getClient(YoutubeSabrClientProfile.WEB);
    }

    private static byte[] signed(final long revision, final long validFrom,
                                 final long validUntil, final String keyId,
                                 final Ed25519PrivateKeyParameters key,
                                 final boolean includeMweb,
                                 final boolean failingMediaMapping) {
        final JsonObject document = document(
                revision, validFrom, validUntil, keyId, includeMweb, failingMediaMapping);
        final byte[] unsigned = JsonWriter.string(document).getBytes(StandardCharsets.UTF_8);
        final SabrCompatibilityProfile profile =
                SabrCompatibilityProfileDocument.decode(unsigned).getProfile();
        final byte[] payload = profile.serialize();
        final Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, key);
        signer.update(payload, 0, payload.length);
        document.put("signature", java.util.Base64.getEncoder()
                .encodeToString(signer.generateSignature()));
        return JsonWriter.string(document).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject document(final long revision, final long validFrom,
                                       final long validUntil, final String keyId,
                                       final boolean includeMweb,
                                       final boolean failingMediaMapping) {
        final JsonObject root = new JsonObject();
        root.put("format", 1);
        root.put("revision", revision);
        root.put("validFromMs", validFrom);
        root.put("validUntilMs", validUntil);
        root.put("minimumExtractorRevision", 1);
        root.put("capabilities", new JsonArray(Arrays.asList("request-template-v1",
                "response-schema-v1", "recovery-rules-v1")));
        final JsonObject clients = new JsonObject();
        clients.put("WEB", client(failingMediaMapping));
        if (includeMweb) clients.put("MWEB", client(failingMediaMapping));
        root.put("clients", clients);
        root.put("keyId", keyId);
        root.put("signature", java.util.Base64.getEncoder().encodeToString(new byte[64]));
        return root;
    }

    private static JsonObject client(final boolean failingMediaMapping) {
        final JsonObject client = new JsonObject();
        final JsonObject media = new JsonObject();
        media.put("header", 20);
        media.put("payload", 21);
        media.put("end", 22);
        client.put("mediaParts", media);
        client.put("initialRequest", request());
        client.put("followingRequest", request());
        final JsonArray mappings = new JsonArray();
        if (failingMediaMapping) {
            final JsonObject mapping = new JsonObject();
            mapping.put("partType", 20);
            mapping.put("target", "MEDIA_HEADER.SEQUENCE");
            mapping.put("path", new JsonArray(Collections.singletonList(536_870_911)));
            mapping.put("wireType", "VARINT");
            mapping.put("required", true);
            mappings.add(mapping);
        }
        client.put("responseMappings", mappings);
        final JsonObject recovery = new JsonObject();
        recovery.put("maximumOmissions", 3);
        recovery.put("maximumElapsedMs", 15_000);
        recovery.put("forwardThresholdMs", 30_000);
        recovery.put("retryDelayMs", 0);
        client.put("recovery", recovery);
        client.put("rules", rules());
        return client;
    }

    private static JsonArray request() {
        final JsonArray fields = new JsonArray();
        fields.add(field(1, "BYTES", "CLIENT_ABR_STATE", true));
        fields.add(field(2, "BYTES", "SELECTED_FORMATS", false));
        fields.add(field(3, "BYTES", "BUFFERED_RANGES", false));
        fields.add(field(4, "VARINT", "PLAYER_TIME_MS", false));
        fields.add(field(5, "BYTES", "USTREAMER_CONFIG", true));
        fields.add(field(16, "BYTES", "PREFERRED_AUDIO_FORMATS", false));
        fields.add(field(17, "BYTES", "PREFERRED_VIDEO_FORMATS", false));
        fields.add(field(19, "BYTES", "CLIENT_CONTEXT", true));
        return fields;
    }

    private static JsonArray rules() {
        final JsonArray rules = new JsonArray();
        rules.add(rule(Collections.singletonList("HAS_ERROR"),
                Collections.singletonList("FAIL_SESSION")));
        rules.add(rule(Collections.singletonList("RELOAD_REQUESTED"),
                Collections.singletonList("TRY_RELOAD")));
        rules.add(rule(Arrays.asList("HAS_PROTECTION_BOUNDARY", "FETCH_SEGMENT_MODE"),
                Arrays.asList("REQUIRE_PO_TOKEN", "RETRY")));
        rules.add(rule(Arrays.asList("HAS_PROTECTION_BOUNDARY", "PUMP_MODE"),
                Arrays.asList("REFRESH_PO_TOKEN", "CONTINUE")));
        rules.add(rule(Collections.singletonList("HAS_REDIRECT"),
                Arrays.asList("APPLY_REDIRECT", "CONTINUE")));
        rules.add(rule(Collections.emptyList(), Collections.singletonList("CONTINUE")));
        return rules;
    }

    private static JsonObject rule(final java.util.List<String> predicates,
                                   final java.util.List<String> actions) {
        final JsonObject rule = new JsonObject();
        rule.put("whenAll", new JsonArray(predicates));
        rule.put("actions", new JsonArray(actions));
        return rule;
    }

    private static JsonObject field(final int number, final String wireType,
                                    final String source, final boolean required) {
        final JsonObject field = new JsonObject();
        field.put("field", number);
        field.put("wireType", wireType);
        field.put("source", source);
        field.put("required", required);
        return field;
    }
}
