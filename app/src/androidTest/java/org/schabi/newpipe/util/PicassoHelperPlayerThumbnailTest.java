package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.squareup.picasso.RequestCreator;

import org.junit.Test;

import java.lang.reflect.Field;

public final class PicassoHelperPlayerThumbnailTest {
    @Test
    public void playerThumbnailRequestsUseCancelableTag() throws Exception {
        assertEquals(PicassoHelper.PLAYER_THUMBNAIL_TAG,
                requestTag(PicassoHelper.loadScaledDownThumbnail(
                        null, "https://example.com/player.jpg", true)));
        assertNull(requestTag(PicassoHelper.loadScaledDownThumbnail(
                null, "https://example.com/list.jpg", false)));
    }

    private static Object requestTag(final RequestCreator requestCreator) throws Exception {
        final Field tagField = RequestCreator.class.getDeclaredField("tag");
        tagField.setAccessible(true);
        return tagField.get(requestCreator);
    }
}
