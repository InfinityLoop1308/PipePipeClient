package org.schabi.newpipe.player.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.source.dash.DashSegmentIndex;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public final class SabrLongTimelineTest {
    private static final int REPORTED_SEGMENT_COUNT = 23_954;
    private static final int MAX_SIDX_SEGMENT_COUNT = 65_535;
    private static final int SEGMENT_DURATION_MS = 5_400;

    @Test
    public void generatedDashManifestPreservesLongSabrTimelines() throws Exception {
        assertManifestHasSegments(REPORTED_SEGMENT_COUNT);
        assertManifestHasSegments(MAX_SIDX_SEGMENT_COUNT);
    }

    private static void assertManifestHasSegments(final int segmentCount) throws Exception {
        final YoutubeSabrInfo.Format format = YoutubeSabrInfo.Format.fromParsedFormat(
                ItagItem.getItag(136), 0, null, "video/mp4", null, null,
                false, null, 0, 0);
        final YoutubeSabrFormatTimeline timeline =
                YoutubeSabrFormatTimeline.parse(format, createSidx(segmentCount));
        final String segmentTemplate = SabrDashMediaSource.segmentTemplate(format, timeline);
        final long durationMs = totalDurationMs(segmentCount);
        final String manifestXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" "
                + "mediaPresentationDuration=\"PT" + durationMs / 1000 + "."
                + String.format(java.util.Locale.US, "%03d", durationMs % 1000) + "S\">"
                + "<Period id=\"0\" start=\"PT0S\">"
                + "<AdaptationSet id=\"0\" contentType=\"video\" mimeType=\"video/mp4\">"
                + "<Representation id=\"136\" bandwidth=\"1000000\" width=\"1280\" "
                + "height=\"720\"><BaseURL>https://example.invalid/</BaseURL>"
                + segmentTemplate
                + "</Representation></AdaptationSet></Period></MPD>";

        final DashManifest manifest = new DashManifestParser().parse(
                Uri.parse("https://example.invalid/manifest.mpd"),
                new ByteArrayInputStream(manifestXml.getBytes(StandardCharsets.UTF_8)));
        final DashSegmentIndex index = manifest.getPeriod(0).adaptationSets.get(0)
                .representations.get(0).getIndex();

        assertNotNull(index);
        assertEquals(segmentCount, index.getSegmentCount(manifest.getPeriodDurationUs(0)));
        assertEquals(lastSegmentStartMs(segmentCount) * 1000,
                index.getTimeUs(segmentCount));
        assertEquals(segmentDurationMs(segmentCount, segmentCount - 1) * 1000,
                index.getDurationUs(segmentCount, manifest.getPeriodDurationUs(0)));
        if (segmentCount == REPORTED_SEGMENT_COUNT) {
            assertEquals(36L * 60 * 60 * 1000, durationMs);
        }
    }

    private static byte[] createSidx(final int segmentCount) {
        final ByteBuffer data = ByteBuffer.allocate(32 + segmentCount * 12)
                .order(ByteOrder.BIG_ENDIAN);
        data.putInt(data.capacity());
        data.put("sidx".getBytes(StandardCharsets.US_ASCII));
        data.putInt(0);
        data.putInt(1);
        data.putInt(1000);
        data.putInt(0);
        data.putInt(0);
        data.putShort((short) 0);
        data.putShort((short) segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            data.putInt(1);
            data.putInt(segmentDurationMs(segmentCount, i));
            data.putInt(0);
        }
        return data.array();
    }

    private static long totalDurationMs(final int segmentCount) {
        long durationMs = 0;
        for (int i = 0; i < segmentCount; i++) {
            durationMs += segmentDurationMs(segmentCount, i);
        }
        return durationMs;
    }

    private static long lastSegmentStartMs(final int segmentCount) {
        return totalDurationMs(segmentCount)
                - segmentDurationMs(segmentCount, segmentCount - 1);
    }

    private static int segmentDurationMs(final int segmentCount, final int index) {
        if (segmentCount == REPORTED_SEGMENT_COUNT) {
            return index < 8_860 ? 5_411 : 5_410;
        }
        return SEGMENT_DURATION_MS;
    }
}
