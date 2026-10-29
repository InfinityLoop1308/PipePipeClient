package org.schabi.newpipe.player.event

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-process events between the video detail page, the main activity and the player.
 *
 * These used to be global broadcasts registered as exported receivers, so any app could send
 * them. Everything lives in the same process, so plain listeners are enough.
 *
 * Like broadcasts, events are delivered asynchronously on the main thread, to the listeners
 * registered at the time of posting.
 */
object DetailPlayerEvents {

    sealed class Event {
        /** Expand the bottom sheet holding the detail page. */
        object ShowMainPlayer : Event()

        /** Hide the bottom sheet holding the detail page. */
        object HideMainPlayer : Event()

        /** A player started playing; the mini player should be shown if it is hidden. */
        object PlayerStarted : Event()

        /** The detail page became visible. */
        object DetailResumed : Event()

        /** The detail page is not visible anymore (not sent on configuration changes). */
        object DetailStopped : Event()

        /** Seek the player to [seconds]. */
        class SeekTo(val seconds: Int) : Event()
    }

    fun interface Listener {
        fun onEvent(event: Event)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    @JvmStatic
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    @JvmStatic
    fun post(event: Event) {
        val targets = listeners.toList()
        handler.post {
            // a listener removed in the meantime must not receive the event anymore
            targets.filter { it in listeners }.forEach { it.onEvent(event) }
        }
    }

    @JvmStatic
    fun showMainPlayer() = post(Event.ShowMainPlayer)

    @JvmStatic
    fun hideMainPlayer() = post(Event.HideMainPlayer)

    @JvmStatic
    fun playerStarted() = post(Event.PlayerStarted)

    @JvmStatic
    fun detailResumed() = post(Event.DetailResumed)

    @JvmStatic
    fun detailStopped() = post(Event.DetailStopped)

    @JvmStatic
    fun seekTo(seconds: Int) = post(Event.SeekTo(seconds))
}
