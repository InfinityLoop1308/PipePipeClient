package org.schabi.newpipe.player

import android.util.Log
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.bulletComments.MovieBulletCommentsPlayer
import org.schabi.newpipe.util.utils
import java.time.Duration
import java.util.Date
import java.util.Objects
import java.util.concurrent.TimeUnit

/**
 * Owns the bullet-comments ("danmaku") state of a [Player]: the [MovieBulletCommentsPlayer]
 * instance, the periodic drawing [Disposable] and whether the comments are visible.
 *
 * [Player] only keeps the playback primitives this controller cannot own (the current item and
 * playback position) and delegates the public entry points [Player.startBCPlayer] and
 * [Player.pauseBCPlayer], so that callers such as the video detail fragment are unaffected.
 * The decision whether bullet comments can be shown for the current stream stays here, so
 * [Player] no longer holds any bullet-comments state.
 */
class BulletCommentsController(private val player: Player) {

    private companion object {
        const val TAG = "BulletCommentsController"
        const val VISIBILITY_PREF_KEY = "isBCPlayerVisible"
    }

    private var bcPlayer: MovieBulletCommentsPlayer? = null
    private var drawCommentsObservable: Disposable? = null

    var isVisible = false
        private set

    /**
     * Creates the [MovieBulletCommentsPlayer] for the current stream when its service supports
     * bullet comments, loads the persisted visibility and updates the visibility switch.
     */
    fun init() {
        try {
            val metadata = player.currentMetadata
            if (metadata != null && NewPipe.getService(metadata.serviceId)
                    .serviceInfo
                    .mediaCapabilities
                    .contains(StreamingService.ServiceInfo.MediaCapability.BULLET_COMMENTS)
                && !player.audioPlayerSelected()
            ) {
                val existing = bcPlayer
                if (existing != null) {
                    if (utils.DetimestampedEqual(existing.url, metadata.url)) {
                        return
                    }
                    existing.disconnect()
                }
                clear()
                bcPlayer = MovieBulletCommentsPlayer(player.binding.bulletCommentsView).apply {
                    setInitialData(metadata.serviceId, metadata.url)
                    init()
                }
                Log.d(TAG, "BulletCommentsView initialized.")
            } else {
                Log.i(
                    TAG,
                    "Current service does not have MediaCapability of BULLET_COMMENTS" +
                        ", skipping BulletCommentsView initialization.",
                )
            }
        } catch (e: ExtractionException) {
            Log.e(TAG, Log.getStackTraceString(e))
        }

        isVisible = player.prefs.getBoolean(VISIBILITY_PREF_KEY, false)
        Log.i(TAG, "BulletCommentPlayer initial visibility: $isVisible")
        val switch = player.binding.switchCommentsVisibility
        if (bcPlayer == null) {
            // If set to INVISIBLE, the space remains.
            switch.visibility = View.GONE
        } else {
            switch.visibility = View.VISIBLE
            switch.setImageDrawable(
                AppCompatResources.getDrawable(
                    player.context,
                    if (isVisible) R.drawable.ic_bullet_comment_enabled
                    else R.drawable.ic_bullet_comment_disabled,
                ),
            )
        }
    }

    /** (Re)starts drawing comments from the current playback position. */
    fun start() {
        val bulletCommentsPlayer = bcPlayer
        if (bulletCommentsPlayer == null || drawCommentsObservable != null || !isVisible) {
            return
        }
        bulletCommentsPlayer.start(currentPositionDuration())
        drawCommentsObservable = Observable.interval(
            bulletCommentsPlayer.INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS,
        )
            .observeOn(AndroidSchedulers.mainThread())
            .map { _: Long ->
                var position = currentPositionDuration()
                val item = player.currentItem
                if (item != null && item.startAt != -1L
                    && item.streamType == StreamType.LIVE_STREAM
                ) {
                    position = Duration.ofMillis(Date().time - item.startAt)
                }
                position ?: Duration.ofMillis(-1)
            }
            .subscribe({ position: Duration ->
                if (player.isPlaying && !player.audioPlayerSelected()) {
                    bulletCommentsPlayer.drawComments(position.plus(bulletCommentsPlayer.INTERVAL))
                }
            }, { e: Throwable ->
                Log.e(TAG, Log.getStackTraceString(e))
            })
        Log.d(TAG, "BulletCommentsView started.")
    }

    /** Pauses comment drawing, keeping the current comments on screen. */
    fun pause() {
        val bulletCommentsPlayer = bcPlayer ?: return
        disposeDrawComments()
        bulletCommentsPlayer.pause()
        Log.d(TAG, "BulletCommentsView paused.")
    }

    /** Draws the remaining comments up to the end of the stream and then clears them. */
    fun complete() {
        val bulletCommentsPlayer = bcPlayer
        if (bulletCommentsPlayer == null || !isVisible || player.currentMetadata == null) {
            return
        }
        bulletCommentsPlayer.complete(Objects.requireNonNull(duration()))
        clear()
        Log.d(TAG, "BulletCommentsView completed.")
    }

    /** Clears the comments currently on screen. */
    fun clear() {
        val bulletCommentsPlayer = bcPlayer ?: return
        disposeDrawComments()
        bulletCommentsPlayer.clear()
        Log.d(TAG, "BulletCommentsView cleared.")
    }

    /** Toggles the visibility of the comments, persisting the choice and updating the icon. */
    fun toggleVisibility() {
        isVisible = !isVisible
        player.prefs.edit().putBoolean(VISIBILITY_PREF_KEY, isVisible).apply()
        player.binding.switchCommentsVisibility.setImageDrawable(
            AppCompatResources.getDrawable(
                player.context,
                if (isVisible) R.drawable.ic_bullet_comment_enabled
                else R.drawable.ic_bullet_comment_disabled,
            ),
        )
        Log.i(TAG, "BulletCommentPlayer visibility changed to $isVisible")
        if (isVisible) {
            start()
        } else {
            clear()
        }
    }

    /** Disconnects the player from a live extractor and clears its comments. */
    fun destroy() {
        val bulletCommentsPlayer = bcPlayer
        if (bulletCommentsPlayer != null) {
            bulletCommentsPlayer.disconnect()
            clear()
        }
    }

    private fun currentPositionDuration(): Duration? {
        if (player.currentItem == null) {
            return null
        }
        return Duration.ofMillis(player.simpleExoPlayer.currentPosition)
    }

    private fun duration(): Duration? {
        if (player.currentItem == null) {
            return null
        }
        return Duration.ofMillis(player.simpleExoPlayer.duration)
    }

    private fun disposeDrawComments() {
        if (drawCommentsObservable != null) {
            drawCommentsObservable!!.dispose()
            drawCommentsObservable = null
            Log.d(TAG, "BulletCommentsView observable disposed.")
        }
    }
}
