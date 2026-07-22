package org.schabi.newpipe.player.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.schabi.newpipe.extractor.services.youtube.sabr.FallbackSabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.ProfiledSabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaHeader;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaProtocol;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrRecoverableException;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrStreamingResponseReader;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

public final class SabrProfileFallbackHarnessTest {
    @Test
    public void failedProfileFramingRetriesWithBuiltinProtocolOnNextResponse() {
        final AtomicInteger failures = new AtomicInteger();
        final FallbackSabrSessionPolicy policy = new FallbackSabrSessionPolicy(
                failingProfile(), failure -> failures.incrementAndGet());
        final SabrMediaProtocol responseProtocol = policy.getMediaProtocol();
        final byte[] customHeaderPart = new byte[]{20, 1, 0};

        assertEquals(20, responseProtocol.getHeaderPartType());
        assertThrows(SabrRecoverableException.class, () ->
                SabrStreamingResponseReader.readUntil(
                        new ByteArrayInputStream(customHeaderPart), null, null, null,
                        responseProtocol));

        assertTrue(policy.isDisabled());
        assertEquals(1, failures.get());
        assertEquals(20, responseProtocol.getHeaderPartType());
        assertEquals(SabrMediaProtocol.builtin().getHeaderPartType(),
                policy.getMediaProtocol().getHeaderPartType());
    }

    @Test
    public void profileMappingCanReuseBuiltinFieldWithDifferentWireType()
            throws SabrProtocolException {
        final SabrMediaProtocol protocol = new ProfiledSabrSessionPolicy(
                SabrProfileTestDocuments.mediaHeaderCollisionClient()).getMediaProtocol();

        final SabrMediaHeader header = protocol.decodeHeader(new byte[]{16, 42});

        assertEquals(42, header.getSequenceNumber());
        assertNull(header.getVideoId());
    }

    @Nonnull
    private static SabrSessionPolicy failingProfile() {
        return new SabrSessionPolicy() {
            @Nonnull
            @Override
            public Result evaluate(@Nonnull final State state, @Nonnull final Event event) {
                throw new UnsupportedOperationException();
            }

            @Nonnull
            @Override
            public SabrMediaProtocol getMediaProtocol() {
                return new SabrMediaProtocol() {
                    @Override public int getHeaderPartType() { return 20; }
                    @Override public int getMediaPartType() { return 21; }
                    @Override public int getEndPartType() { return 22; }

                    @Nonnull
                    @Override
                    public SabrMediaHeader decodeHeader(@Nonnull final byte[] payload)
                            throws SabrProtocolException {
                        throw new SabrProtocolException("broken profile media mapping");
                    }
                };
            }
        };
    }
}
