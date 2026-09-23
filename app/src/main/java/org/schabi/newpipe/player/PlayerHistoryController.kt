package org.schabi.newpipe.player

import android.util.Log
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.R
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem

/**
 * Owns the watch-history side of playback: view counting, saving the watched progress, and
 * loading a saved progress back for resume-start. The [HistoryRecordManager] and the
 * [io.reactivex.rxjava3.disposables.CompositeDisposable] guarding its async updates live here,
 * so [Player] no longer holds database state.
 */
class PlayerHistoryController(private val player: Player) {

    private val context = player.context
    private val prefs = player.prefs
    private val recordManager = HistoryRecordManager(context)
    private val databaseUpdateDisposable = CompositeDisposable()

    fun clear() {
        databaseUpdateDisposable.clear()
    }

    fun registerStreamViewed() {
        player.currentStreamInfo.ifPresent { info ->
            databaseUpdateDisposable.add(recordManager.onViewed(info).onErrorComplete().subscribe())
        }
    }

    private fun saveStreamProgressState(progressMillis: Long) {
        val info = player.currentStreamInfo.orElse(null) ?: return
        if (!prefs.getBoolean(context.getString(R.string.enable_watch_history_key), true)) {
            return
        }
        if (Player.DEBUG) {
            Log.d(
                Player.TAG, "saveStreamProgressState() called with: progressMillis="
                        + progressMillis + ", currentMetadata=[" + info.name + "]"
            )
        }

        databaseUpdateDisposable.add(
            recordManager.saveStreamState(info, progressMillis)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError { e ->
                    if (Player.DEBUG) {
                        e.printStackTrace()
                    }
                }
                .onErrorComplete()
                .subscribe()
        )
    }

    fun saveStreamProgressState() {
        val playQueue = player.playQueue
        if (player.exoPlayerIsNull() || player.currentMetadata == null || playQueue == null
            || playQueue.index != player.simpleExoPlayer.currentMediaItemIndex
        ) {
            // Make sure play queue and current window index are equal, to prevent saving state for
            // the wrong stream on discontinuity (e.g. when the stream just changed but the
            // playQueue index and currentMetadata still haven't updated)
            return
        }
        // Save current position. It will help to restore this position once a user
        // wants to play prev or next stream from the queue
        playQueue.setRecovery(playQueue.index, player.simpleExoPlayer.contentPosition)
        saveStreamProgressState(player.simpleExoPlayer.currentPosition)
    }

    fun saveStreamProgressStateCompleted() {
        // current stream has ended, so the progress is its duration (+1 to overcome rounding)
        player.currentStreamInfo.ifPresent { info ->
            saveStreamProgressState((info.duration + 1) * 1000)
        }
    }

    /**
     * Loads the saved progress of [item] from the history and calls [onResult] on the main
     * thread with the stored position, or with `null` when there is no usable saved progress
     * (finished, not found, or any database error).
     */
    fun startPlaybackWithSavedProgress(item: PlayerMediaItem, onResult: (Long?) -> Unit) {
        databaseUpdateDisposable.add(
            recordManager.loadStreamState(item)
                .observeOn(AndroidSchedulers.mainThread())
                // Do not place the continuation in doFinally() because
                // it restarts playback after destroy()
                //.doFinally()
                .subscribe({ state ->
                    if (!state.isFinished(item.duration)) {
                        // resume playback only if the stream was not played to the end
                        onResult(state.progressMillis)
                    } else {
                        onResult(null)
                    }
                }, { error ->
                    if (Player.DEBUG) {
                        Log.w(Player.TAG, "Failed to start playback", error)
                    }
                    // In case any error we can start playback without history
                    onResult(null)
                }, {
                    // Completed but not found in history
                    onResult(null)
                })
        )
    }
}
