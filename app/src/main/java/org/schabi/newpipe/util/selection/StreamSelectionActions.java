package org.schabi.newpipe.util.selection;

import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.local.dialog.PlaylistDialog;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.NavigationHelper;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

/**
 * Stream actions shared by the selection toolbar of every list
 * (enqueue, play in background, add to playlist, download).
 */
public final class StreamSelectionActions {

    private StreamSelectionActions() {
    }

    /** @return true if {@code item} is one of the stream actions and has been handled */
    public static boolean handle(@NonNull final Fragment fragment,
                                 @NonNull final CompositeDisposable disposables,
                                 @NonNull final MenuItem item,
                                 @NonNull final List<StreamEntity> streams) {
        final int id = item.getItemId();
        if (id != R.id.action_selection_add_to_playlist
                && id != R.id.action_selection_enqueue
                && id != R.id.action_selection_background
                && id != R.id.action_selection_download) {
            return false;
        }
        if (streams.isEmpty() || fragment.getContext() == null) {
            return true;
        }

        final List<StreamInfoItem> infoItems = new ArrayList<>(streams.size());
        for (final StreamEntity stream : streams) {
            infoItems.add(stream.toStreamInfoItem());
        }

        if (id == R.id.action_selection_add_to_playlist) {
            disposables.add(PlaylistDialog.createCorrespondingDialog(fragment.requireContext(),
                    streams, dialog -> dialog.show(fragment.getParentFragmentManager(),
                            "StreamSelectionActions@playlist")));
        } else if (id == R.id.action_selection_enqueue) {
            NavigationHelper.enqueueOnPlayer(fragment.requireContext(),
                    new SinglePlayQueue(infoItems, 0));
        } else if (id == R.id.action_selection_background) {
            NavigationHelper.playOnBackgroundPlayer(fragment.requireContext(),
                    new SinglePlayQueue(infoItems, 0), false);
        } else {
            SelectionDownloadHelper.showDownloadDialog(fragment.requireContext(), infoItems);
        }
        return true;
    }

    @NonNull
    public static List<StreamEntity> toEntities(@NonNull final List<? extends StreamInfoItem> items) {
        final List<StreamEntity> entities = new ArrayList<>(items.size());
        for (final StreamInfoItem item : items) {
            entities.add(new StreamEntity(item));
        }
        return entities;
    }
}
