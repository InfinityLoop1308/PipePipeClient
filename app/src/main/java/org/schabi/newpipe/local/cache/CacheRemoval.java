package org.schabi.newpipe.local.cache;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.cache.model.CachedStreamEntity;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;

import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Removing a stream from the offline cache, from wherever the user asks for it.
 *
 * <p>Deleting a video is worth a question while it is still worth watching, and pure friction once
 * it has been watched - which is exactly when people clear it out. So the confirmation is skipped
 * for a stream the app already counts as watched, and the toast becomes the whole feedback.</p>
 */
public final class CacheRemoval {

    private static final String TAG = "CacheRemoval";

    private CacheRemoval() {
        // no instance
    }

    /**
     * Removes {@code entity} from the cache, asking first unless the video has been watched.
     *
     * @param context an activity context: an unwatched stream puts a dialog on screen
     * @return the subscription doing the work, for callers that keep a disposable container
     */
    @NonNull
    public static Disposable removeAsking(@NonNull final Context context,
                                          @NonNull final CachedStreamEntity entity) {
        return Single.fromCallable(() -> isWatched(context, entity))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(watched -> {
                    if (watched) {
                        remove(context, entity);
                    } else {
                        new AlertDialog.Builder(context)
                                .setTitle(R.string.cache_remove_confirm_title)
                                .setMessage(R.string.cache_remove_confirm_message)
                                .setPositiveButton(R.string.ok,
                                        (dialog, which) -> remove(context, entity))
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    }
                }, throwable -> Log.e(TAG, "could not check the watch state of "
                        + entity.getUrl(), throwable));
    }

    private static void remove(@NonNull final Context context,
                               @NonNull final CachedStreamEntity entity) {
        Completable.fromAction(() -> CacheManager.removeCache(context, entity))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> Toast.makeText(context, R.string.cache_removed,
                                Toast.LENGTH_SHORT).show(),
                        throwable -> {
                            Log.e(TAG, "could not remove " + entity.getUrl(), throwable);
                            Toast.makeText(context, R.string.general_error,
                                    Toast.LENGTH_SHORT).show();
                        });
    }

    /**
     * Whether the user has watched this stream to the end, by the same rule the rest of the app
     * uses for it - {@link StreamStateEntity#isFinished(long)}, which is what puts the tick on a
     * thumbnail and what the feed's "hide watched" filter goes by. A stream with no playback
     * state at all has not been watched.
     *
     * <p>Reads the two tables directly rather than going through {@code HistoryRecordManager},
     * whose lookups all want an {@code InfoItem} or a {@code StreamInfo}; a cache row carries the
     * service, the URL and the duration itself, which is everything the check needs.</p>
     */
    private static boolean isWatched(@NonNull final Context context,
                                     @NonNull final CachedStreamEntity entity) {
        final var database = NewPipeDatabase.getInstance(context.getApplicationContext());
        final List<StreamEntity> streams = database.streamDAO()
                .getStream(entity.getServiceId(), entity.getUrl()).blockingFirst();
        if (streams.isEmpty()) {
            return false;
        }
        final List<StreamStateEntity> states = database.streamStateDAO()
                .getState(streams.get(0).getUid()).blockingFirst();
        return !states.isEmpty() && states.get(0).isFinished(entity.getDuration());
    }
}
