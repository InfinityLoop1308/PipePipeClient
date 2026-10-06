package org.schabi.newpipe.util.dearrow;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Re-binds the rows already on screen after an exclusion changes.
 *
 * <p>{@link DeArrowBinder} decides what a row shows only when the row is bound, so toggling
 * "Show original thumbnail" used to change nothing until the row was scrolled off screen and
 * back. Rebinding every list under the given view makes the change visible as soon as the
 * dialog closes. A whole channel can be excluded at once, so every row is rebound rather than
 * only the one that was long-pressed.</p>
 */
public final class DeArrowRowRefresher {

    /**
     * Sent as the change payload. With a payload RecyclerView rebinds each holder in place
     * instead of running a change animation, which swaps in a second holder and crashes on
     * adapters whose header row is one shared view ("Called attach on a child which is not
     * detached"). Adapters that act on their own payloads treat this one as a full rebind.
     */
    public static final Object REBIND = new Object();

    private DeArrowRowRefresher() {
    }

    public static void rebindVisibleRows(@Nullable final View root) {
        if (root instanceof RecyclerView) {
            final RecyclerView.Adapter<?> adapter = ((RecyclerView) root).getAdapter();
            if (adapter != null && adapter.getItemCount() > 0) {
                adapter.notifyItemRangeChanged(0, adapter.getItemCount(), REBIND);
            }
            // A list inside a list (a row hosting its own carousel) is reached by the outer
            // rebind, so there is no need to descend further.
            return;
        }
        if (root instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                rebindVisibleRows(group.getChildAt(i));
            }
        }
    }
}
