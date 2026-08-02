package org.schabi.newpipe.info_list.dialog;

import static org.schabi.newpipe.util.NavigationHelper.openChannelFragment;
import static org.schabi.newpipe.util.SparseItemUtil.fetchItemInfoIfSparse;
import static org.schabi.newpipe.util.SparseItemUtil.fetchStreamInfoAndSaveToDatabase;
import static org.schabi.newpipe.util.SparseItemUtil.fetchUploaderUrlIfSparse;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import androidx.preference.PreferenceManager;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.download.DownloadDialog;
import org.schabi.newpipe.local.cache.CacheDialog;
import org.schabi.newpipe.local.cache.CacheLogger;
import org.schabi.newpipe.local.cache.CacheManager;
import org.schabi.newpipe.local.dialog.PlaylistAppendDialog;
import org.schabi.newpipe.local.dialog.PlaylistDialog;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.external_communication.KoreUtils;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * <p>
 *     This enum provides entries that are accepted
 *     by the {@link InfoItemDialog.Builder}.
 * </p>
 * <p>
 *     These entries contain a String {@link #resource} which is displayed in the dialog and
 *     a default {@link #action} that is executed
 *     when the entry is selected (via <code>onClick()</code>).
 *     <br/>
 *     They action can be overridden by using the Builder's
 *     {@link InfoItemDialog.Builder#setAction(
 *     StreamDialogDefaultEntry, StreamDialogEntry.StreamDialogEntryAction)}
 *     method.
 * </p>
 */
public enum StreamDialogDefaultEntry {
    SHOW_CHANNEL_DETAILS(R.string.show_channel_details, (fragment, item) -> {
        if (item.getUploaderUrl() != null && !Objects.equals(item.getUploaderUrl(), "")) {
            openChannelFragment(fragment, item, item.getUploaderUrl());
        } else {
            fetchUploaderUrlIfSparse(fragment.requireContext(), item.getServiceId(), item.getUrl(),
                    item.getUploaderUrl(), url -> openChannelFragment(fragment, item, url));
        }
    }),

    /**
     * Enqueues the stream automatically to the current PlayerType.
     */
    ENQUEUE(R.string.enqueue_stream, (fragment, item) ->
            fetchItemInfoIfSparse(fragment.requireContext(), item, singlePlayQueue ->
                NavigationHelper.enqueueOnPlayer(fragment.getContext(), singlePlayQueue))
    ),

    /**
     * Enqueues the stream automatically to the current PlayerType
     * after the currently playing stream.
     */
    ENQUEUE_NEXT(R.string.enqueue_next_stream, (fragment, item) -> {
        final Context context = fragment.requireContext();
        fetchItemInfoIfSparse(context, item, singlePlayQueue ->
            NavigationHelper.enqueueNextOnPlayer(context, singlePlayQueue));
    }),

    START_HERE_ON_BACKGROUND(R.string.start_here_on_background, (fragment, item) -> {
        final Context context = fragment.requireContext();
        fetchItemInfoIfSparse(context, item, singlePlayQueue ->
            NavigationHelper.playOnBackgroundPlayer(context, singlePlayQueue, true));
    }),

    START_HERE_ON_POPUP(R.string.start_here_on_popup, (fragment, item) -> {
        final Context context = fragment.requireContext();
        fetchItemInfoIfSparse(context, item, singlePlayQueue ->
            NavigationHelper.playOnPopupPlayer(context, singlePlayQueue, true));
    }),

    SET_AS_PLAYLIST_THUMBNAIL(R.string.set_as_playlist_thumbnail, (fragment, item) -> {
        throw new UnsupportedOperationException("This needs to be implemented manually "
                + "by using InfoItemDialog.Builder.setAction()");
    }),

    DELETE(R.string.delete, (fragment, item) -> {
        throw new UnsupportedOperationException("This needs to be implemented manually "
                + "by using InfoItemDialog.Builder.setAction()");
    }),

    /**
     * Opens a {@link PlaylistDialog} to either append the stream to a playlist
     * or create a new playlist if there are no local playlists.
     */
    APPEND_PLAYLIST(R.string.add_to_playlist, (fragment, item) ->
        PlaylistDialog.createCorrespondingDialog(
                fragment.getContext(),
                Collections.singletonList(new StreamEntity(item)),
                dialog -> dialog.show(
                        fragment.getParentFragmentManager(),
                        "StreamDialogEntry@"
                                + (dialog instanceof PlaylistAppendDialog ? "append" : "create")
                                + "_playlist"
                )
        )
    ),

    PLAY_WITH_KODI(R.string.play_with_kodi_title, (fragment, item) -> {
        final Uri videoUrl = Uri.parse(item.getUrl());
        try {
            NavigationHelper.playWithKore(fragment.requireContext(), videoUrl);
        } catch (final Exception e) {
            KoreUtils.showInstallKoreDialog(fragment.requireActivity());
        }
    }),

    SHARE(R.string.share, (fragment, item) ->
            ShareUtils.shareText(fragment.requireContext(), item.getName(), item.getUrl(),
                    item.getThumbnailUrl())),

    DOWNLOAD(R.string.download, (fragment, item) ->
            fetchStreamInfoAndSaveToDatabase(fragment.requireContext(), item.getServiceId(),
                    item.getUrl(), info -> {
                        final DownloadDialog downloadDialog
                                = DownloadDialog.newInstance(fragment.requireContext(), info);
                        downloadDialog.show(fragment.getChildFragmentManager(), "downloadDialog");
                    })
    ),

    /**
     * Toggles the "cache for offline viewing" feature (issue #2782) for this item: caches it if
     * not already cached, or offers to remove it from the cache if it is. Prefer
     * {@link InfoItemDialog.Builder#addCacheEntryIfNeeded()} over adding this entry directly,
     * since that also gives the dialog a "Cache"/"Uncache" label matching the item's current
     * state - this static entry's label never changes.
     */
    CACHE(R.string.controls_cache_title, (fragment, item) -> {
        final Context context = fragment.requireContext();
        final boolean cached = CacheManager.isCachedBlocking(
                context, item.getServiceId(), item.getUrl());
        CacheLogger.d(context, "StreamDialogDefaultEntry",
                "context menu Cache/Uncache tapped for url=" + item.getUrl()
                        + " currentlyCached=" + cached);
        if (cached) {
            // Complete entries only: an unfinished row is a failed attempt, and offering to
            // delete it rather than retry is what made a broken cache impossible to restart.
            CacheManager.findCompleteCachedStream(context, item.getServiceId(), item.getUrl())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(cachedEntity -> new AlertDialog.Builder(context)
                            .setTitle(R.string.cache_remove_confirm_title)
                            .setMessage(R.string.cache_remove_confirm_message)
                            .setPositiveButton(R.string.ok, (dialog, which) -> Completable
                                    .fromAction(() -> CacheManager.removeCache(
                                            context, cachedEntity))
                                    .subscribeOn(Schedulers.io())
                                    .subscribe())
                            .setNegativeButton(R.string.cancel, null)
                            .show());
        } else {
            // Needs the full StreamInfo (a list item only carries metadata, no stream URLs)
            // before a quality can be offered, so fetch it first, then show the same selector
            // the video page uses.
            fetchStreamInfoAndSaveToDatabase(context, item.getServiceId(), item.getUrl(),
                    info -> CacheDialog.show(context, info));
        }
    }),

    OPEN_IN_BROWSER(R.string.open_in_browser, (fragment, item) ->
            ShareUtils.openUrlInBrowser(fragment.requireContext(), item.getUrl())),


    MARK_AS_WATCHED(R.string.mark_as_watched, (fragment, item) ->
        new HistoryRecordManager(fragment.getContext())
                .markAsWatched(item)
                .onErrorComplete()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe()
    ),

    ADD_TO_FILTER_LIST(R.string.add_to_filter_list, (fragment, item) -> {
        // Create confirmation dialog
        new AlertDialog.Builder(fragment.requireContext())
                .setMessage(fragment.getString(R.string.add_to_filter_list_confirm, item.getUploaderName()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // User confirmed - execute blocking logic
                    final SharedPreferences pref = PreferenceManager
                            .getDefaultSharedPreferences(fragment.requireContext());
                    // Get the key from string resources
                    String filterKey = fragment.requireContext().getString(R.string.filter_by_channel_key) + "_set";
                    // Retrieve current filter set or default to empty
                    Set<String> currentFilters = new HashSet<>(pref.getStringSet(filterKey, new HashSet<>()));
                    // Add the new channel to the set
                    currentFilters.add(item.getUploaderName());
                    // Save the updated set back to SharedPreferences
                    pref.edit()
                            .putStringSet(filterKey, currentFilters)
                            .apply();
                    ServiceHelper.initServices(fragment.requireContext());
                    Toast.makeText(
                            fragment.requireContext(), R.string.recaptcha_done_button,
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }),


    NAVIGATE_TO(R.string.navigate_to, (fragment, item) -> {
        throw new UnsupportedOperationException("This needs to be implemented manually "
                + "by using InfoItemDialog.Builder.setAction()");
    }),

    SHOW_STREAM_DETAILS(R.string.show_stream_details, (fragment, item) -> {
        throw new UnsupportedOperationException("This needs to be implemented manually "
                + "by using InfoItemDialog.Builder.setAction()");
    });


    @StringRes
    public final int resource;
    @NonNull
    public final StreamDialogEntry.StreamDialogEntryAction action;

    StreamDialogDefaultEntry(@StringRes final int resource,
                             @NonNull final StreamDialogEntry.StreamDialogEntryAction action) {
        this.resource = resource;
        this.action = action;
    }

    @NonNull
    public StreamDialogEntry toStreamDialogEntry() {
        return new StreamDialogEntry(resource, action);
    }

}
