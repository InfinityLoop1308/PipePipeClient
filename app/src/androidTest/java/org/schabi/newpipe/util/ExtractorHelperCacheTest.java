package org.schabi.newpipe.util;

import static org.junit.Assert.assertSame;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;

@RunWith(AndroidJUnit4.class)
public final class ExtractorHelperCacheTest {
    private static final int YOUTUBE_SERVICE_ID = 0;
    private static final String REQUESTED_URL = "https://youtu.be/test-id";
    private static final String CANONICAL_URL = "https://www.youtube.com/watch?v=test-id";

    @After
    public void clearCache() {
        InfoCache.getInstance().clearCache();
    }

    @Test
    public void streamInfoIsCachedUnderRequestedAndCanonicalUrls() {
        final StreamInfo info = new StreamInfo(YOUTUBE_SERVICE_ID, "test-id",
                CANONICAL_URL, "test");

        ExtractorHelper.cacheInfo(YOUTUBE_SERVICE_ID, REQUESTED_URL, info,
                InfoItem.InfoType.STREAM);

        assertSame(info, InfoCache.getInstance().getFromKey(YOUTUBE_SERVICE_ID,
                REQUESTED_URL, InfoItem.InfoType.STREAM));
        assertSame(info, InfoCache.getInstance().getFromKey(YOUTUBE_SERVICE_ID,
                CANONICAL_URL, InfoItem.InfoType.STREAM));
    }
}
