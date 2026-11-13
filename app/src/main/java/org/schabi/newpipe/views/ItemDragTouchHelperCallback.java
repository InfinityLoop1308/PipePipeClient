package org.schabi.newpipe.views;

import android.view.Display;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

/** Applies the shared precise-to-expedited edge-scroll behavior to item dragging. */
public abstract class ItemDragTouchHelperCallback extends ItemTouchHelper.SimpleCallback {
    private final ItemDragScrollPolicy dragScrollPolicy = new ItemDragScrollPolicy();
    private ItemDragRecyclerView activeRecyclerView;

    protected ItemDragTouchHelperCallback(final int dragDirs, final int swipeDirs) {
        super(dragDirs, swipeDirs);
    }

    @Override
    public int interpolateOutOfBoundsScroll(final RecyclerView recyclerView,
                                            final int viewSize,
                                            final int viewSizeOutOfBounds,
                                            final int totalSize,
                                            final long msSinceStartScroll) {
        final long initialRampElapsedMs =
                dragScrollPolicy.initialRampElapsedForAndroidX(
                        msSinceStartScroll, Integer.signum(viewSizeOutOfBounds));
        final int standardSpeed = super.interpolateOutOfBoundsScroll(recyclerView, viewSize,
                viewSizeOutOfBounds, totalSize, initialRampElapsedMs);
        final Display display = recyclerView.getDisplay();
        final float refreshRate = display == null
                ? ItemDragScrollPolicy.DEFAULT_REFRESH_RATE_HZ
                : display.getRefreshRate();
        final RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        final int itemsPerRow = layoutManager instanceof GridLayoutManager
                ? Math.max(1, ((GridLayoutManager) layoutManager).getSpanCount())
                : 1;

        return dragScrollPolicy.applyScrollSpeed(
                standardSpeed,
                viewSize,
                itemsPerRow,
                refreshRate,
                msSinceStartScroll);
    }

    @Override
    public void onSelectedChanged(final RecyclerView.ViewHolder viewHolder,
                                  final int actionState) {
        super.onSelectedChanged(viewHolder, actionState);
        dragScrollPolicy.resetScroll();

        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null
                && viewHolder.itemView.getParent() instanceof ItemDragRecyclerView) {
            setActiveRecyclerView((ItemDragRecyclerView) viewHolder.itemView.getParent());
        } else {
            setActiveRecyclerView(null);
        }
    }

    private void setActiveRecyclerView(final ItemDragRecyclerView recyclerView) {
        if (activeRecyclerView != null) {
            activeRecyclerView.setItemDragInProgress(false);
        }

        activeRecyclerView = recyclerView;
        if (activeRecyclerView != null) {
            activeRecyclerView.setItemDragInProgress(true);
        }
    }
}
