package org.schabi.newpipe.player.playqueue;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.views.ItemDragTouchHelperCallback;

public abstract class PlayQueueItemTouchCallback extends ItemDragTouchHelperCallback {
    @Nullable private PendingViewportAnchor pendingViewportAnchor;

    public PlayQueueItemTouchCallback() {
        super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.RIGHT);
    }

    public abstract void onMove(int sourceIndex, int targetIndex);

    public abstract void onSwiped(int index);

    @Override
    public boolean onMove(final RecyclerView recyclerView, final RecyclerView.ViewHolder source,
                          final RecyclerView.ViewHolder target) {
        if (source.getItemViewType() != target.getItemViewType()) {
            return false;
        }

        final int sourceIndex = source.getBindingAdapterPosition();
        final int targetIndex = target.getBindingAdapterPosition();
        if (sourceIndex == RecyclerView.NO_POSITION || targetIndex == RecyclerView.NO_POSITION
                || sourceIndex == targetIndex) {
            return false;
        }

        if (pendingViewportAnchor != null && pendingViewportAnchor.isWaiting()) {
            // Binding positions still describe the previous order until the MOVE event arrives.
            return false;
        }
        clearPendingAnchor();
        final RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager && targetIndex > sourceIndex) {
            final LinearLayoutManager linearLayoutManager = (LinearLayoutManager) layoutManager;
            final int firstVisiblePosition =
                    linearLayoutManager.findFirstVisibleItemPosition();
            final View firstVisibleView =
                    linearLayoutManager.findViewByPosition(firstVisiblePosition);
            if (firstVisibleView != null) {
                // Preserve the current viewport slot, not the identity of the dragged holder.
                // Capture it anew for each swap so edge scrolling remains free to advance it.
                final RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                if (adapter != null) {
                    final int offset = linearLayoutManager.getDecoratedTop(firstVisibleView)
                            - ((RecyclerView.LayoutParams) firstVisibleView.getLayoutParams())
                                    .topMargin
                            - recyclerView.getPaddingTop();
                    pendingViewportAnchor = new PendingViewportAnchor(recyclerView,
                            linearLayoutManager, adapter, sourceIndex, targetIndex,
                            firstVisiblePosition, offset);
                }
            }
        }

        onMove(sourceIndex, targetIndex);
        return true;
    }

    @Override
    public void onSelectedChanged(final RecyclerView.ViewHolder viewHolder,
                                  final int actionState) {
        super.onSelectedChanged(viewHolder, actionState);
        clearPendingAnchor();
    }

    @Override
    public void onMoved(@NonNull final RecyclerView recyclerView,
                        @NonNull final RecyclerView.ViewHolder viewHolder,
                        final int fromPos,
                        @NonNull final RecyclerView.ViewHolder target,
                        final int toPos,
                        final int x,
                        final int y) {
        if (pendingViewportAnchor != null) {
            // The queue broadcasts asynchronously. Apply the anchor from the adapter notification,
            // otherwise a layout before that notification consumes it against the old item order.
            return;
        }

        super.onMoved(recyclerView, viewHolder, fromPos, target, toPos, x, y);
    }

    private void clearPendingAnchor() {
        if (pendingViewportAnchor != null) {
            pendingViewportAnchor.cancel();
            pendingViewportAnchor = null;
        }
    }

    private static final class PendingViewportAnchor extends RecyclerView.AdapterDataObserver {
        private final RecyclerView recycler;
        private final LinearLayoutManager layout;
        private final RecyclerView.Adapter<?> adapter;
        private final int from;
        private final int to;
        private final int position;
        private final int offset;
        private boolean waiting = true;

        PendingViewportAnchor(final RecyclerView recycler, final LinearLayoutManager layout,
                              final RecyclerView.Adapter<?> adapter, final int from, final int to,
                              final int position, final int offset) {
            this.recycler = recycler;
            this.layout = layout;
            this.adapter = adapter;
            this.from = from;
            this.to = to;
            this.position = position;
            this.offset = offset;
            adapter.registerAdapterDataObserver(this);
        }

        @Override
        public void onItemRangeMoved(final int fromPosition, final int toPosition,
                                     final int itemCount) {
            cancel();
            if (fromPosition == from && toPosition == to && itemCount == 1
                    && recycler.getAdapter() == adapter && recycler.getLayoutManager() == layout) {
                layout.scrollToPositionWithOffset(position, offset);
            }
        }

        @Override
        public void onChanged() {
            cancel();
        }

        @Override
        public void onItemRangeInserted(final int positionStart, final int itemCount) {
            cancel();
        }

        @Override
        public void onItemRangeRemoved(final int positionStart, final int itemCount) {
            cancel();
        }

        boolean isWaiting() {
            return waiting;
        }

        void cancel() {
            if (waiting) {
                waiting = false;
                adapter.unregisterAdapterDataObserver(this);
            }
        }
    }

    @Override
    public boolean isLongPressDragEnabled() {
        return false;
    }

    @Override
    public boolean isItemViewSwipeEnabled() {
        return true;
    }

    @Override
    public void onSwiped(final RecyclerView.ViewHolder viewHolder, final int swipeDir) {
        onSwiped(viewHolder.getBindingAdapterPosition());
    }
}
