package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrDecodedResponse;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrResponseDecoder;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrStreamingResponseReader;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class SabrProtocolRegressionTest {
    @Test
    public void malformedControlPartDoesNotDiscardResponse() throws Exception {
        // NEXT_REQUEST_POLICY containing protobuf tag 1 / invalid wire type 7.
        final SabrDecodedResponse decoded = SabrResponseDecoder.decode(
                new byte[]{35, 1, 0x0f, 30, 0});

        assertEquals(1, decoded.getMalformedParts().size());
        assertTrue(decoded.getMalformedParts().get(0).startsWith("35:1:"));
    }

    @Test
    public void malformedMediaHeaderBecomesRecoverableIntegrityFailure() throws Exception {
        final ByteArrayOutputStream ump = new ByteArrayOutputStream();
        writePart(ump, 20, new byte[]{0x0f});
        writePart(ump, 21, new byte[]{1, 10, 11});
        writePart(ump, 22, new byte[]{1});

        final SabrStreamingResponseReader.Result result = SabrStreamingResponseReader.read(
                new ByteArrayInputStream(ump.toByteArray()));

        assertEquals(1, result.getDecodedResponse().getMalformedParts().size());
        assertTrue(result.getDecodedResponse().getIntegrityIssues()
                .contains("media-without-header:1"));
        final Method recoverable = YoutubeSabrSession.class.getDeclaredMethod(
                "isRecoverableIncompleteMediaResponse", List.class);
        recoverable.setAccessible(true);
        assertTrue("malformed media header would terminate playback instead of retrying",
                (Boolean) recoverable.invoke(null,
                        result.getDecodedResponse().getIntegrityIssues()));
    }

    @Test
    public void streamingConsumerDoesNotRetainCompletedBatch() throws Exception {
        final ByteArrayOutputStream ump = new ByteArrayOutputStream();
        for (int id = 1; id <= 3; id++) {
            writePart(ump, 20, mediaHeader(id, id));
            writePart(ump, 21, new byte[]{(byte) id, 10, 11, 12, 13});
            writePart(ump, 22, new byte[]{(byte) id});
        }
        final AtomicInteger delivered = new AtomicInteger();

        final SabrStreamingResponseReader.Result result = SabrStreamingResponseReader.read(
                new ByteArrayInputStream(ump.toByteArray()), segment -> delivered.incrementAndGet());

        assertEquals(3, delivered.get());
        assertEquals(3, result.getSegmentCount());
        assertTrue("streaming production path retained the response batch",
                result.getSegments().isEmpty());
        assertTrue(result.getDecodedResponse().getIntegrityIssues().isEmpty());
    }

    private static byte[] mediaHeader(final int headerId, final int sequence) {
        return new byte[]{
                0x08, (byte) headerId,             // header id
                0x18, (byte) 0x8c, 0x01,           // itag 140
                0x48, (byte) sequence,             // sequence
                0x70, 0x04                         // content length
        };
    }

    private static void writePart(final ByteArrayOutputStream output,
                                  final int type,
                                  final byte[] payload) {
        if (type >= 128 || payload.length >= 128) {
            throw new IllegalArgumentException("test helper only supports one-byte UMP integers");
        }
        output.write(type);
        output.write(payload.length);
        output.write(payload, 0, payload.length);
    }
}
