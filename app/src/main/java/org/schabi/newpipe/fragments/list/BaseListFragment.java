package org.schabi.newpipe.fragments.list;

import org.schabi.newpipe.extractor.utils.ServiceScopedId;
import static org.schabi.newpipe.ktx.ViewUtils.animate;
import static org.schabi.newpipe.ktx.ViewUtils.animateHideRecyclerViewAllowingScrolling;
import static org.schabi.newpipe.util.ThemeHelper.getGridSpanCountStreams;
import static org.schabi.newpipe.util.ThemeHelper.isGrid;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.BaseStateFragment;
import org.schabi.newpipe.fragments.OnScrollBelowItemsListener;
import org.schabi.newpipe.info_list.ItemViewMode;
import org.schabi.newpipe.info_list.dialog.InfoItemDialog;
import org.schabi.newpipe.info_list.InfoListAdapter;
import org.schabi.newpipe.settings.SettingsActivity;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.OnClickGesture;
import org.schabi.newpipe.util.StateSaver;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.util.ToolbarFilterHelper;
import org.schabi.newpipe.util.selection.SelectionController;
import org.schabi.newpipe.util.selection.SelectionToolbar;
import org.schabi.newpipe.util.selection.StreamSelectionActions;
import org.schabi.newpipe.views.SuperScrollLayoutManager;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;

