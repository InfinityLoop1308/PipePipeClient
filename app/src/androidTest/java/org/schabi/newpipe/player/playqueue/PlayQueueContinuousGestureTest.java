package org.schabi.newpipe.player.playqueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.views.ItemDragRecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Attached production rows, asynchronous queue events and uninterrupted native helper gestures. */
@RunWith(AndroidJUnit4.class)
public class PlayQueueContinuousGestureTest {
    @Test
    public void stationaryPointerAfterFirstVisibleSwapDoesNotCascade() {
        try (Fixture fixture = new Fixture()) {
            fixture.scrollTo(4);
            fixture.begin(4);
            final float destination = fixture.y + fixture.rowHeight * 1.1f;
            fixture.moveTo(destination);
            fixture.hold(60);
            fixture.main(() -> {
                assertEquals("One row crossed must produce exactly one reorder", 1, fixture.moves);
                assertEquals(4, fixture.layout.findFirstVisibleItemPosition());
                assertSame(fixture.dragged, fixture.queue.getItem(5));
                fixture.assertSelected();
            });
            fixture.release();
        }
    }

    @Test
    public void uninterruptedTopReversalAllowsEdgeScrollAndNextGestureResets() {
        try (Fixture fixture = new Fixture()) {
            fixture.scrollTo(0);
            fixture.begin(2);
            fixture.moveTo(fixture.rowHeight * 0.25f);
            fixture.hold(12);
            fixture.main(() -> {
                assertSame("The initial upward leg must reach the first slot",
                        fixture.dragged, fixture.queue.getItem(0));
                fixture.assertSelected();
            });
            fixture.moveTo(fixture.recycler.getHeight() + fixture.rowHeight);
            fixture.hold(180);
            fixture.main(() -> {
                assertTrue("Downward edge scrolling must advance beyond the top",
                        fixture.layout.findFirstVisibleItemPosition() > 0);
                assertTrue(fixture.queue.getStreams().indexOf(fixture.dragged) > 2);
                fixture.assertSelected();
            });
            fixture.release();
            fixture.scrollTo(8);
            fixture.begin(8);
            final int previousMoves = fixture.moves;
            fixture.moveTo(fixture.y + fixture.rowHeight * 1.1f);
            fixture.hold(30);
            fixture.main(() -> {
                assertEquals(previousMoves + 1, fixture.moves);
                assertEquals(8, fixture.layout.findFirstVisibleItemPosition());
                assertSame(fixture.dragged, fixture.queue.getItem(9));
                fixture.assertSelected();
            });
            fixture.release();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Instrumentation instrumentation =
                InstrumentationRegistry.getInstrumentation();
        private final Activity activity;
        private ItemDragRecyclerView recycler;
        private LinearLayoutManager layout;
        private PlayQueue queue;
        private PlayQueueAdapter adapter;
        private ItemTouchHelper helper;
        private RecyclerView.ViewHolder selected;
        private PlayerMediaItem dragged;
        private int moves;
        private float x;
        private float y;
        private int rowHeight;
        private long downTime;

        Fixture() {
            final Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = instrumentation.startActivitySync(intent);
            main(() -> {
                final List<StreamInfoItem> items = new ArrayList<>();
                for (int index = 0; index < 80; index++) {
                    final StreamInfoItem item = new StreamInfoItem(0,
                            "https://example.invalid/gesture/" + index,
                            "Gesture row " + index, StreamType.VIDEO_STREAM);
                    item.setUploaderName("Gesture fixture uploader");
                    item.setThumbnailUrl("");
                    items.add(item);
                }
                queue = new SinglePlayQueue(items, 0);
                queue.init();
                recycler = new ItemDragRecyclerView(activity);
                layout = new LinearLayoutManager(activity);
                recycler.setLayoutManager(layout);
                recycler.setItemAnimator(null);
                helper = new ItemTouchHelper(new PlayQueueItemTouchCallback() {
                    @Override
                    public void onMove(final int from, final int to) {
                        moves++;
                        queue.move(from, to);
                    }

                    @Override
                    public void onSwiped(final int index) {
                        throw new AssertionError("A vertical handle drag must not swipe a row");
                    }

                    @Override
                    public void onSelectedChanged(final RecyclerView.ViewHolder holder,
                                                  final int state) {
                        super.onSelectedChanged(holder, state);
                        selected = state == ItemTouchHelper.ACTION_STATE_DRAG ? holder : null;
                    }
                });
                helper.attachToRecyclerView(recycler);
                adapter = new PlayQueueAdapter(activity, queue);
                adapter.setSelectedListener(new PlayQueueItemBuilder.OnSelectedListener() {
                    @Override
                    public void selected(final PlayerMediaItem item, final View view) { }

                    @Override
                    public void held(final PlayerMediaItem item, final View view) { }

                    @Override
                    public void onStartDrag(final PlayQueueItemHolder holder) {
                        helper.startDrag(holder);
                    }
                });
                recycler.setAdapter(adapter);
                final FrameLayout overlay = new FrameLayout(activity);
                overlay.setBackgroundColor(0xff202020);
                overlay.addView(recycler, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                activity.addContentView(overlay, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            });
            frames(8);
        }

        void main(final Runnable action) {
            instrumentation.runOnMainSync(action);
        }

        void scrollTo(final int position) {
            main(() -> layout.scrollToPositionWithOffset(position, 0));
            frames(8);
            main(() -> assertEquals(position, layout.findFirstVisibleItemPosition()));
        }

        void begin(final int position) {
            main(() -> {
                final RecyclerView.ViewHolder holder =
                        recycler.findViewHolderForAdapterPosition(position);
                assertNotNull(holder);
                dragged = queue.getItem(position);
                rowHeight = holder.itemView.getHeight();
                assertTrue("Fixture needs room for multiple rows", recycler.getHeight() > 4 * rowHeight);
                final View handle = holder.itemView.findViewById(R.id.itemHandle);
                final Rect bounds = new Rect(0, 0, handle.getWidth(), handle.getHeight());
                recycler.offsetDescendantRectToMyCoords(handle, bounds);
                x = bounds.exactCenterX();
                y = bounds.exactCenterY();
                downTime = SystemClock.uptimeMillis();
                dispatch(MotionEvent.ACTION_DOWN);
                assertSelected();
            });
            frames(2);
        }

        void assertSelected() {
            assertNotNull("The same touch stream must still own a dragged row", selected);
            final int position = selected.getBindingAdapterPosition();
            assertTrue(position != RecyclerView.NO_POSITION);
            assertSame(dragged, queue.getItem(position));
        }

        void moveTo(final float destination) {
            final float start = y;
            final int steps = Math.max(1, (int) Math.ceil(Math.abs(destination - start)
                    / (rowHeight * 0.2f)));
            for (int step = 1; step <= steps; step++) {
                final float next = start + (destination - start) * step / steps;
                main(() -> {
                    y = next;
                    dispatch(MotionEvent.ACTION_MOVE);
                });
                frames(1);
            }
        }

        void hold(final int frameCount) {
            for (int frame = 0; frame < frameCount; frame++) {
                main(() -> dispatch(MotionEvent.ACTION_MOVE));
                frames(1);
            }
        }

        void release() {
            main(() -> {
                dispatch(MotionEvent.ACTION_UP);
                assertTrue("Release must clear the helper selection", selected == null);
            });
            frames(20);
        }

        private void dispatch(final int action) {
            final MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    action, x, y, 0);
            try {
                recycler.dispatchTouchEvent(event);
            } finally {
                event.recycle();
            }
        }

        private void frames(final int count) {
            for (int frame = 0; frame < count; frame++) {
                SystemClock.sleep(16);
                // Continuous edge scrolling schedules frames until the gesture ends.
                main(() -> { });
            }
        }

        @Override
        public void close() {
            main(() -> {
                if (selected != null) {
                    dispatch(MotionEvent.ACTION_CANCEL);
                }
                helper.attachToRecyclerView(null);
                recycler.setAdapter(null);
                adapter.dispose();
                queue.dispose();
                activity.finish();
            });
            instrumentation.waitForIdleSync();
        }
    }
}
