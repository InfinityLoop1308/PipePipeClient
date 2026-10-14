package org.schabi.newpipe.local;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.databinding.PignateFooterBinding;
import org.schabi.newpipe.fragments.BaseStateFragment;
import org.schabi.newpipe.fragments.list.ListViewContract;
import org.schabi.newpipe.info_list.ItemViewMode;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.util.selection.SelectionController;
import org.schabi.newpipe.util.selection.SelectionToolbar;
import org.schabi.newpipe.util.selection.StreamSelectionActions;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

import static org.schabi.newpipe.ktx.ViewUtils.animate;
import static org.schabi.newpipe.ktx.ViewUtils.animateHideRecyclerViewAllowingScrolling;
import static org.schabi.newpipe.util.ThemeHelper.*;

/**
 * This fragment is design to be used with persistent data such as
 * {@link org.schabi.newpipe.database.LocalItem}, and does not cache the data contained
 * in the list adapter to avoid extra writes when the it exits or re-enters its lifecycle.
 * <p>
 * This fragment destroys its adapter and views when {@link Fragment#onDestroyView()} is
 * called and is memory efficient when in backstack.
 * </p>
 *
 * @param <I> List of {@link org.schabi.newpipe.database.LocalItem}s
 * @param <N> {@link Void}
 */
