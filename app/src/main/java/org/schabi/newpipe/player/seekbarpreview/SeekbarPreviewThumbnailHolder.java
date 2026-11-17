package org.schabi.newpipe.player.seekbarpreview;

import static org.schabi.newpipe.player.seekbarpreview.SeekbarPreviewThumbnailHelper.SeekbarPreviewThumbnailType;
import static org.schabi.newpipe.player.seekbarpreview.SeekbarPreviewThumbnailHelper.getSeekbarPreviewThumbnailType;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.Frameset;
import org.schabi.newpipe.util.PicassoHelper;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SeekbarPreviewThumbnailHolder {

    // This has to be <= 23 chars on devices running Android 7 or lower (API <= 25)
    // or it fails with an IllegalArgumentException
    // https://stackoverflow.com/a/54744028
    public static final String TAG = "SeekbarPrevThumbHolder";

    private static final int MAX_STORYBOARD_BITMAP_PAGES = 12;

    private final Object lock = new Object();
    // guarded by lock
    @Nullable
    private Frameset currentFrameset;
    private final Map<Integer, Bitmap> pages =
            new LinkedHashMap<Integer, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<Integer, Bitmap> eldest) {
                    return size() > MAX_STORYBOARD_BITMAP_PAGES;
                }
            };
    private int loadingPage = -1;
    // -1 = none; wantedPage is the latest one asked while another is loading
    private int wantedPage = -1;

    // one download at a time
    private final ExecutorService executor = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                final Thread thread = new Thread(runnable, "SeekbarPreviewLoader");
                thread.setDaemon(true);
                return thread;
            });

    public void resetFrom(@NonNull final Context context, final List<Frameset> framesets) {
        final int seekbarPreviewType = getSeekbarPreviewThumbnailType(context);
        final Frameset frameset = seekbarPreviewType == SeekbarPreviewThumbnailType.NONE
                ? null : getFrameSetForType(framesets, seekbarPreviewType);
        synchronized (lock) {
            currentFrameset = frameset;
            pages.clear();
            wantedPage = -1;
        }
    }

    @Nullable
    private Frameset getFrameSetForType(final List<Frameset> framesets,
                                        final int seekbarPreviewType) {
        if (framesets == null) {
            return null;
        }
        final Comparator<Frameset> bySize =
                Comparator.comparingInt(fs -> fs.getFrameHeight() * fs.getFrameWidth());
        return seekbarPreviewType == SeekbarPreviewThumbnailType.HIGH_QUALITY
                ? framesets.stream().max(bySize).orElse(null)
                : framesets.stream().min(bySize).orElse(null);
    }

    // never blocks: loads the page for the next call if it is not there yet
    public Optional<Bitmap> getBitmapAt(final int positionInMs) {
        final Frameset frameset;
        final int[] bounds;
        final Bitmap page;
        synchronized (lock) {
            frameset = currentFrameset;
            if (frameset == null || frameset.getUrls().isEmpty()) {
                return Optional.empty();
            }
            bounds = frameset.getFrameBoundsAt(positionInMs);
            final int pageIndex = Math.min(bounds[0], frameset.getUrls().size() - 1);
            page = pages.get(pageIndex);
            if (page == null) {
                requestPage(frameset, pageIndex);
                return Optional.empty();
            }
        }
        return Optional.ofNullable(cutFrame(page, bounds, frameset));
    }

    // called with lock held
    private void requestPage(@NonNull final Frameset frameset, final int pageIndex) {
        if (loadingPage == pageIndex) {
            return;
        }
        if (loadingPage != -1) {
            wantedPage = pageIndex;
            return;
        }
        loadingPage = pageIndex;
        try {
            executor.execute(() -> loadPages(frameset, pageIndex));
        } catch (final RuntimeException rejected) {
            loadingPage = -1;
        }
    }

    private void loadPages(@NonNull final Frameset frameset, final int firstPageIndex) {
        int pageIndex = firstPageIndex;
        while (pageIndex != -1) {
            final Bitmap bitmap = getBitMapFrom(frameset.getUrls().get(pageIndex));
            synchronized (lock) {
                if (bitmap != null && currentFrameset == frameset) {
                    pages.put(pageIndex, bitmap);
                }
                pageIndex = currentFrameset == frameset && wantedPage != pageIndex
                        && !pages.containsKey(wantedPage) ? wantedPage : -1;
                wantedPage = -1;
                loadingPage = pageIndex;
            }
        }
    }

    @Nullable
    private Bitmap cutFrame(@NonNull final Bitmap srcBitMap, final int[] bounds,
                            @NonNull final Frameset frameset) {
        if (srcBitMap.isRecycled()) {
            return null;
        }

        // Under some rare circumstances the YouTube API returns slightly too small storyboards,
        // (or not the matching frame width/height)
        // This would lead to createBitmap cutting out a bitmap that is out of bounds,
        // so we need to adjust the bounds accordingly
        final int left = Math.max(0, Math.min(bounds[1],
                srcBitMap.getWidth() - frameset.getFrameWidth()));
        final int top = Math.max(0, Math.min(bounds[2],
                srcBitMap.getHeight() - frameset.getFrameHeight()));
        final int width = Math.min(frameset.getFrameWidth(), srcBitMap.getWidth() - left);
        final int height = Math.min(frameset.getFrameHeight(), srcBitMap.getHeight() - top);
        if (width <= 0 || height <= 0) {
            return null;
        }

        final Bitmap cutOutBitmap = Bitmap.createBitmap(srcBitMap, left, top, width, height);

        // If the cut out bitmap is identical to its source,
        // we need to copy the bitmap to create a new instance.
        // createBitmap allows itself to return the original object that is was created with
        // this leads to recycled bitmaps being returned (if they are identical)
        // Reference: https://stackoverflow.com/a/23683075 + first comment
        // Fixes: https://github.com/TeamNewPipe/NewPipe/issues/11461
        return cutOutBitmap == srcBitMap
                ? cutOutBitmap.copy(cutOutBitmap.getConfig(), true) : cutOutBitmap;
    }

    @Nullable
    private Bitmap getBitMapFrom(final String url) {
        if (url == null) {
            Log.w(TAG, "url is null; This should never happen");
            return null;
        }
        try {
            // Gets the bitmap within the timeout of 15 seconds imposed by default by OkHttpClient
            return PicassoHelper.loadSeekbarThumbnailPreview(url).get();
        } catch (final Exception ex) {
            Log.w(TAG, "Failed to get bitmap for seekbarPreview from url='" + url
                    + "' in time", ex);
            return null;
        }
    }
}