public abstract class BaseListFragment<I, N> extends BaseStateFragment<I>
        implements ListViewContract<I, N>, StateSaver.WriteRead,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int LIST_MODE_UPDATE_FLAG = 0x32;
    protected org.schabi.newpipe.util.SavedState savedState;

    private boolean useDefaultStateSaving = true;
    private int updateFlags = 0;

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    protected InfoListAdapter infoListAdapter;
    protected RecyclerView itemsList;

    /** Multi-selection of streams; only usable if {@link #supportsMultiSelect()}. */
    protected final SelectionController<String> selection = new SelectionController<>();
    @Nullable
    private SelectionToolbar selectionToolbar;
    private final CompositeDisposable selectionDisposables = new CompositeDisposable();
    private static final String SELECTION_STATE_KEY = "list_selection";
    private int focusedPosition = -1;
    private int hiddenItemCount;
    private boolean manualFilterContinuation;
    /** True while the user's local toolbar filter has text; suspends automatic paging. */
    private boolean localFilterActive;
    private ToolbarFilterHelper localFilterHelper;

    /** Opens the toolbar filter field; every list's local search must go through here. */
    protected void openLocalFilter() {
        if (localFilterHelper == null) {
            localFilterHelper = new ToolbarFilterHelper(activity, text -> {
                onLocalFilterChanged(text);
                return kotlin.Unit.INSTANCE;
            });
        }
        localFilterHelper.open();
    }

    /** Closes the toolbar filter if open; returns true if it consumed the back press. */
    protected boolean closeLocalFilterOnBack() {
        if (localFilterHelper != null && localFilterHelper.isActive()) {
            localFilterHelper.close();
            return true;
        }
        return false;
    }

    /** Applies the toolbar's local filter text; no automatic loading happens while it is set. */
    protected void onLocalFilterChanged(@NonNull final String text) {
        localFilterActive = !text.trim().isEmpty();
        if (infoListAdapter != null) {
            infoListAdapter.filter(text);
        }
        if (localFilterActive) {
            installFilterFooter();
        } else if (hiddenItemCount == 0 && !manualFilterContinuation && infoListAdapter != null) {
            infoListAdapter.setFooterSupplier(null);
        }
        showListFooter(hasMoreItems());
        if (!localFilterActive && itemsList != null) {
            // resume paging if the unfiltered list doesn't fill the screen
            itemsList.post(() -> {
                if (itemsList != null && !localFilterActive && hasMoreItems()
                        && !isLoading.get() && !manualFilterContinuation
                        && !itemsList.canScrollVertically(1)) {
                    loadMoreItems();
                }
            });
        }
    }

    private int searchHiddenCount() {
        return localFilterActive && infoListAdapter != null
                ? infoListAdapter.getItemsList().size() - infoListAdapter.getShownCount() : 0;
    }

    /** A bounded empty-page batch must be resumed explicitly, never by the scroll listener. */
    protected void updateFilterStatus(final int count, final boolean emptyBatch,
                                      final boolean reset) {
        hiddenItemCount = reset ? count : hiddenItemCount + count;
        manualFilterContinuation = emptyBatch && hasMoreItems();
        if (hiddenItemCount == 0 && !manualFilterContinuation) {
            if (!localFilterActive) {
                infoListAdapter.setFooterSupplier(null);
            }
            showListFooter(hasMoreItems());
            return;
        }
        hideLoading();
        if (manualFilterContinuation) {
            useNormalItemListScrollListener();
        }
        installFilterFooter();
        showListFooter(true);
    }

    private boolean hasContentFilterNote() {
        return hiddenItemCount > 0 || manualFilterContinuation;
    }

    private void installFilterFooter() {
        infoListAdapter.setFooterSupplier(() -> {
            final View footer = getLayoutInflater().inflate(
                    R.layout.filter_list_footer, itemsList, false);
            final StringBuilder text = new StringBuilder();
            if (hasContentFilterNote()) {
                text.append(hiddenItemCount > 0
                        ? getString(infoListAdapter.getItemsList().isEmpty()
                                ? R.string.filter_all_items_hidden : R.string.filter_items_hidden,
                                hiddenItemCount)
                        : getString(R.string.filter_no_visible_items_yet));
            }
            final int mismatch = searchHiddenCount();
            if (mismatch > 0) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(getString(R.string.filter_search_mismatch, mismatch));
            }
            final TextView status = footer.findViewById(R.id.filter_status);
            status.setText(text);
            status.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
            final View more = footer.findViewById(R.id.filter_continue);
            more.setVisibility(manualFilterContinuation
                    || (localFilterActive && hasMoreItems()) ? View.VISIBLE : View.GONE);
            more.setOnClickListener(v -> {
                if (isLoading.get()) {
                    return;
                }
                // Keep automatic loading paused until the request completes.
                loadMoreItems();
            });
            final View settings = footer.findViewById(R.id.filter_settings);
            settings.setVisibility(hasContentFilterNote() ? View.VISIBLE : View.GONE);
            settings.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), SettingsActivity.class)
                            .putExtra(SettingsActivity.EXTRA_CONTENT_FILTER, true)));
            return footer;
        });
    }

    protected void resetFilterStatus() {
        hiddenItemCount = 0;
        manualFilterContinuation = false;
        if (infoListAdapter != null) {
            infoListAdapter.setFooterSupplier(null);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);

        if (infoListAdapter == null) {
            infoListAdapter = new InfoListAdapter(activity);
        }
    }

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
        if (useDefaultStateSaving) {
            StateSaver.onDestroy(savedState);
        }
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
        bindSelectionToolbar();
    }

    @Override
    public void onPause() {
        super.onPause();
        releaseSelectionToolbar();
    }

    @Override
    public void onDestroyView() {
        if (localFilterHelper != null) {
            localFilterHelper.close();
            localFilterHelper = null;
        }
        selection.setListener(null);
        if (infoListAdapter != null) {
            infoListAdapter.setSelectionState(null);
        }
        super.onDestroyView();
    }

    /**
     * Pages of a pager that are resumed but not shown must not own the shared selection toolbar.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void setUserVisibleHint(final boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (!isResumed()) {
            return;
        }
        if (isVisibleToUser) {
            bindSelectionToolbar();
        } else {
            selection.exit();
            releaseSelectionToolbar();
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * If the default implementation of {@link StateSaver.WriteRead} should be used.
     *
     * @param useDefaultStateSaving Whether the default implementation should be used
     * @see StateSaver
     */
    public void setUseDefaultStateSaving(final boolean useDefaultStateSaving) {
        this.useDefaultStateSaving = useDefaultStateSaving;
    }

    @Override
    public String generateSuffix() {
        // Naive solution, but it's good for now (the items don't change)
        return "." + infoListAdapter.getItemsList().size() + ".list";
    }

    private int getFocusedPosition() {
        try {
            final View focusedItem = itemsList.getFocusedChild();
            final RecyclerView.ViewHolder itemHolder =
                    itemsList.findContainingViewHolder(focusedItem);
            return itemHolder.getBindingAdapterPosition();
        } catch (final NullPointerException e) {
            return -1;
        }
    }

    @Override
    public void writeTo(final Queue<Object> objectsToSave) {
        if (!useDefaultStateSaving) {
            return;
        }

        objectsToSave.add(infoListAdapter.getItemsList());
        objectsToSave.add(getFocusedPosition());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void readFrom(@NonNull final Queue<Object> savedObjects) throws Exception {
        if (!useDefaultStateSaving) {
            return;
        }

        infoListAdapter.getItemsList().clear();
        infoListAdapter.getItemsList().addAll((List<InfoItem>) savedObjects.poll());
        restoreFocus((Integer) savedObjects.poll());
    }

    private void restoreFocus(final Integer position) {
        if (position == null || position < 0) {
            return;
        }

        itemsList.post(() -> {
            final RecyclerView.ViewHolder focusedHolder =
                    itemsList.findViewHolderForAdapterPosition(position);

            if (focusedHolder != null) {
                focusedHolder.itemView.requestFocus();
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle bundle) {
        super.onSaveInstanceState(bundle);
        if (supportsMultiSelect()) {
            final Bundle selectionState = new Bundle();
            selection.saveState(selectionState);
            bundle.putBundle(SELECTION_STATE_KEY, selectionState);
        }
        if (useDefaultStateSaving) {
            savedState = StateSaver
                    .tryToSave(activity.isChangingConfigurations(), savedState, bundle, this);
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull final Bundle bundle) {
        super.onRestoreInstanceState(bundle);
        selection.restoreState(bundle.getBundle(SELECTION_STATE_KEY));
        if (useDefaultStateSaving) {
            savedState = StateSaver.tryToRestore(bundle, this);
        }
    }

    @Override
    public void onStop() {
        focusedPosition = getFocusedPosition();
        super.onStop();
    }

    @Override
    public void onStart() {
        super.onStart();
        restoreFocus(focusedPosition);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    @Nullable
    protected Supplier<View> getListHeaderSupplier() {
        return null;
    }

    protected RecyclerView.LayoutManager getListLayoutManager() {
        return new SuperScrollLayoutManager(activity);
    }

    protected RecyclerView.LayoutManager getGridLayoutManager() {
        final int spanCount = getGridSpanCountStreams(activity);
        final GridLayoutManager lm = new GridLayoutManager(activity, spanCount);
        lm.setSpanSizeLookup(infoListAdapter.getSpanSizeLookup(spanCount));
        return lm;
    }

    /**
     * Updates the item view mode based on user preference.
     */
    private void refreshItemViewMode() {
        final ItemViewMode itemViewMode = getItemViewMode();
        itemsList.setLayoutManager((isGrid(itemViewMode))
                ? getGridLayoutManager() : getListLayoutManager());
        infoListAdapter.setItemViewMode(itemViewMode);
        infoListAdapter.notifyDataSetChanged();
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);

        itemsList = rootView.findViewById(R.id.items_list);
        refreshItemViewMode();

        final Supplier<View> listHeaderSupplier = getListHeaderSupplier();
        if (listHeaderSupplier != null) {
            infoListAdapter.setHeaderSupplier(listHeaderSupplier);
        }

        itemsList.setAdapter(infoListAdapter);

        if (supportsMultiSelect()) {
            infoListAdapter.setSelectionState(item -> {
                final String key = getSelectionKey(item);
                return key != null && selection.isSelected(key);
            });
            selection.setListener(controller -> {
                if (selectionToolbar != null) {
                    selectionToolbar.update(controller);
                }
                if (infoListAdapter != null) {
                    infoListAdapter.notifySelectionChanged();
                }
            });
        }
    }

    protected void onItemSelected(final InfoItem selectedItem) {
        if (DEBUG) {
            Log.d(TAG, "onItemSelected() called with: selectedItem = [" + selectedItem + "]");
        }
    }

    protected void onItemCallback(final InfoItem selectedItem) throws Exception {
        if (DEBUG) {
            Log.d(TAG, "onItemCallback() called with: selectedItem = [" + selectedItem + "]");
        }
    }

    @Override
    protected void initListeners() {
        super.initListeners();
        infoListAdapter.setOnStreamSelectedListener(new OnClickGesture<>() {
            @Override
            public void selected(final StreamInfoItem selectedItem) {
                if (selection.isActive()) {
                    selection.toggle(getSelectionKey(selectedItem));
                    return;
                }
                onStreamSelected(selectedItem);
            }

            @Override
            public void held(final StreamInfoItem selectedItem) {
                showInfoItemDialog(selectedItem);
            }
        });

        infoListAdapter.setOnChannelSelectedListener(new OnClickGesture<>() {
            @Override
            public void selected(final ChannelInfoItem selectedItem) {
                if (selection.isActive()) {
                    return;
                }
                try {
                    if (isChannelBlocked(selectedItem.getName(), selectedItem.getServiceId(),
                            selectedItem.getUploaderId())) {
                        Toast.makeText(requireContext(),
                                R.string.channel_is_blocked,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    onItemSelected(selectedItem);
                    NavigationHelper.openChannelFragment(getFM(),
                            selectedItem.getServiceId(),
                            selectedItem.getUrl(),
                            selectedItem.getName());
                } catch (final Exception e) {
                    ErrorUtil.showUiErrorSnackbar(
                            BaseListFragment.this, "Opening channel fragment", e);
                }
            }
        });

        infoListAdapter.setOnPlaylistSelectedListener(new OnClickGesture<>() {
            @Override
            public void selected(final PlaylistInfoItem selectedItem) {
                if (selection.isActive()) {
                    return;
                }
                try {
                    if (isChannelBlocked(selectedItem.getUploaderName(),
                            selectedItem.getServiceId(), selectedItem.getUploaderId())) {
                        Toast.makeText(requireContext(),
                                R.string.channel_is_blocked,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    onItemSelected(selectedItem);
                    NavigationHelper.openPlaylistFragment(getFM(),
                            selectedItem.getServiceId(),
                            selectedItem.getUrl(),
                            selectedItem.getName());
                } catch (final Exception e) {
                    ErrorUtil.showUiErrorSnackbar(BaseListFragment.this,
                            "Opening playlist fragment", e);
                }
            }
        });

        infoListAdapter.setOnCommentsSelectedListener(new OnClickGesture<>() {
            @Override
            public void selected(final CommentsInfoItem selectedItem) {
                onItemSelected(selectedItem);
            }
        });

        infoListAdapter.setOnCommentsReplyListener(
                new OnClickGesture<>() {
                    @Override
                    public void selected(final CommentsInfoItem selectedItem) {
                        try {
                            onItemCallback(selectedItem);
                        } catch (final Exception e) {
                            e.printStackTrace();
                        }
                    }
                });

        // Ensure that there is always a scroll listener (e.g. when rotating the device)
        useNormalItemListScrollListener();
    }

    /**
     * Removes all listeners and adds the normal scroll listener to the {@link #itemsList}.
     */
    protected void useNormalItemListScrollListener() {
        if (DEBUG) {
            Log.d(TAG, "useNormalItemListScrollListener called");
        }
        itemsList.clearOnScrollListeners();
        itemsList.addOnScrollListener(new DefaultItemListOnScrolledDownListener());
    }

    /**
     * Removes all listeners and adds the initial scroll listener to the {@link #itemsList}.
     * <br/>
     * Which tries to load more items when not enough are in the view (not scrollable)
     * and more are available.
     * <br/>
     * Note: This method only works because "This callback will also be called if visible
     * item range changes after a layout calculation. In that case, dx and dy will be 0."
     * - which might be unexpected because no actual scrolling occurs...
     * <br/>
     * This listener will be replaced by DefaultItemListOnScrolledDownListener when
     * <ul>
     *     <li>the view was actually scrolled</li>
     *     <li>the view is scrollable</li>
     *     <li>no more items can be loaded</li>
     * </ul>
     */
    protected void useInitialItemListLoadScrollListener() {
        if (DEBUG) {
            Log.d(TAG, "useInitialItemListLoadScrollListener called");
        }
        itemsList.clearOnScrollListeners();
        if(false){
            itemsList.addOnScrollListener(new InitialItemListOnScrolledDownListener() {
                @Override
                public void onScrollStateChanged(@NonNull @Nonnull RecyclerView recyclerView, int newState) {
                    if(recyclerView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.canScrollVertically(1)) { // RecyclerView is scrolled to the bottom }
                        onScrollToBottom();
                    }
                }
            });
        } else {
            itemsList.addOnScrollListener(new InitialItemListOnScrolledDownListener());
        }
    }

    class DefaultItemListOnScrolledDownListener extends OnScrollBelowItemsListener {
        @Override
        public void onScrolledDown(final RecyclerView recyclerView) {
            onScrollToBottom();
        }
    }

    class InitialItemListOnScrolledDownListener extends DefaultItemListOnScrolledDownListener {
        @Override
        public void onScrolled(@Nonnull final RecyclerView recyclerView, final int dx, final int dy) {
            super.onScrolled(recyclerView, dx, dy);
            if (manualFilterContinuation || localFilterActive) {
                return;
            }
            if (dy != 0) {
                log("Vertical scroll occurred");

                useNormalItemListScrollListener();
                return;
            }
            if (isLoading.get()) {
                log("Still loading data -> Skipping");
                return;
            }
            if (!hasMoreItems()) {
                log("No more items to load");

                useNormalItemListScrollListener();
                return;
            }
            if (itemsList.canScrollVertically(1)
                    || itemsList.canScrollVertically(-1)) {
                log("View is scrollable");

                useNormalItemListScrollListener();
                return;
            }

            log("Loading more data");
            loadMoreItems();
        }
        private void log(final String msg) {
            if (DEBUG) {
                Log.d(TAG, "initItemListLoadScrollListener - " + msg);
            }
        }
    }

    private void onStreamSelected(final StreamInfoItem selectedItem) {
        onItemSelected(selectedItem);
        NavigationHelper.openVideoDetailFragment(requireContext(), getFM(),
                selectedItem.getServiceId(), selectedItem.getUrl(), selectedItem.getName(),
                null, false);
    }

    protected void onScrollToBottom() {
        if (hasMoreItems() && !isLoading.get() && !manualFilterContinuation
                && !localFilterActive) {
            loadMoreItems();
        }
    }

    protected void showInfoItemDialog(final StreamInfoItem item) {
        try {
            new InfoItemDialog.Builder(getActivity(), getContext(), this, item).create().show();
        } catch (final IllegalArgumentException e) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(e, item);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: "
                    + "menu = [" + menu + "], inflater = [" + inflater + "]");
        }
        super.onCreateOptionsMenu(menu, inflater);
        final ActionBar supportActionBar = activity.getSupportActionBar();
        if (supportActionBar != null) {
            supportActionBar.setDisplayShowTitleEnabled(true);
            supportActionBar.setDisplayHomeAsUpEnabled(!useAsFrontPage);
        }
        if (supportsMultiSelect()) {
            inflater.inflate(R.menu.menu_multi_select, menu);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.action_multi_select && supportsMultiSelect()) {
            selection.enter();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Multi-selection
    //////////////////////////////////////////////////////////////////////////*/

    /** @return whether the stream items of this list can be multi-selected */
    protected boolean supportsMultiSelect() {
        return false;
    }

    @MenuRes
    protected int getSelectionMenu() {
        return R.menu.menu_stream_selection;
    }

    @Nullable
    private static String getSelectionKey(@Nullable final InfoItem item) {
        return item instanceof StreamInfoItem
                ? item.getServiceId() + ":" + item.getUrl() : null;
    }

    private void bindSelectionToolbar() {
        if (!supportsMultiSelect() || !getUserVisibleHint() || selectionToolbar != null) {
            return;
        }
        selectionToolbar = SelectionToolbar.bind(activity, getSelectionMenu(), selection,
                this::onSelectionAction);
    }

    private void releaseSelectionToolbar() {
        if (selectionToolbar != null) {
            selectionToolbar.release();
            selectionToolbar = null;
        }
    }

    @NonNull
    private List<StreamInfoItem> getStreamItems(final boolean onlySelected) {
        final List<StreamInfoItem> result = new ArrayList<>();
        if (infoListAdapter == null) {
            return result;
        }
        for (final InfoItem item : infoListAdapter.getItemsList()) {
            if (item instanceof StreamInfoItem
                    && (!onlySelected || selection.isSelected(getSelectionKey(item)))) {
                result.add((StreamInfoItem) item);
            }
        }
        return result;
    }

    @NonNull
    private List<String> getStreamKeys() {
        final List<String> keys = new ArrayList<>();
        for (final StreamInfoItem item : getStreamItems(false)) {
            keys.add(getSelectionKey(item));
        }
        return keys;
    }

    private boolean onSelectionAction(@NonNull final MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.action_selection_select_all) {
            selection.selectAll(getStreamKeys());
            return true;
        } else if (id == R.id.action_selection_invert) {
            selection.invert(getStreamKeys());
            return true;
        }
        final boolean handled = StreamSelectionActions.handle(this, selectionDisposables, item,
                StreamSelectionActions.toEntities(getStreamItems(true)));
        if (handled) {
            selection.exit();
        }
        return handled;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected void startLoading(final boolean forceLoad) {
        // the list is replaced, so the selected items would not exist anymore
        selection.exit();
        resetFilterStatus();
        useInitialItemListLoadScrollListener();
        super.startLoading(forceLoad);
    }

    protected abstract void loadMoreItems();

    protected abstract boolean hasMoreItems();

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void showLoading() {
        super.showLoading();
        animateHideRecyclerViewAllowingScrolling(itemsList);
    }

    @Override
    public void hideLoading() {
        super.hideLoading();
        animate(itemsList, true, 300);
    }

    @Override
    public void showEmptyState() {
        super.showEmptyState();
        showListFooter(false);
        animateHideRecyclerViewAllowingScrolling(itemsList);
    }

    @Override
    public void showListFooter(final boolean show) {
        itemsList.post(() -> {
            if (infoListAdapter != null && itemsList != null) {
                infoListAdapter.showFooter(show || hiddenItemCount > 0
                        || manualFilterContinuation
                        || (localFilterActive && (searchHiddenCount() > 0 || hasMoreItems())));
            }
        });
    }

    @Override
    public void handleNextItems(final N result) {
        isLoading.set(false);
    }

    @Override
    public void handleError() {
        super.handleError();
        showListFooter(false);
        animateHideRecyclerViewAllowingScrolling(itemsList);
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences,
                                          final String key) {
        try {
            if (key.equals(getString(R.string.list_view_mode_key))
                    || key.equals(getString(R.string.grid_layout_enabled_key))
                    || key.equals(getString(R.string.grid_columns_key))
                    || key.equals(getString(R.string.grid_columns_landscape_key))
                    || key.equals(getString(R.string.use_experimental_new_ui_key))) {
                updateFlags |= LIST_MODE_UPDATE_FLAG;
            }
        } catch (final Exception e) {
            // Ignore
        }
    }

    /**
     * Returns preferred item view mode.
     * @return ItemViewMode
     */
    protected ItemViewMode getItemViewMode() {
        return ThemeHelper.getItemViewMode(requireContext());
    }

    /**
     * Check if a channel name is in the user's block list.
     * @param channelName the channel name to check
     * @param serviceId the service the channel belongs to
     * @param channelId the channel id to check, may be null
     * @return true if the channel is blocked
     */
    protected boolean isChannelBlocked(@Nullable final String channelName,
                                       final int serviceId,
                                       @Nullable final String channelId) {
        final SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(requireContext());
        final String channelKey = ServiceScopedId.of(serviceId, channelId);
        if (channelKey != null
                && prefs.getStringSet(getString(R.string.filter_by_channel_id_key) + "_set",
                new HashSet<>()).contains(channelKey)) {
            return true;
        }
        if (channelName == null || channelName.isEmpty()) {
            return false;
        }
        return prefs.getStringSet(getString(R.string.filter_by_channel_key) + "_set",
                new HashSet<>()).contains(channelName);
    }
}
