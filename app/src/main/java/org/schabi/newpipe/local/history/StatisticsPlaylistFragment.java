package org.schabi.newpipe.local.history;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;

import com.google.android.material.snackbar.Snackbar;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.stream.StreamStatisticsEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.databinding.PlaylistControlBinding;
import org.schabi.newpipe.databinding.StatisticPlaylistControlBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.BackPressable;
import org.schabi.newpipe.info_list.dialog.InfoItemDialog;
import org.schabi.newpipe.local.BaseLocalListFragment;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.settings.HistorySettingsFragment;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.OnClickGesture;
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;

public class StatisticsPlaylistFragment
        extends BaseLocalListFragment<List<StreamStatisticsEntry>, Void> implements BackPressable {
    private final CompositeDisposable disposables = new CompositeDisposable();
    Parcelable itemsListState;
    private StatisticSortMode sortMode = StatisticSortMode.LAST_PLAYED;

    private StatisticPlaylistControlBinding headerBinding;
    private PlaylistControlBinding playlistControlBinding;

    /* Used for independent events */
    private Subscription databaseSubscription;
    private HistoryRecordManager recordManager;
    private EditText editText;
    private View searchClear;
    private TextWatcher textWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            itemListAdapter.filter(String.valueOf(editText.getText()));
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    };

    private List<StreamStatisticsEntry> processResult(final List<StreamStatisticsEntry> results) {
        final Comparator<StreamStatisticsEntry> comparator;
        switch (sortMode) {
            case LAST_PLAYED:
                comparator = Comparator.comparing(StreamStatisticsEntry::getLatestAccessDate);
                break;
            case MOST_PLAYED:
                comparator = Comparator.comparingLong(StreamStatisticsEntry::getWatchCount);
                break;
            default:
                return null;
        }
        Collections.sort(results, comparator.reversed());
        return results;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Creation
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        recordManager = new HistoryRecordManager(getContext());
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlist, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (activity != null) {
            setTitle(activity.getString(R.string.title_activity_history));
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_history, menu);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Views
    ///////////////////////////////////////////////////////////////////////////

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        if (!useAsFrontPage) {
            setTitle(getString(R.string.title_last_played));
        }
    }

    @Override
    protected ViewBinding getListHeader() {
        headerBinding = StatisticPlaylistControlBinding.inflate(activity.getLayoutInflater(),
                itemsList, false);
        playlistControlBinding = headerBinding.playlistControl;
        playlistControlBinding.getRoot().configure(getString(R.string.list_control_page_history));

        return headerBinding;
    }

    @Override
    protected void initListeners() {
        super.initListeners();

        itemListAdapter.setSelectedListener(new OnClickGesture<LocalItem>() {
            @Override
            public void selected(final LocalItem selectedItem) {
                if (toggleSelectionIfActive(selectedItem)) {
                    return;
                }
                if (selectedItem instanceof StreamStatisticsEntry) {
                    final StreamEntity item =
                            ((StreamStatisticsEntry) selectedItem).getStreamEntity();
                    NavigationHelper.openVideoDetailFragment(requireContext(), getFM(),
                            item.getServiceId(), item.getUrl(), item.getTitle(), null, false);
                }
            }

            @Override
            public void held(final LocalItem selectedItem) {
                if (selectedItem instanceof StreamStatisticsEntry) {
                    showInfoItemDialog((StreamStatisticsEntry) selectedItem);
                }
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == R.id.action_multi_select) {
            selection.enter();
        } else if (item.getItemId() == R.id.action_history_clear) {
            HistorySettingsFragment
                    .openDeleteWatchHistoryDialog(requireContext(), recordManager, disposables);
        } else if (item.getItemId() == R.id.menu_item_sort) {
            showSortDialog();
        } else if (item.getItemId() == R.id.action_search_local) {
            ActionBar actionBar = activity.getSupportActionBar();
            View customView = getLayoutInflater().inflate(R.layout.local_playlist_search_toolbar, null, false);
            assert actionBar != null;
            actionBar.setDisplayShowTitleEnabled(false);
            actionBar.setCustomView(customView, new ActionBar.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            actionBar.setDisplayShowCustomEnabled(true);
            editText = activity.findViewById(R.id.toolbar_search_edit_text_local);
            editText.requestFocus();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
            item.setVisible(false);
            searchClear = customView.findViewById(R.id.toolbar_search_clear_local);
            searchClear.setOnClickListener(v -> {
                if (TextUtils.isEmpty(editText.getText())) {
                    destroyCustomViewInActionBar();
                    return;
                }
                editText.setText("");
            });

            try {
                editText.removeTextChangedListener(textWatcher);
            } catch (Exception e) {
                // ignore
            }
            editText.addTextChangedListener(textWatcher);
        }else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Loading
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void startLoading(final boolean forceLoad) {
        super.startLoading(forceLoad);
        recordManager.getStreamStatistics()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(getHistoryObserver());
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Destruction
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void onPause() {
        super.onPause();
        itemsListState = Objects.requireNonNull(itemsList.getLayoutManager()).onSaveInstanceState();
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

        if(Objects.requireNonNull(activity.getSupportActionBar()).getCustomView() != null){
            destroyCustomViewInActionBar();
        }

        if (itemListAdapter != null) {
            itemListAdapter.unsetSelectedListener();
        }
        if (playlistControlBinding != null) {
            playlistControlBinding.playlistCtrlPlayBgButton.setOnClickListener(null);
            playlistControlBinding.playlistCtrlPlayAllButton.setOnClickListener(null);
            playlistControlBinding.playlistCtrlPlayPopupButton.setOnClickListener(null);

            headerBinding = null;
            playlistControlBinding = null;
        }

        if (databaseSubscription != null) {
            databaseSubscription.cancel();
        }
        databaseSubscription = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        recordManager = null;
        itemsListState = null;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Statistics Loader
    ///////////////////////////////////////////////////////////////////////////

    private Subscriber<List<StreamStatisticsEntry>> getHistoryObserver() {
        return new Subscriber<List<StreamStatisticsEntry>>() {
            @Override
            public void onSubscribe(final Subscription s) {
                showLoading();

                if (databaseSubscription != null) {
                    databaseSubscription.cancel();
                }
                databaseSubscription = s;
                databaseSubscription.request(1);
            }

            @Override
            public void onNext(final List<StreamStatisticsEntry> streams) {
                handleResult(streams);
                if (databaseSubscription != null) {
                    databaseSubscription.request(1);
                }
            }

            @Override
            public void onError(final Throwable exception) {
                showError(
                        new ErrorInfo(exception, UserAction.SOMETHING_ELSE, "History Statistics"));
            }

            @Override
            public void onComplete() {
            }
        };
    }

    @Override
    public void handleResult(@NonNull final List<StreamStatisticsEntry> result) {
        super.handleResult(result);
        if (itemListAdapter == null) {
            return;
        }

        playlistControlBinding.getRoot().setVisibility(View.VISIBLE);

        itemListAdapter.clearStreamItemList();

        // drop selected entries that no longer exist (e.g. deleted elsewhere)
        selection.retainOnly(getSelectionKeys(new ArrayList<>(result)));

        if (result.isEmpty()) {
            showEmptyState();
            return;
        }

        itemListAdapter.addItems(processResult(result));
        if (itemsListState != null && itemsList.getLayoutManager() != null) {
            itemsList.getLayoutManager().onRestoreInstanceState(itemsListState);
            itemsListState = null;
        }

        playlistControlBinding.playlistCtrlPlayAllButton.setOnClickListener(view ->
                NavigationHelper.playOnMainPlayer(activity, getPlayQueue()));
        playlistControlBinding.playlistCtrlPlayPopupButton.setOnClickListener(view ->
                NavigationHelper.playOnPopupPlayer(activity, getPlayQueue(), false));
        playlistControlBinding.playlistCtrlPlayBgButton.setOnClickListener(view ->
                NavigationHelper.playOnBackgroundPlayer(activity, getPlayQueue(), false));

        hideLoading();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Error Handling
    ///////////////////////////////////////////////////////////////////////////

    @Override
    protected void resetFragment() {
        super.resetFragment();
        if (databaseSubscription != null) {
            databaseSubscription.cancel();
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private void showSortDialog() {
        final StatisticSortMode[] modes = {StatisticSortMode.LAST_PLAYED,
                StatisticSortMode.MOST_PLAYED};
        final String[] items = {getString(R.string.title_last_played),
                getString(R.string.title_most_played)};
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.sort)
                .setSingleChoiceItems(items, java.util.Arrays.asList(modes).indexOf(sortMode),
                        (dialog, which) -> {
                            setSortMode(modes[which]);
                            dialog.dismiss();
                        })
                .show();
    }

    private void setSortMode(final StatisticSortMode mode) {
        if (sortMode == mode) {
            return;
        }
        sortMode = mode;
        setTitle(getString(mode == StatisticSortMode.LAST_PLAYED
                ? R.string.title_last_played : R.string.title_most_played));
        startLoading(true);
    }

    private PlayQueue getPlayQueueStartingAt(final StreamStatisticsEntry infoItem) {
        return getPlayQueue(Math.max(itemListAdapter.getItemsList().indexOf(infoItem), 0));
    }

    private void showInfoItemDialog(final StreamStatisticsEntry item) {
        final Context context = getContext();
        final StreamInfoItem infoItem = item.toStreamInfoItem();

        try {
            final InfoItemDialog.Builder dialogBuilder =
                    new InfoItemDialog.Builder(getActivity(), context, this, infoItem);

            // set entries in the middle; the others are added automatically
            dialogBuilder
                    .addEntry(StreamDialogDefaultEntry.DELETE)
                    .setAction(
                            StreamDialogDefaultEntry.DELETE,
                            (f, i) -> deleteEntry(item))
                    .setAction(
                            StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND,
                            (f, i) -> NavigationHelper.playOnBackgroundPlayer(
                                    context, getPlayQueueStartingAt(item), true))
                    .create()
                    .show();
        } catch (final IllegalArgumentException e) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(e, infoItem);
        }
    }

    private void deleteEntry(final StreamStatisticsEntry entry) {
        final Disposable onDelete = recordManager
                .deleteStreamHistoryAndState(entry.getStreamId())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> {
                            if (getView() != null) {
                                Snackbar.make(getView(), R.string.one_item_deleted,
                                        Snackbar.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(getContext(),
                                        R.string.one_item_deleted,
                                        Toast.LENGTH_SHORT).show();
                            }
                        },
                        throwable -> showSnackBarError(new ErrorInfo(throwable,
                                UserAction.DELETE_FROM_HISTORY, "Deleting item")));

        disposables.add(onDelete);
    }

    private PlayQueue getPlayQueue() {
        return getPlayQueue(0);
    }

    private PlayQueue getPlayQueue(final int index) {
        if (itemListAdapter == null) {
            return new SinglePlayQueue(Collections.emptyList(), 0);
        }

        final List<LocalItem> infoItems = itemListAdapter.getItemsList();
        final List<StreamInfoItem> streamInfoItems = new ArrayList<>(infoItems.size());
        for (final LocalItem item : infoItems) {
            if (item instanceof StreamStatisticsEntry) {
                streamInfoItems.add(((StreamStatisticsEntry) item).toStreamInfoItem());
            }
        }
        return new SinglePlayQueue(streamInfoItems, index);
    }


    @Override
    public boolean onBackPressed() {
        if (exitSelectionOnBack()) {
            return true;
        }
        if(Objects.requireNonNull(activity.getSupportActionBar()).getCustomView() != null){
            destroyCustomViewInActionBar();
            return true;
        }
        return false;
    }
    public void destroyCustomViewInActionBar(){
        ActionBar actionBar = activity.getSupportActionBar();
        assert actionBar != null;
        actionBar.setCustomView(null);
        actionBar.setDisplayShowCustomEnabled(false);
        actionBar.setDisplayShowTitleEnabled(true);

        activity.invalidateOptionsMenu();
        
        if(itemListAdapter != null) {
            itemListAdapter.clearFilter();
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Multi-selection
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected int getSelectionMenu() {
        return R.menu.menu_history_selection;
    }

    @Nullable
    @Override
    protected Long getSelectionKey(@NonNull final LocalItem item) {
        return item instanceof StreamStatisticsEntry
                ? ((StreamStatisticsEntry) item).getStreamId() : null;
    }

    @Nullable
    @Override
    protected StreamEntity getSelectionStream(@NonNull final LocalItem item) {
        return item instanceof StreamStatisticsEntry
                ? ((StreamStatisticsEntry) item).getStreamEntity() : null;
    }

    @Override
    protected boolean onSelectionAction(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.action_selection_delete) {
            confirmDeleteSelected();
            return true;
        }
        return false;
    }

    private void confirmDeleteSelected() {
        final int count = selection.getSelectedCount();
        if (count == 0) {
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setMessage(getResources().getString(R.string.delete_selected_history_confirm, count))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> deleteSelected())
                .show();
    }

    private void deleteSelected() {
        final Set<Long> ids = selection.getSelectedKeys();
        if (ids.isEmpty()) {
            return;
        }
        final int count = ids.size();
        disposables.add(recordManager.deleteStreamHistoryAndState(ids)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> {
                            selection.exit();
                            final String message = getResources()
                                    .getString(R.string.items_deleted, count);
                            if (getView() != null) {
                                Snackbar.make(getView(), message, Snackbar.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                            }
                        },
                        throwable -> showSnackBarError(new ErrorInfo(throwable,
                                UserAction.DELETE_FROM_HISTORY, "Deleting selected items"))));
    }

    private enum StatisticSortMode {
        LAST_PLAYED,
        MOST_PLAYED,
    }
}
