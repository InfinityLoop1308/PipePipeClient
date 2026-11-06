package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.NavigationHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Online full-player regression. Enable explicitly with -e queueActivityProbe true. */
@RunWith(AndroidJUnit4.class)
public class PlayQueueActivityGestureTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private PlayQueueActivity activity;
    private Activity mainActivity;
    private RecyclerView.Adapter<?> observedAdapter;
    private RecyclerView.AdapterDataObserver moveObserver;
    private RecyclerView.OnScrollListener scrollObserver;
    private boolean touching;
    private RecyclerView recycler;
    private LinearLayoutManager layout;
    private PlayQueue queue;
    private PlayerMediaItem dragged;
    private float x;
    private float y;
    private int rowHeight;
    private int moves;
    private long downTime;

    @Test
    public void firstRowInRunningQueue() {
        firstRowWithPlayingIndex(0);
    }

    @Test
    public void firstRowWhenAnotherItemIsPlaying() {
        firstRowWithPlayingIndex(1);
    }

    private void firstRowWithPlayingIndex(final int playingIndex) {
        Assume.assumeTrue("Online queue playback probe requires explicit opt-in",
                Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                        .getString("queueActivityProbe", "false")));
        final Intent mainIntent = new Intent(instrumentation.getTargetContext(), MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mainActivity = instrumentation.startActivitySync(mainIntent);
        instrumentation.runOnMainSync(() -> {
            final List<StreamInfoItem> items = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                final StreamInfoItem item = new StreamInfoItem(0,
                        "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
                        "Queue probe row " + i, StreamType.VIDEO_STREAM);
                item.setUploaderName("Queue probe uploader");
                item.setThumbnailUrl("");
                items.add(item);
            }
            NavigationHelper.playOnBackgroundPlayer(mainActivity,
                    new SinglePlayQueue(items, playingIndex), false);
        });
        SystemClock.sleep(1500);
        final Intent queueIntent = NavigationHelper.getPlayQueueActivityIntent(mainActivity);
        queueIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (PlayQueueActivity) instrumentation.startActivitySync(queueIntent);
        await(() -> activity.player != null && activity.player.getPlayQueue() != null
                && activity.player.getPlayQueueAdapter() != null, 15000, "Player service binding");
        await(() -> activity.player.isPlaying(), 60000, "Actual playback must start");
        instrumentation.runOnMainSync(() -> {
            queue = activity.player.getPlayQueue();
            assertEquals(80, queue.size());
            recycler = activity.findViewById(R.id.play_queue);
            layout = (LinearLayoutManager) recycler.getLayoutManager();
            observedAdapter = recycler.getAdapter();
            moveObserver = new RecyclerView.AdapterDataObserver() {
                @Override
                public void onItemRangeMoved(final int from, final int to, final int count) {
                    moves++;
                    android.util.Log.w("QueueActivityProbe", "MOVE " + from + "->" + to
                            + " first=" + layout.findFirstVisibleItemPosition());
                }
            };
            observedAdapter.registerAdapterDataObserver(moveObserver);
            scrollObserver = new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(final RecyclerView view, final int dx, final int dy) {
                    android.util.Log.w("QueueActivityProbe", "SCROLL dy=" + dy
                            + " first=" + layout.findFirstVisibleItemPosition());
                }
            };
            recycler.addOnScrollListener(scrollObserver);
            layout.scrollToPositionWithOffset(0, 0);
        });
        SystemClock.sleep(700);
        instrumentation.runOnMainSync(() -> {
            final RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(0);
            assertNotNull(holder);
            assertEquals("Playing-item precondition", playingIndex, queue.getIndex());
            dragged = queue.getItem(0);
            rowHeight = holder.itemView.getHeight();
            final View handle = holder.itemView.findViewById(R.id.itemHandle);
            final int[] screen = new int[2];
            handle.getLocationOnScreen(screen);
            x = screen[0] + handle.getWidth() / 2f;
            y = screen[1] + handle.getHeight() / 2f;
            android.util.Log.w("QueueActivityProbe", "BEGIN playing=" + activity.player.isPlaying()
                    + " row=" + rowHeight + " viewport=" + recycler.getHeight());
        });
        downTime = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, y);
        touching = true;
        SystemClock.sleep(80);
        for (int step = 1; step <= 12; step++) {
            touch(MotionEvent.ACTION_MOVE, y + rowHeight * 1.1f * step / 12f);
            SystemClock.sleep(16);
        }
        for (int frame = 0; frame < 60; frame++) {
            touch(MotionEvent.ACTION_MOVE, y + rowHeight * 1.1f);
            SystemClock.sleep(16);
        }
        instrumentation.runOnMainSync(() -> {
            android.util.Log.w("QueueActivityProbe", "FINAL moves=" + moves + " first="
                    + layout.findFirstVisibleItemPosition() + " position=" + queue.indexOf(dragged));
        });
        touch(MotionEvent.ACTION_UP, y + rowHeight * 1.1f);
        touching = false;
        instrumentation.runOnMainSync(() -> {
            assertEquals("One-row drag must make one move", 1, moves);
            assertEquals("Viewport must stay at top", 0, layout.findFirstVisibleItemPosition());
            assertSame(dragged, queue.getItem(1));
        });
    }

    @After
    public void cleanUp() {
        if (touching) {
            touch(MotionEvent.ACTION_CANCEL, y + rowHeight * 1.1f);
        }
        instrumentation.runOnMainSync(() -> {
            if (observedAdapter != null && moveObserver != null) {
                observedAdapter.unregisterAdapterDataObserver(moveObserver);
            }
            if (recycler != null && scrollObserver != null) {
                recycler.removeOnScrollListener(scrollObserver);
            }
            if (activity != null) {
                activity.finish();
            }
            if (mainActivity != null) {
                mainActivity.finish();
                instrumentation.getTargetContext().stopService(new Intent(
                        instrumentation.getTargetContext(), DeviceUtils.getPlayerServiceClass()));
            }
        });
        instrumentation.waitForIdleSync();
    }

    private void touch(final int action, final float pointerY) {
        final MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x,
                pointerY, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            instrumentation.sendPointerSync(event);
        } finally {
            event.recycle();
        }
    }

    private void await(final BooleanSupplier condition, final long timeout, final String message) {
        final long end = SystemClock.uptimeMillis() + timeout;
        final AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(100);
        }
        fail(message);
    }
}
