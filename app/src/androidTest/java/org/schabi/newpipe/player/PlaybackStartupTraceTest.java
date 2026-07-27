package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PlaybackStartupTraceTest {
    @Test
    public void traceSurvivesIntentAndRecordsFirstFrame() {
        final String videoId = "video-id";
        final String url = "https://example.com/video-id";
        final long id = PlaybackStartupTrace.begin(videoId, url);
        final Intent intent = new Intent();

        PlaybackStartupTrace.attach(intent, id);
        assertEquals(id, PlaybackStartupTrace.fromIntent(intent));
        PlaybackStartupTrace.markForUrl(url, "stream_info_ready");
        PlaybackStartupTrace.markForVideoId(videoId, "source_ready");
        PlaybackStartupTrace.finish(id);

        final PlaybackStartupTrace.Snapshot snapshot = PlaybackStartupTrace.snapshot(id);
        assertNotNull(snapshot);
        assertTrue(snapshot.finished);
        assertTrue(snapshot.elapsedMs.containsKey("detail_click"));
        assertTrue(snapshot.elapsedMs.containsKey("intent_created"));
        assertTrue(snapshot.elapsedMs.containsKey("stream_info_ready"));
        assertTrue(snapshot.elapsedMs.containsKey("source_ready"));
        assertTrue(snapshot.elapsedMs.containsKey("first_frame"));
    }
}
