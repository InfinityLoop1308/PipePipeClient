package org.schabi.newpipe.fragments.list.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.os.Bundle;
import android.os.Parcel;

import org.junit.Test;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.Collections;

public final class ChannelTabFragmentStateTest {
    @Test
    public void argumentsRestoreLinkHandlerAfterParcelRoundTrip() {
        final ListLinkHandler handler = new ListLinkHandler(
                "https://example.com/original",
                "https://example.com/tab",
                "channel-id",
                Collections.singletonList(new FilterItem(1, "videos")),
                Collections.singletonList(new FilterItem(2, "newest")));
        final ChannelTabFragment original = ChannelTabFragment.getInstance(
                7, handler, "Channel");
        final Parcel parcel = Parcel.obtain();
        final Bundle restoredArguments;
        try {
            parcel.writeBundle(original.getArguments());
            parcel.setDataPosition(0);
            restoredArguments = parcel.readBundle(ListLinkHandler.class.getClassLoader());
        } finally {
            parcel.recycle();
        }

        final ChannelTabFragment restored = new ChannelTabFragment();
        restored.setArguments(restoredArguments);
        restored.restoreFromArguments();

        assertNotNull(restored.tabHandler);
        assertEquals(handler.getUrl(), restored.tabHandler.getUrl());
        assertEquals(handler.getId(), restored.tabHandler.getId());
        assertEquals("videos", restored.tabHandler.getContentFilters().get(0).getName());
        assertEquals("newest", restored.tabHandler.getSortFilter().get(0).getName());
    }

    @Test
    public void missingLinkHandlerBecomesLoadErrorInsteadOfSynchronousCrash() {
        new ChannelTabFragment().loadResult(false).test()
                .assertError(IllegalStateException.class);
    }

    @Test
    public void legacyBundleDoesNotOverwriteHandlerRestoredFromStateSaver() {
        final ListLinkHandler restoredHandler = new ListLinkHandler(
                "https://example.com/original",
                "https://example.com/tab",
                "channel-id",
                Collections.emptyList(),
                null);
        final ChannelTabFragment fragment = new ChannelTabFragment();
        fragment.tabHandler = restoredHandler;

        fragment.onRestoreInstanceState(new Bundle());

        assertEquals(restoredHandler, fragment.tabHandler);
    }
}
