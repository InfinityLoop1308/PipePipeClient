package org.schabi.newpipe.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Keeps an active item drag within this view's vertical bounds.
 *
 * <p>{@link androidx.recyclerview.widget.ItemTouchHelper} ends a drag when its selected row is
 * detached. Without bounding the pointer, dragging beyond the list can move the row completely
 * outside the RecyclerView, where edge scrolling eventually detaches it. Bounding only the event
 * seen by the list keeps the row overlapping the edge while preserving edge scrolling.</p>
 */
public final class ItemDragRecyclerView extends RecyclerView {
    private boolean itemDragInProgress;

    public ItemDragRecyclerView(@NonNull final Context context) {
        super(context);
    }

    public ItemDragRecyclerView(@NonNull final Context context,
                                @Nullable final AttributeSet attrs) {
        super(context, attrs);
    }

    public ItemDragRecyclerView(@NonNull final Context context,
                                @Nullable final AttributeSet attrs,
                                final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    void setItemDragInProgress(final boolean itemDragInProgress) {
        this.itemDragInProgress = itemDragInProgress;
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent event) {
        if (!itemDragInProgress || event.getPointerCount() == 0) {
            return super.dispatchTouchEvent(event);
        }

        final float pointerY = event.getY();
        final float boundedPointerY = ItemDragScrollPolicy.clampPointerY(
                pointerY, getHeight(), getPaddingTop(), getPaddingBottom());
        if (pointerY == boundedPointerY) {
            return super.dispatchTouchEvent(event);
        }

        final MotionEvent boundedEvent = MotionEvent.obtain(event);
        boundedEvent.offsetLocation(0.0f, boundedPointerY - pointerY);
        try {
            return super.dispatchTouchEvent(boundedEvent);
        } finally {
            boundedEvent.recycle();
        }
    }
}
