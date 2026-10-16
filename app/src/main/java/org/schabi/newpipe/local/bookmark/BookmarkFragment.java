package org.schabi.newpipe.local.bookmark;

import static org.schabi.newpipe.util.ThemeHelper.shouldUseGridLayout;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.InputType;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.playlist.PlaylistLocalItem;
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry;
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity;
import org.schabi.newpipe.databinding.DialogEditTextBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.fragments.BackPressable;
import org.schabi.newpipe.local.BaseLocalListFragment;
import org.schabi.newpipe.local.holder.LocalBookmarkPlaylistItemHolder;
import org.schabi.newpipe.local.holder.RemoteBookmarkPlaylistItemHolder;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;
import org.schabi.newpipe.local.playlist.RemotePlaylistManager;
import org.schabi.newpipe.util.DebounceSavable;
import org.schabi.newpipe.util.DebounceSaver;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.OnClickGesture;
import org.schabi.newpipe.util.SortMode;
import org.schabi.newpipe.util.utils;
import org.schabi.newpipe.util.ToolbarFilterHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;

public final class BookmarkFragment extends BaseLocalListFragment<List<PlaylistLocalItem>, Void>
        implements DebounceSavable, BackPressable {

    private static final int MINIMUM_INITIAL_DRAG_VELOCITY = 12;
    protected Parcelable itemsListState;

    private Subscription databaseSubscription;
    private CompositeDisposable disposables = new CompositeDisposable();
    private LocalPlaylistManager localPlaylistManager;
    private RemotePlaylistManager remotePlaylistManager;
    private ItemTouchHelper itemTouchHelper;

    /* Have the bookmarked playlists been fully loaded from db */
    private AtomicBoolean isLoadingComplete;

    private DebounceSaver debounceSaver;

    private ToolbarFilterHelper filterHelper;

    private SortMode sortMode = SortMode.ORIGIN;

    // Map from (uid, local/remote item) to the saved display index in the database.
    private Map<Pair<Long, LocalItem.LocalItemType>, Long> displayIndexInDatabase;

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Creation
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (activity == null) {
            return;
        }
        final AppDatabase database = NewPipeDatabase.getInstance(activity);
        localPlaylistManager = new LocalPlaylistManager(database);
        remotePlaylistManager = new RemotePlaylistManager(database);
        disposables = new CompositeDisposable();

        isLoadingComplete = new AtomicBoolean();
        debounceSaver = new DebounceSaver(10000, this);

        displayIndexInDatabase = new HashMap<>();

        sortMode = utils.parseSortMode(PreferenceManager.getDefaultSharedPreferences(activity)
                .getString(getString(R.string.bookmark_sort_mode_key), SortMode.ORIGIN.name()));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             final Bundle savedInstanceState) {

        if (!useAsFrontPage) {
            setTitle(activity.getString(R.string.tab_bookmarks));
        }
        return inflater.inflate(R.layout.fragment_bookmarks, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (activity != null) {
            setTitle(activity.getString(R.string.tab_bookmarks));
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Views
    ///////////////////////////////////////////////////////////////////////////

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);

        itemListAdapter.setUseItemHandle(true);
    }

    @Override
    protected void initListeners() {
        super.initListeners();

        itemTouchHelper = new ItemTouchHelper(getItemTouchCallback());
        itemTouchHelper.attachToRecyclerView(itemsList);

        itemListAdapter.setSelectedListener(new OnClickGesture<LocalItem>() {
            @Override
            public void selected(final LocalItem selectedItem) {
                if (toggleSelectionIfActive(selectedItem)) {
                    return;
                }
                final FragmentManager fragmentManager = getFM();

                if (selectedItem instanceof PlaylistMetadataEntry) {
                    final PlaylistMetadataEntry entry = ((PlaylistMetadataEntry) selectedItem);
                    NavigationHelper.openLocalPlaylistFragment(fragmentManager, entry.uid,
                            entry.name);

                } else if (selectedItem instanceof PlaylistRemoteEntity) {
                    final PlaylistRemoteEntity entry = ((PlaylistRemoteEntity) selectedItem);
                    NavigationHelper.openPlaylistFragment(
                            fragmentManager,
                            entry.getServiceId(),
                            entry.getUrl(),
                            entry.getName());
                }
            }

            @Override
            public void held(final LocalItem selectedItem) {
                if (selectedItem instanceof PlaylistMetadataEntry) {
                    showLocalDialog((PlaylistMetadataEntry) selectedItem);
                } else if (selectedItem instanceof PlaylistRemoteEntity) {
                    showRemoteDeleteDialog((PlaylistRemoteEntity) selectedItem);
                }
            }

            @Override
            public void drag(final LocalItem selectedItem,
                             final RecyclerView.ViewHolder viewHolder) {
                // reordering while selecting is not supported
                if (itemTouchHelper != null && !selection.isActive()) {
                    itemTouchHelper.startDrag(viewHolder);
                }
            }
        });
    }

    @Override
    protected boolean forceUseListLayout() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        return prefs.getBoolean(getString(R.string.always_list_mode_for_bookmark_key), false);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Loading
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void startLoading(final boolean forceLoad) {
        super.startLoading(forceLoad);

        if (debounceSaver != null) {
            disposables.add(debounceSaver.getDebouncedSaver());
            debounceSaver.setIsModified(false);
        }
        isLoadingComplete.set(false);

        Flowable.combineLatest(localPlaylistManager.getPlaylists(),
                remotePlaylistManager.getPlaylists(), PlaylistLocalItem::merge)
                .onBackpressureLatest()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(getPlaylistsSubscriber());
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Destruction
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void onPause() {
        super.onPause();
        itemsListState = itemsList.getLayoutManager().onSaveInstanceState();

        // Save on exit
        saveImmediate();
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable("itemsListState", itemsListState);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        itemsListState = savedInstanceState.getParcelable("itemsListState");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (filterHelper != null) {
            filterHelper.close();
            filterHelper = null;
        }

        if (disposables != null) {
            disposables.clear();
        }
        if (databaseSubscription != null) {
            databaseSubscription.cancel();
        }

        databaseSubscription = null;
        itemTouchHelper = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (debounceSaver != null) {
            debounceSaver.getDebouncedSaveSignal().onComplete();
        }
        if (disposables != null) {
            disposables.dispose();
        }

        debounceSaver = null;
        disposables = null;
        localPlaylistManager = null;
        remotePlaylistManager = null;
        itemsListState = null;

        isLoadingComplete = null;
        displayIndexInDatabase = null;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Subscriptions Loader
    ///////////////////////////////////////////////////////////////////////////

    private Subscriber<List<PlaylistLocalItem>> getPlaylistsSubscriber() {
        return new Subscriber<List<PlaylistLocalItem>>() {
            @Override
            public void onSubscribe(final Subscription s) {
                showLoading();
                isLoadingComplete.set(false);

                if (databaseSubscription != null) {
                    databaseSubscription.cancel();
                }
                databaseSubscription = s;
                databaseSubscription.request(1);
            }

            @Override
            public void onNext(final List<PlaylistLocalItem> subscriptions) {
                if (debounceSaver == null || !debounceSaver.getIsModified()) {
                    checkDisplayIndexModified(subscriptions);
                    handleResult(subscriptions);
                    isLoadingComplete.set(true);
                }
                if (databaseSubscription != null) {
                    databaseSubscription.request(1);
                }
            }

            @Override
            public void onError(final Throwable exception) {
                showError(new ErrorInfo(exception,
                        UserAction.REQUESTED_BOOKMARK, "Loading playlists"));
            }

            @Override
            public void onComplete() {
                // Do nothing.
            }
        };
    }

    @Override
    public void handleResult(@NonNull final List<PlaylistLocalItem> result) {
        super.handleResult(result);

        itemListAdapter.clearStreamItemList();

        if (result.isEmpty()) {
            showEmptyState();
            return;
        }

        itemListAdapter.addItems(sorted(result, sortMode));
        retainExistingSelection();
        if (itemsListState != null) {
            itemsList.getLayoutManager().onRestoreInstanceState(itemsListState);
            itemsListState = null;
        }
        hideLoading();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Multi-selection
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_bookmark, menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.action_multi_select) {
            selection.enter();
            return true;
        } else if (item.getItemId() == R.id.menu_item_sort) {
            showSortDialog();
            return true;
        } else if (item.getItemId() == R.id.action_search_local) {
            if (filterHelper == null) {
                filterHelper = new ToolbarFilterHelper(activity, text -> {
                    if (itemListAdapter != null) {
                        itemListAdapter.filter(text);
                    }
                    return kotlin.Unit.INSTANCE;
                });
            }
            filterHelper.open();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSortDialog() {
        final SortMode[] modes = {SortMode.ORIGIN, SortMode.ORIGIN_REVERSE,
                SortMode.SORT_NAME, SortMode.SORT_NAME_REVERSE};
        final String[] items = {getString(R.string.sort_origin),
                getString(R.string.sort_origin_reverse),
                getString(R.string.sort_name_ascending),
                getString(R.string.sort_name_descending)};
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.sort)
                .setSingleChoiceItems(items, java.util.Arrays.asList(modes).indexOf(sortMode),
                        (dialog, which) -> {
                            changeSortMode(modes[which]);
                            dialog.dismiss();
                        })
                .show();
    }

    private boolean isOriginMode() {
        return sortMode == SortMode.ORIGIN || sortMode == SortMode.ORIGIN_REVERSE;
    }

    /** Sorts a copy of the items; the display index always holds the "original" position. */
    private List<PlaylistLocalItem> sorted(final List<? extends LocalItem> items,
                                           final SortMode mode) {
        final List<PlaylistLocalItem> list = new ArrayList<>();
        for (final LocalItem item : items) {
            if (item instanceof PlaylistLocalItem) {
                list.add((PlaylistLocalItem) item);
            }
        }
        final Comparator<PlaylistLocalItem> byName = (a, b) -> utils.compareNames(
                a.getOrderingName() == null ? "" : a.getOrderingName(),
                b.getOrderingName() == null ? "" : b.getOrderingName());
        switch (mode) {
            case SORT_NAME:
                Collections.sort(list, byName);
                break;
            case SORT_NAME_REVERSE:
                Collections.sort(list, byName.reversed());
                break;
            case ORIGIN_REVERSE:
                Collections.sort(list,
                        Comparator.comparingLong(PlaylistLocalItem::getDisplayIndex).reversed());
                break;
            default:
                Collections.sort(list, Comparator.comparingLong(PlaylistLocalItem::getDisplayIndex));
                break;
        }
        return list;
    }

    private void changeSortMode(final SortMode newMode) {
        if (itemListAdapter == null || newMode == sortMode) {
            return;
        }
        // make the display index of every item up to date before the order is changed
        if (debounceSaver != null && debounceSaver.getIsModified()) {
            saveImmediate();
        }
        sortMode = newMode;
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                .putString(getString(R.string.bookmark_sort_mode_key), newMode.name()).apply();

        final List<PlaylistLocalItem> items = sorted(itemListAdapter.getItemsList(), newMode);
        itemListAdapter.clearStreamItemList();
        itemListAdapter.addItems(items);
    }

    @Override
    public boolean onBackPressed() {
        if (exitSelectionOnBack()) {
            return true;
        }
        if (filterHelper != null && filterHelper.isActive()) {
            filterHelper.close();
            return true;
        }
        return false;
    }

    @Override
    protected int getSelectionMenu() {
        return R.menu.menu_bookmark_selection;
    }

    /** Local and remote playlists have independent uids, so the type is part of the key. */
    @Nullable
    @Override
    protected Long getSelectionKey(@NonNull final LocalItem item) {
        if (item instanceof PlaylistMetadataEntry) {
            return ((PlaylistMetadataEntry) item).uid * 2;
        } else if (item instanceof PlaylistRemoteEntity) {
            return ((PlaylistRemoteEntity) item).getUid() * 2 + 1;
        }
        return null;
    }

    @Override
    protected boolean onSelectionAction(@NonNull final MenuItem item) {
        if (item.getItemId() != R.id.action_selection_delete) {
            return false;
        }
        final int count = selection.getSelectedCount();
        if (count > 0) {
            new AlertDialog.Builder(requireContext())
                    .setMessage(getResources().getString(R.string.delete_selected_playlists_confirm, count))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (d, w) -> deleteSelected())
                    .show();
        }
        return true;
    }

    private void deleteSelected() {
        if (itemListAdapter == null || debounceSaver == null) {
            return;
        }
        final List<LocalItem> items = getSelectedItems();
        if (items.isEmpty()) {
            return;
        }
        selection.exit();
        itemListAdapter.removeItems(items);
        if (itemListAdapter.getItemsList().isEmpty()) {
            showEmptyState();
        }
        debounceSaver.saveChanges();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Error Handling
    ///////////////////////////////////////////////////////////////////////////

    @Override
    protected void resetFragment() {
        super.resetFragment();
        if (disposables != null) {
            disposables.clear();
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Playlist Metadata Manipulation
    //////////////////////////////////////////////////////////////////////////*/

    private void changeLocalPlaylistName(final long id, final String name) {
        if (localPlaylistManager == null) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Updating playlist id=[" + id + "] "
                    + "with new name=[" + name + "] items");
        }

        final Disposable disposable = localPlaylistManager.renamePlaylist(id, name)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(longs -> { /*Do nothing on success*/ }, throwable -> showError(
                        new ErrorInfo(throwable,
                                UserAction.REQUESTED_BOOKMARK,
                                "Changing playlist name")));
        disposables.add(disposable);
    }

    private void deleteItem(final PlaylistLocalItem item) {
        if (itemListAdapter == null) {
            return;
        }
        itemListAdapter.removeItem(item);

        debounceSaver.saveChanges();
    }

    private void checkDisplayIndexModified(@NonNull final List<PlaylistLocalItem> result) {
        if (debounceSaver != null && debounceSaver.getIsModified()) {
            return;
        }

        displayIndexInDatabase.clear();

        // If the display index does not match actual index in the list, update the display index.
        // This may happen when a new list is created
        // or on the first run after database migration
        // or display index is not continuous for some reason
        // or the user changes the display index.
        boolean isDisplayIndexModified = false;
        for (int i = 0; i < result.size(); i++) {
            final PlaylistLocalItem item = result.get(i);
            if (item.getDisplayIndex() != i) {
                isDisplayIndexModified = true;
            }

            // Updating display index in the item does not affect the value inserts into
            // database, which will be recalculated during the database update. Updating
            // display index in the item here is to determine whether it is recently modified.
            // Save the index read from the database.
            if (item instanceof PlaylistMetadataEntry) {

                displayIndexInDatabase.put(new Pair<>(((PlaylistMetadataEntry) item).uid,
                        LocalItem.LocalItemType.PLAYLIST_LOCAL_ITEM), item.getDisplayIndex());
                ((PlaylistMetadataEntry) item).displayIndex = i;

            } else if (item instanceof PlaylistRemoteEntity) {

                displayIndexInDatabase.put(new Pair<>(((PlaylistRemoteEntity) item).getUid(),
                                LocalItem.LocalItemType.PLAYLIST_REMOTE_ITEM),
                        item.getDisplayIndex());
                ((PlaylistRemoteEntity) item).setDisplayIndex(i);

            }
        }

        if (isDisplayIndexModified) {
            debounceSaver.saveChanges();
        }
    }

    @Override
    public void saveImmediate() {
        if (itemListAdapter == null) {
            return;
        }

        // List must be loaded and modified in order to save
        if (isLoadingComplete == null || debounceSaver == null
                || !isLoadingComplete.get() || !debounceSaver.getIsModified()) {
            Log.w(TAG, "Attempting to save playlists in bookmark when bookmark "
                    + "is not loaded or playlists not modified");
            return;
        }

        // The saved order is always the original one, whatever is displayed.
        final List<LocalItem> items = new ArrayList<>(isOriginMode()
                ? itemListAdapter.getItemsList()
                : sorted(itemListAdapter.getItemsList(), SortMode.ORIGIN));
        if (sortMode == SortMode.ORIGIN_REVERSE) {
            Collections.reverse(items);
        }
        final List<PlaylistMetadataEntry> localItemsUpdate = new ArrayList<>();
        final List<Long> localItemsDeleteUid = new ArrayList<>();
        final List<PlaylistRemoteEntity> remoteItemsUpdate = new ArrayList<>();
        final List<Long> remoteItemsDeleteUid = new ArrayList<>();

        // Calculate display index
        for (int i = 0; i < items.size(); i++) {
            final LocalItem item = items.get(i);

            if (item instanceof PlaylistMetadataEntry) {
                ((PlaylistMetadataEntry) item).displayIndex = i;

                final Long uid = ((PlaylistMetadataEntry) item).uid;
                final Pair<Long, LocalItem.LocalItemType> key = new Pair<>(uid,
                        LocalItem.LocalItemType.PLAYLIST_LOCAL_ITEM);
                final Long databaseIndex = displayIndexInDatabase.remove(key);

                // The database index should not be null because inserting new item into database
                // is not handled here. NullPointerException has occurred once, but I can't
                // reproduce it. Enhance robustness here.
                if (databaseIndex != null && databaseIndex != i) {
                    localItemsUpdate.add((PlaylistMetadataEntry) item);
                }
            } else if (item instanceof PlaylistRemoteEntity) {
                ((PlaylistRemoteEntity) item).setDisplayIndex(i);

                final Long uid = ((PlaylistRemoteEntity) item).getUid();
                final Pair<Long, LocalItem.LocalItemType> key = new Pair<>(uid,
                        LocalItem.LocalItemType.PLAYLIST_REMOTE_ITEM);
                final Long databaseIndex = displayIndexInDatabase.remove(key);

                if (databaseIndex != null && databaseIndex != i) {
                    remoteItemsUpdate.add((PlaylistRemoteEntity) item);
                }
            }
        }

        // Find deleted items
        for (final Pair<Long, LocalItem.LocalItemType> key : displayIndexInDatabase.keySet()) {
            if (key.second.equals(LocalItem.LocalItemType.PLAYLIST_LOCAL_ITEM)) {
                localItemsDeleteUid.add(key.first);
            } else if (key.second.equals(LocalItem.LocalItemType.PLAYLIST_REMOTE_ITEM)) {
                remoteItemsDeleteUid.add(key.first);
            }
        }

        displayIndexInDatabase.clear();

        // 1. Update local playlists
        // 2. Update remote playlists
        // 3. Set isModified false
        disposables.add(localPlaylistManager.updatePlaylists(localItemsUpdate, localItemsDeleteUid)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> disposables.add(remotePlaylistManager.updatePlaylists(
                        remoteItemsUpdate, remoteItemsDeleteUid)
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(() -> {
                                            if (debounceSaver != null) {
                                                debounceSaver.setIsModified(false);
                                            }
                                        },
                                        throwable -> showError(new ErrorInfo(throwable,
                                                UserAction.REQUESTED_BOOKMARK,
                                                "Saving playlist"))
                                )),
                        throwable -> showError(new ErrorInfo(throwable,
                                UserAction.REQUESTED_BOOKMARK, "Saving playlist"))
                ));

    }

    private ItemTouchHelper.SimpleCallback getItemTouchCallback() {
        int directions = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
        if (shouldUseGridLayout(requireContext())) {
            directions |= ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
        }
        return new ItemTouchHelper.SimpleCallback(directions,
                ItemTouchHelper.ACTION_STATE_IDLE) {
            @Override
            public int interpolateOutOfBoundsScroll(@NonNull final RecyclerView recyclerView,
                                                    final int viewSize,
                                                    final int viewSizeOutOfBounds,
                                                    final int totalSize,
                                                    final long msSinceStartScroll) {
                final int standardSpeed = super.interpolateOutOfBoundsScroll(recyclerView,
                        viewSize, viewSizeOutOfBounds, totalSize, msSinceStartScroll);
                final int minimumAbsVelocity = Math.max(MINIMUM_INITIAL_DRAG_VELOCITY,
                        Math.abs(standardSpeed));
                return minimumAbsVelocity * (int) Math.signum(viewSizeOutOfBounds);
            }

            @Override
            public boolean onMove(@NonNull final RecyclerView recyclerView,
                                  @NonNull final RecyclerView.ViewHolder source,
                                  @NonNull final RecyclerView.ViewHolder target) {

                // Allow swap LocalBookmarkPlaylistItemHolder and RemoteBookmarkPlaylistItemHolder.
                if (itemListAdapter == null || itemListAdapter.isFilterEnabled
                        || !isOriginMode()
                        || source.getItemViewType() != target.getItemViewType()
                        && !(
                        (
                                (source instanceof LocalBookmarkPlaylistItemHolder)
                                        || (source instanceof RemoteBookmarkPlaylistItemHolder)
                        )
                                && (
                                (target instanceof LocalBookmarkPlaylistItemHolder)
                                        || (target instanceof RemoteBookmarkPlaylistItemHolder)
                        ))
                ) {
                    return false;
                }

                final int sourceIndex = source.getBindingAdapterPosition();
                final int targetIndex = target.getBindingAdapterPosition();
                final boolean isSwapped = itemListAdapter.swapItems(sourceIndex, targetIndex);
                if (isSwapped) {
                    debounceSaver.saveChanges();
                }
                return isSwapped;
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public void onSwiped(@NonNull final RecyclerView.ViewHolder viewHolder,
                                 final int swipeDir) {
                // Do nothing.
            }
        };
    }

    ///////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////

    private void showRemoteDeleteDialog(final PlaylistRemoteEntity item) {
        showDeleteDialog(item.getName(), item);
    }

    private void showLocalDialog(final PlaylistMetadataEntry selectedItem) {
        final DialogEditTextBinding dialogBinding
                = DialogEditTextBinding.inflate(getLayoutInflater());
        dialogBinding.dialogEditText.setHint(R.string.name);
        dialogBinding.dialogEditText.setInputType(InputType.TYPE_CLASS_TEXT);
        dialogBinding.dialogEditText.setText(selectedItem.name);

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setView(dialogBinding.getRoot())
                .setPositiveButton(R.string.rename_playlist, (dialog, which) ->
                        changeLocalPlaylistName(
                                selectedItem.uid,
                                dialogBinding.dialogEditText.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.delete, (dialog, which) -> {
                    showDeleteDialog(selectedItem.name, selectedItem);
                    dialog.dismiss();
                })
                .create()
                .show();
    }

    private void showDeleteDialog(final String name, final PlaylistLocalItem item) {
        if (activity == null || disposables == null) {
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle(name)
                .setMessage(R.string.delete_playlist_prompt)
                .setCancelable(true)
                .setPositiveButton(R.string.delete, (dialog, i) -> deleteItem(item))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

