package org.schabi.newpipe.util.selection;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
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
public final class SelectionController<K extends Serializable> {

    public interface Listener {
        /** Called whenever the mode or the selected keys change. */
        void onSelectionChanged(@NonNull SelectionController<?> controller);
    }

    private static final String STATE_ACTIVE = "selection_active";
    private static final String STATE_KEYS = "selection_keys";

    private final LinkedHashSet<K> selectedKeys = new LinkedHashSet<>();
    private boolean active = false;
    @Nullable
    private Listener listener;

    public void setListener(@Nullable final Listener listener) {
        this.listener = listener;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isSelected(final K key) {
        return selectedKeys.contains(key);
    }

    public int getSelectedCount() {
        return selectedKeys.size();
    }

    @NonNull
    public Set<K> getSelectedKeys() {
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

    public void toggle(final K key) {
        if (!active) {
            return;
        }
        if (!selectedKeys.remove(key)) {
            selectedKeys.add(key);
        }
        notifyChanged();
    }

    /** Selects every key in {@code keys}, keeping the ones already selected. */
    public void selectAll(@NonNull final Collection<K> keys) {
        if (!active) {
            return;
        }
        selectedKeys.addAll(keys);
        notifyChanged();
    }

    /** Inverts the selection within {@code keys}; keys outside it are left untouched. */
    public void invert(@NonNull final Collection<K> keys) {
        if (!active) {
            return;
        }
        for (final K key : keys) {
            if (!selectedKeys.remove(key)) {
                selectedKeys.add(key);
            }
        }
        notifyChanged();
    }

    /** Drops selected keys that no longer exist, e.g. after the underlying data reloaded. */
    public void retainOnly(@NonNull final Collection<K> existingKeys) {
        if (selectedKeys.isEmpty()) {
            return;
        }
        final Set<K> existing = existingKeys instanceof Set
                ? (Set<K>) existingKeys : new HashSet<>(existingKeys);
        if (selectedKeys.retainAll(existing)) {
            notifyChanged();
        }
    }

    /** Replaces the selected keys, e.g. after the keys of the underlying data were renumbered. */
    public void setSelected(@NonNull final Collection<K> keys) {
        if (!active) {
            return;
        }
        selectedKeys.clear();
        selectedKeys.addAll(keys);
        notifyChanged();
    }

    public void saveState(@NonNull final Bundle outState) {
        outState.putBoolean(STATE_ACTIVE, active);
        outState.putSerializable(STATE_KEYS, new ArrayList<>(selectedKeys));
    }

    @SuppressWarnings("unchecked")
    public void restoreState(@Nullable final Bundle savedState) {
        if (savedState == null) {
            return;
        }
        active = savedState.getBoolean(STATE_ACTIVE, false);
        selectedKeys.clear();
        final Serializable keys = savedState.getSerializable(STATE_KEYS);
        if (active && keys instanceof ArrayList) {
            selectedKeys.addAll((ArrayList<K>) keys);
        }
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onSelectionChanged(this);
        }
    }
}
