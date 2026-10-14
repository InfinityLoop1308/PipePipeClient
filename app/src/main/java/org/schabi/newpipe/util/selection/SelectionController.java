package org.schabi.newpipe.util.selection;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Adapter-agnostic multi-selection state.
 * <p>
 * Items are tracked by a stable key (e.g. a database id) instead of object identity or adapter
 * position, so the selection survives list reloads, sorting and filtering.
 * </p>
 */
public final class SelectionController {

    public interface Listener {
        /** Called whenever the mode or the selected keys change. */
        void onSelectionChanged(@NonNull SelectionController controller);
    }

    private static final String STATE_ACTIVE = "selection_active";
    private static final String STATE_KEYS = "selection_keys";

    private final LinkedHashSet<Long> selectedKeys = new LinkedHashSet<>();
    private boolean active = false;
    @Nullable
    private Listener listener;

    public void setListener(@Nullable final Listener listener) {
        this.listener = listener;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isSelected(final long key) {
        return selectedKeys.contains(key);
    }

    public int getSelectedCount() {
        return selectedKeys.size();
    }

    @NonNull
    public Set<Long> getSelectedKeys() {
        return new LinkedHashSet<>(selectedKeys);
    }

    public void enter() {
        if (active) {
            return;
        }
        active = true;
        selectedKeys.clear();
        notifyChanged();
    }

    public void exit() {
        if (!active) {
            return;
        }
        active = false;
        selectedKeys.clear();
        notifyChanged();
    }

    public void toggle(final long key) {
        if (!active) {
            return;
        }
        if (!selectedKeys.remove(key)) {
            selectedKeys.add(key);
        }
        notifyChanged();
    }

    /** Selects every key in {@code keys}, keeping the ones already selected. */
    public void selectAll(@NonNull final Collection<Long> keys) {
        if (!active) {
            return;
        }
        selectedKeys.addAll(keys);
        notifyChanged();
    }

    /** Inverts the selection within {@code keys}; keys outside it are left untouched. */
    public void invert(@NonNull final Collection<Long> keys) {
        if (!active) {
            return;
        }
        for (final Long key : keys) {
            if (!selectedKeys.remove(key)) {
                selectedKeys.add(key);
            }
        }
        notifyChanged();
    }

    /** Drops selected keys that no longer exist, e.g. after the underlying data reloaded. */
    public void retainOnly(@NonNull final Collection<Long> existingKeys) {
        if (selectedKeys.isEmpty()) {
            return;
        }
        final Set<Long> existing = existingKeys instanceof Set
                ? (Set<Long>) existingKeys : new HashSet<>(existingKeys);
        if (selectedKeys.retainAll(existing)) {
            notifyChanged();
        }
    }

    public void saveState(@NonNull final Bundle outState) {
        outState.putBoolean(STATE_ACTIVE, active);
        final long[] keys = new long[selectedKeys.size()];
        int i = 0;
        for (final Long key : selectedKeys) {
            keys[i++] = key;
        }
        outState.putLongArray(STATE_KEYS, keys);
    }

    public void restoreState(@Nullable final Bundle savedState) {
        if (savedState == null) {
            return;
        }
        active = savedState.getBoolean(STATE_ACTIVE, false);
        selectedKeys.clear();
        final long[] keys = savedState.getLongArray(STATE_KEYS);
        if (active && keys != null) {
            for (final long key : keys) {
                selectedKeys.add(key);
            }
        }
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onSelectionChanged(this);
        }
    }
}
