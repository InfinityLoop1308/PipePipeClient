package org.schabi.newpipe.util.selection;

import android.app.Activity;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import org.schabi.newpipe.R;

/**
 * Toolbar shown on top of the main toolbar while a list is in selection mode.
 * <p>
 * The view lives in the activity layout and is shared by all fragments, so a fragment should
 * bind it when it becomes the visible page ({@code onResume}) and {@link #release()} it when it
 * stops being visible ({@code onPause}).
 * </p>
 */
public final class SelectionToolbar {

    public interface OnActionListener {
        /** @return true if the action was handled */
        boolean onSelectionAction(@NonNull MenuItem item);
    }

    /** The toolbar currently owned by the visible page, used to route the back press. */
    @Nullable
    private static SelectionToolbar current;

    private final Toolbar toolbar;
    private final SelectionController<?> controller;

    private SelectionToolbar(@NonNull final Toolbar toolbar,
                             @MenuRes final int menuRes,
                             @NonNull final SelectionController<?> controller,
                             @NonNull final OnActionListener actionListener) {
        this.toolbar = toolbar;
        this.controller = controller;
        toolbar.getMenu().clear();
        toolbar.inflateMenu(menuRes);
        toolbar.setNavigationOnClickListener(v -> controller.exit());
        toolbar.setOnMenuItemClickListener(actionListener::onSelectionAction);
    }

    /** @return null if the activity has no selection toolbar */
    @Nullable
    public static SelectionToolbar bind(@Nullable final Activity activity,
                                        @MenuRes final int menuRes,
                                        @NonNull final SelectionController<?> controller,
                                        @NonNull final OnActionListener actionListener) {
        if (activity == null) {
            return null;
        }
        final Toolbar toolbar = activity.findViewById(R.id.selection_toolbar);
        if (toolbar == null) {
            return null;
        }
        final SelectionToolbar selectionToolbar =
                new SelectionToolbar(toolbar, menuRes, controller, actionListener);
        selectionToolbar.update(controller);
        current = selectionToolbar;
        return selectionToolbar;
    }

    /**
     * Exits the selection mode of the visible page, if any.
     *
     * @return true if the back press has been consumed
     */
    public static boolean onBackPressed() {
        if (current != null && current.controller.isActive()) {
            current.controller.exit();
            return true;
        }
        return false;
    }

    /** Syncs visibility, title and action availability with the controller state. */
    public void update(@NonNull final SelectionController<?> controller) {
        if (!controller.isActive()) {
            toolbar.setVisibility(View.GONE);
            return;
        }
        toolbar.setVisibility(View.VISIBLE);
        final int count = controller.getSelectedCount();
        toolbar.setTitle(toolbar.getResources()
                .getString(R.string.selection_count, count));
        for (int i = 0; i < toolbar.getMenu().size(); i++) {
            final MenuItem item = toolbar.getMenu().getItem(i);
            // Select all / invert are always usable; the rest needs at least one item
            final int id = item.getItemId();
            item.setEnabled(count > 0
                    || id == R.id.action_selection_select_all
                    || id == R.id.action_selection_invert);
        }
    }

    /** Hides the toolbar and detaches it from the owner; selection state is kept. */
    public void release() {
        if (current == this) {
            current = null;
        }
        toolbar.setVisibility(View.GONE);
        toolbar.setNavigationOnClickListener(null);
        toolbar.setOnMenuItemClickListener(null);
        toolbar.getMenu().clear();
    }
}
