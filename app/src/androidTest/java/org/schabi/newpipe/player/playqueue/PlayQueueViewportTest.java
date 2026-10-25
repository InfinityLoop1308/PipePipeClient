package org.schabi.newpipe.player.playqueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/** Real RecyclerView position mapping and layout, without network or player dependencies. */
@RunWith(AndroidJUnit4.class)
public class PlayQueueViewportTest {
    @Test
    public void deferredMoveNotificationPreservesAnchorAfterInterveningLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Fixture fixture = new Fixture(0, 0, true);
            fixture.scrollTo(0, 0);
            fixture.move(0, 1);
            // A real input frame can lay out before the queue's asynchronous MOVE event arrives.
            fixture.pendingMove.run();
            fixture.performLayout();
            assertEquals(0, fixture.layout.findFirstVisibleItemPosition());
            assertEquals(0, fixture.layout.findViewByPosition(0).getTop());
            assertEquals(Integer.valueOf(0), fixture.items.get(1));
        });
    }

    @Test
    public void anotherMoveWaitsForDeferredAdapterPositions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Fixture fixture = new Fixture(0, 0, true);
            fixture.scrollTo(0, 0);
            fixture.move(0, 1);
            assertFalse(fixture.callback.onMove(fixture.recycler,
                    fixture.holder(0), fixture.holder(1)));
            fixture.pendingMove.run();
            fixture.performLayout();
            fixture.move(1, 2);
            fixture.pendingMove.run();
            fixture.performLayout();
            assertEquals(0, fixture.layout.findFirstVisibleItemPosition());
            assertEquals(Integer.valueOf(0), fixture.items.get(2));
        });
    }

    @Test
    public void releaseCancelsDeferredAnchorAndAllowsFreshDrag() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Fixture fixture = new Fixture(0, 0, true);
            fixture.scrollTo(0, 0);
            fixture.move(0, 1);
            fixture.callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE);
            fixture.pendingMove.run();
            fixture.performLayout();
            assertEquals(1, fixture.layout.findFirstVisibleItemPosition());
            fixture.scrollTo(0, 0);
            fixture.callback.onSelectedChanged(fixture.holder(0), ItemTouchHelper.ACTION_STATE_DRAG);
            fixture.move(0, 1);
            fixture.pendingMove.run();
            fixture.performLayout();
            assertEquals(0, fixture.layout.findFirstVisibleItemPosition());
            assertEquals(Integer.valueOf(1), fixture.items.get(1));
        });
    }

    @Test
    public void downwardMovePreservesMarginsAndPadding() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Fixture fixture = new Fixture(9, 17);
            fixture.scrollTo(4, -20);
            final int top = fixture.layout.findViewByPosition(4).getTop();
            fixture.move(4, 5);
            assertEquals(4, fixture.layout.findFirstVisibleItemPosition());
            assertEquals(top, fixture.layout.findViewByPosition(4).getTop());
        });
    }

    @Test
    public void firstVisibleDownwardMoveKeepsViewportAndMovesItemExactlyOnce() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Fixture fixture = new Fixture();
            fixture.scrollTo(4, -20);
            fixture.move(4, 5);
            assertEquals(4, fixture.layout.findFirstVisibleItemPosition());
            assertEquals(-20, fixture.layout.findViewByPosition(4).getTop());
            assertEquals(Integer.valueOf(5), fixture.items.get(4));
            assertEquals(Integer.valueOf(4), fixture.items.get(5));
            assertEquals(100, fixture.layout.findViewByPosition(5).getTop());
            final List<RecyclerView.ViewHolder> candidates = new ArrayList<>();
            candidates.add(fixture.holder(6));
            // AndroidX's actual target predicate must not expose another swap at this pointer.
            assertNull(fixture.callback.chooseDropTarget(fixture.holder(5), candidates, 0, 101));
        });
    }

    @Test
    public void topReversalDoesNotUndoLaterEdgeScrollOrLeakIntoNewDrag() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Fixture fixture = new Fixture();
            fixture.callback.onSelectedChanged(fixture.holder(2),
                    ItemTouchHelper.ACTION_STATE_DRAG);
            fixture.move(2, 1);
            fixture.move(1, 0);
            fixture.move(0, 1);
            assertEquals(0, fixture.layout.findFirstVisibleItemPosition());
            assertEquals(Integer.valueOf(2), fixture.items.get(1));
            fixture.move(1, 2);
            fixture.move(2, 3);
            fixture.move(3, 4);
            // Same drag, after ItemTouchHelper's edge scrolling has advanced the viewport.
            fixture.scrollTo(2, -15);
            fixture.move(4, 5);
            assertEquals(2, fixture.layout.findFirstVisibleItemPosition());
            assertEquals(-15, fixture.layout.findViewByPosition(2).getTop());
            assertEquals(Integer.valueOf(2), fixture.items.get(5));
            fixture.callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE);
            fixture.scrollTo(8, -10);
            fixture.callback.onSelectedChanged(fixture.holder(8),
                    ItemTouchHelper.ACTION_STATE_DRAG);
            fixture.move(8, 9);
            assertEquals(8, fixture.layout.findFirstVisibleItemPosition());
            assertEquals(-10, fixture.layout.findViewByPosition(8).getTop());
            assertEquals(Integer.valueOf(8), fixture.items.get(9));
        });
    }

    private static final class Fixture {
        private final List<Integer> items = new ArrayList<>();
        private final RecyclerView recycler;
        private final LinearLayoutManager layout;
        private final PlayQueueItemTouchCallback callback;
        private Runnable pendingMove;

        Fixture() {
            this(0, 0);
        }

        Fixture(final int topMargin, final int paddingTop) {
            this(topMargin, paddingTop, false);
        }

        Fixture(final int topMargin, final int paddingTop, final boolean deferMoveNotification) {
            final Context context = InstrumentationRegistry.getInstrumentation()
                    .getTargetContext();
            recycler = new RecyclerView(context);
            recycler.setPadding(0, paddingTop, 0, 0);
            layout = new LinearLayoutManager(context);
            recycler.setLayoutManager(layout);
            recycler.setItemAnimator(null);
            for (int index = 0; index < 40; index++) {
                items.add(index);
            }
            final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter =
                    new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        @NonNull
                        @Override
                        public RecyclerView.ViewHolder onCreateViewHolder(
                                @NonNull final ViewGroup parent, final int viewType) {
                            final TextView row = new TextView(context);
                            final RecyclerView.LayoutParams params =
                                    new RecyclerView.LayoutParams(360, 120);
                            params.topMargin = topMargin;
                            row.setLayoutParams(params);
                            return new RecyclerView.ViewHolder(row) { };
                        }

                        @Override
                        public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder,
                                                     final int position) {
                            ((TextView) holder.itemView).setText(items.get(position).toString());
                        }

                        @Override
                        public int getItemCount() {
                            return items.size();
                        }
                    };
            recycler.setAdapter(adapter);
            callback = new PlayQueueItemTouchCallback() {
                @Override
                public void onMove(final int from, final int to) {
                    items.add(to, items.remove(from));
                    if (deferMoveNotification) {
                        pendingMove = () -> adapter.notifyItemMoved(from, to);
                    } else {
                        adapter.notifyItemMoved(from, to);
                    }
                }

                @Override
                public void onSwiped(final int index) { }
            };
            performLayout();
        }

        private RecyclerView.ViewHolder holder(final int position) {
            return recycler.findViewHolderForAdapterPosition(position);
        }

        private void move(final int from, final int to) {
            final RecyclerView.ViewHolder source = holder(from);
            final RecyclerView.ViewHolder target = holder(to);
            assertTrue(callback.onMove(recycler, source, target));
            callback.onMoved(recycler, source, from, target, to, 0, target.itemView.getTop());
            performLayout();
        }

        private void scrollTo(final int position, final int offset) {
            layout.scrollToPositionWithOffset(position, offset);
            performLayout();
        }

        private void performLayout() {
            recycler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            recycler.layout(0, 0, 360, 720);
        }
    }
}