public abstract class BaseLocalListFragment<I, N> extends BaseStateFragment<I>
        implements ListViewContract<I, N>, SharedPreferences.OnSharedPreferenceChangeListener {

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    private static final int LIST_MODE_UPDATE_FLAG = 0x32;
    private static final String SELECTION_STATE_KEY = "local_list_selection";

    /** Multi-selection state; only used if {@link #getSelectionMenu()} is not 0. */
    protected final SelectionController<Long> selection = new SelectionController<>();
    @Nullable
    private SelectionToolbar selectionToolbar;
    private final CompositeDisposable selectionDisposables = new CompositeDisposable();
    private ViewBinding headerRootBinding;
    private ViewBinding footerRootBinding;
    protected LocalItemListAdapter itemListAdapter;
    protected RecyclerView itemsList;
    private int updateFlags = 0;

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle - Creation
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        PreferenceManager.getDefaultSharedPreferences(activity)
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        selectionDisposables.dispose();
        PreferenceManager.getDefaultSharedPreferences(activity)
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (updateFlags != 0) {
            if ((updateFlags & LIST_MODE_UPDATE_FLAG) != 0) {
                refreshItemViewMode();
            }
            updateFlags = 0;
        }
        if (getSelectionMenu() != 0) {
            // the selection toolbar is shared by the activity; only the visible page owns it
            selectionToolbar = SelectionToolbar.bind(activity, getSelectionMenu(), selection,
                    this::dispatchSelectionAction);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (selectionToolbar != null) {
            selectionToolbar.release();
            selectionToolbar = null;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        if (getSelectionMenu() != 0 && canPersistSelection()) {
            final Bundle selectionState = new Bundle();
            selection.saveState(selectionState);
            outState.putBundle(SELECTION_STATE_KEY, selectionState);
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        selection.restoreState(savedInstanceState.getBundle(SELECTION_STATE_KEY));
    }

    protected boolean forceUseListLayout() {
        return false;
    }

    /**
     * Updates the item view mode based on user preference.
     */
    private void refreshItemViewMode() {
        final ItemViewMode itemViewMode = getItemViewMode(requireContext());
        final ItemViewMode appliedItemViewMode = forceUseListLayout() ? ItemViewMode.LIST : itemViewMode;
        itemsList.setLayoutManager((isGrid(appliedItemViewMode))
                ? getGridLayoutManager() : getListLayoutManager());
        itemListAdapter.setItemViewMode(appliedItemViewMode);
        itemListAdapter.notifyDataSetChanged();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle - View
    //////////////////////////////////////////////////////////////////////////*/

    @Nullable
    protected ViewBinding getListHeader() {
        return null;
    }

    protected ViewBinding getListFooter() {
        return PignateFooterBinding.inflate(activity.getLayoutInflater(), itemsList, false);
    }

    protected RecyclerView.LayoutManager getGridLayoutManager() {
        final int spanCount = ThemeHelper.getGridSpanCountStreams(activity);
        final GridLayoutManager lm = new GridLayoutManager(activity, spanCount);
        lm.setSpanSizeLookup(itemListAdapter.getSpanSizeLookup(spanCount));
        return lm;
    }

    protected RecyclerView.LayoutManager getListLayoutManager() {
        return new LinearLayoutManager(activity);
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);

        itemListAdapter = new LocalItemListAdapter(activity);

        itemsList = rootView.findViewById(R.id.items_list);
        refreshItemViewMode();

        headerRootBinding = getListHeader();
        if (headerRootBinding != null) {
            itemListAdapter.setHeader(headerRootBinding.getRoot());
        }
        footerRootBinding = getListFooter();
        itemListAdapter.setFooter(footerRootBinding.getRoot());

        itemsList.setAdapter(itemListAdapter);

        if (getSelectionMenu() != 0) {
            itemListAdapter.setSelectionState(item -> {
                final Long key = getSelectionKey(item);
                return key != null && selection.isSelected(key);
            });
            selection.setListener(controller -> {
                if (selectionToolbar != null) {
                    selectionToolbar.update(controller);
                }
                if (itemListAdapter != null) {
                    itemListAdapter.notifySelectionChanged();
                }
                onSelectionChanged();
            });
        }
    }

    @Override
    protected void initListeners() {
        super.initListeners();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle - Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: "
                    + "menu = [" + menu + "], inflater = [" + inflater + "]");
        }

        final ActionBar supportActionBar = activity.getSupportActionBar();
        if (supportActionBar == null) {
            return;
        }

        supportActionBar.setDisplayShowTitleEnabled(true);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle - Destruction
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        selection.setListener(null);
        if (itemListAdapter != null) {
            itemListAdapter.setSelectionState(null);
        }
        itemsList = null;
        itemListAdapter = null;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Multi-selection
    //////////////////////////////////////////////////////////////////////////*/

    /** @return menu of the selection toolbar, or 0 if this list does not support selection */
    @MenuRes
    protected int getSelectionMenu() {
        return 0;
    }

    /** @return a key that stays stable across reloads, or null if the item is not selectable */
    @Nullable
    protected Long getSelectionKey(@NonNull final LocalItem item) {
        return null;
    }

    /** @return the stream of a selected item, or null if stream actions don't apply to it */
    @Nullable
    protected StreamEntity getSelectionStream(@NonNull final LocalItem item) {
        return null;
    }

    /** Handles list specific selection actions; common ones are handled by the base class. */
    protected boolean onSelectionAction(@NonNull final MenuItem item) {
        return false;
    }

    /** Called after the selection mode or selected items changed. */
    protected void onSelectionChanged() {
    }

    /** @return false if the saved keys could be stale after recreation */
    protected boolean canPersistSelection() {
        return true;
    }

    /**
     * Toggles the item if selection mode is active.
     *
     * @return true if the click was consumed by the selection mode
     */
    protected boolean toggleSelectionIfActive(@NonNull final LocalItem item) {
        if (!selection.isActive()) {
            return false;
        }
        final Long key = getSelectionKey(item);
        if (key != null) {
            selection.toggle(key);
        }
        return true;
    }

    /** @return true if selection mode was active and has been exited */
    protected boolean exitSelectionOnBack() {
        if (selection.isActive()) {
            selection.exit();
            return true;
        }
        return false;
    }

    /** Drops selected keys that are not in the adapter anymore. */
    protected void retainExistingSelection() {
        if (itemListAdapter != null) {
            selection.retainOnly(getSelectionKeys(itemListAdapter.getItemsList()));
        }
    }

    /** Selected items in displayed order, including ones hidden by the local filter. */
    @NonNull
    protected List<LocalItem> getSelectedItems() {
        final List<LocalItem> result = new ArrayList<>();
        if (itemListAdapter == null) {
            return result;
        }
        for (final LocalItem item : itemListAdapter.getItemsList()) {
            final Long key = getSelectionKey(item);
            if (key != null && selection.isSelected(key)) {
                result.add(item);
            }
        }
        return result;
    }

    @NonNull
    protected List<Long> getSelectionKeys(@NonNull final List<LocalItem> items) {
        final List<Long> keys = new ArrayList<>(items.size());
        for (final LocalItem item : items) {
            final Long key = getSelectionKey(item);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    @NonNull
    private List<StreamEntity> getSelectedStreams() {
        final List<StreamEntity> streams = new ArrayList<>();
        for (final LocalItem item : getSelectedItems()) {
            final StreamEntity stream = getSelectionStream(item);
            if (stream != null) {
                streams.add(stream);
            }
        }
        return streams;
    }

    private boolean dispatchSelectionAction(@NonNull final MenuItem item) {
        if (itemListAdapter == null) {
            return false;
        }
        if (onSelectionAction(item)) {
            return true;
        }
        final int id = item.getItemId();
        if (id == R.id.action_selection_select_all) {
            selection.selectAll(getSelectionKeys(itemListAdapter.getCurrentItemsList()));
        } else if (id == R.id.action_selection_invert) {
            selection.invert(getSelectionKeys(itemListAdapter.getCurrentItemsList()));
        } else {
            final boolean handled = StreamSelectionActions.handle(this, selectionDisposables,
                    item, getSelectedStreams());
            if (handled) {
                selection.exit();
            }
            return handled;
        }
        return true;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void startLoading(final boolean forceLoad) {
        super.startLoading(forceLoad);
        resetFragment();
    }

    @Override
    public void showLoading() {
        super.showLoading();
        if (itemsList != null) {
            animateHideRecyclerViewAllowingScrolling(itemsList);
        }
        if (headerRootBinding != null) {
            animate(headerRootBinding.getRoot(), false, 200);
        }
    }

    @Override
    public void hideLoading() {
        super.hideLoading();
        if (itemsList != null) {
            animate(itemsList, true, 200);
        }
        if (headerRootBinding != null) {
            animate(headerRootBinding.getRoot(), true, 200);
        }
    }

    @Override
    public void showEmptyState() {
        super.showEmptyState();
        showListFooter(false);
    }

    @Override
    public void showListFooter(final boolean show) {
        if (itemsList == null) {
            return;
        }
        itemsList.post(() -> {
            if (itemListAdapter != null) {
                itemListAdapter.showFooter(show);
            }
        });
    }

    @Override
    public void handleNextItems(final N result) {
        isLoading.set(false);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Error handling
    //////////////////////////////////////////////////////////////////////////*/

    protected void resetFragment() {
        if (itemListAdapter != null) {
            itemListAdapter.clearStreamItemList();
        }
    }

    @Override
    public void handleError() {
        super.handleError();
        resetFragment();

        showListFooter(false);

        if (itemsList != null) {
            animateHideRecyclerViewAllowingScrolling(itemsList);
        }
        if (headerRootBinding != null) {
            animate(headerRootBinding.getRoot(), false, 200);
        }
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences,
                                          final String key) {
        if (key != null && (key.equals(getString(R.string.list_view_mode_key))
                || key.equals(getString(R.string.grid_layout_enabled_key))
                || key.equals(getString(R.string.grid_columns_key))
                || key.equals(getString(R.string.grid_columns_landscape_key))
                || key.equals(getString(R.string.use_experimental_new_ui_key)))) {
            updateFlags |= LIST_MODE_UPDATE_FLAG;
        }
    }
}
