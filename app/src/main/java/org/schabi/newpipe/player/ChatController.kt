package org.schabi.newpipe.player

import android.util.Log
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsExtractor
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.player.chat.ChatStore
import org.schabi.newpipe.player.chat.PinnedSc
import org.schabi.newpipe.util.utils
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Feeds [ChatStore] with the live chat / bullet comments of the current stream, for the chat tab.
 *
 * Unlike the floating bullet comments there is no track selection, rate limiting or clearing:
 * the last [MAX_ITEMS] messages up to the playback position are kept, so history can be scrolled.
 */
class ChatController(private val player: Player) {

    private companion object {
        const val TAG = "ChatController"
        const val MAX_ITEMS = 300
        const val POLL_MS = 500L
        const val SEEK_THRESHOLD_MS = 5000L
    }

    private var url: String? = null
    private var extractor: BulletCommentsExtractor? = null

    /** Complete list sorted by time, for extractors that provide everything at once. */
    private var staticItems: List<BulletCommentsInfoItem>? = null
    private var loading = false
    private var fetchDisposable: Disposable? = null
    private var pollDisposable: Disposable? = null

    /** Messages of streaming extractors. */
    private val buffer = ArrayList<BulletCommentsInfoItem>()
    private var lastPositionMs = -1L
    private var lastEndIndex = -1

    private class Entry(val item: BulletCommentsInfoItem, val start: Long, val total: Long)

    /** Super chats that may be pinned, with the playback position they start at. */
    private val scEntries = ArrayList<Entry>()

    /** Prepares the chat for the current stream, if its service supports it. */
    fun init() {
        val metadata = player.currentMetadata
        val supported = metadata != null && player.videoPlayerSelected() &&
            runCatching {
                NewPipe.getService(metadata.serviceId).serviceInfo.mediaCapabilities
                    .contains(StreamingService.ServiceInfo.MediaCapability.BULLET_COMMENTS)
            }.getOrDefault(false)
        if (!supported) {
            release()
            return
        }
        val current = url
        if (current != null && utils.DetimestampedEqual(current, metadata!!.url)) {
            return
        }
        release()
        url = metadata!!.url
        ChatStore.url = url
        loading = true
        val serviceId = metadata.serviceId
        fetchDisposable = ExtractorHelper.getBulletCommentsInfo(serviceId, metadata.url, false)
            .map { info ->
                val ex = info.bulletCommentsExtractor
                ex.reconnect()
                Pair(ex, info.relatedItems ?: emptyList<BulletCommentsInfoItem>())
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (ex, items) ->
                extractor = ex
                staticItems = if (ex.isLive) null else items.sortedBy { it.duration }
                staticItems?.forEach { addEntry(it, it.duration.toMillis()) }
                loading = false
                ChatStore.available.value =
                    !ex.isDisabled && (ex.isLive || items.isNotEmpty())
                Log.d(TAG, "Chat ready for $url, live=${ex.isLive}")
            }, { e ->
                loading = false
                Log.e(TAG, Log.getStackTraceString(e))
            })
    }

    /** Starts following the playback position. */
    fun start() {
        if (url == null || pollDisposable != null) {
            return
        }
        extractor?.reconnect()
        pollDisposable = Observable.interval(POLL_MS, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ tick() }, { e -> Log.e(TAG, Log.getStackTraceString(e)) })
    }

    fun pause() {
        pollDisposable?.dispose()
        pollDisposable = null
    }

    /** Stops the live connection while the player is idle; [start] reconnects it. */
    fun disconnect() {
        val ex = extractor
        if (ex != null && ex.isLive) {
            ex.disconnect()
        }
    }

    /** Forgets the current stream. */
    fun release() {
        pause()
        fetchDisposable?.dispose()
        fetchDisposable = null
        disconnect()
        extractor = null
        staticItems = null
        buffer.clear()
        scEntries.clear()
        lastPositionMs = -1L
        lastEndIndex = -1
        loading = false
        if (url != null) {
            url = null
            ChatStore.reset()
        }
    }

    private fun tick() {
        val ex = extractor
        if (loading || ex == null || ex.isDisabled || !player.isPlaying) {
            return
        }
        val item = player.currentItem ?: return
        var position = player.simpleExoPlayer.currentPosition
        val isLiveStream = item.streamType == StreamType.LIVE_STREAM
        if (isLiveStream && item.startAt != -1L) {
            position = Date().time - item.startAt
        }
        try {
            if (ex.isLive) {
                if (!isLiveStream && lastPositionMs >= 0 &&
                    abs(position - lastPositionMs) > SEEK_THRESHOLD_MS
                ) {
                    // chat replay: the extractor refetches from the new position
                    buffer.clear()
                    ChatStore.items.value = emptyList()
                }
                if (position >= 0) {
                    ex.setCurrentPlayPosition(position)
                }
                val fresh = ex.liveMessages
                if (!fresh.isNullOrEmpty()) {
                    buffer.addAll(fresh)
                    fresh.forEach {
                        val at = it.duration?.toMillis() ?: 0L
                        addEntry(it, if (at > 0) at else position)
                    }
                    while (buffer.size > MAX_ITEMS) {
                        buffer.removeAt(0)
                    }
                    ChatStore.items.value = buffer.toList()
                }
                publishPinned(position)
            } else {
                val list = staticItems ?: return
                publishPinned(position)
                val end = upperBound(list, position)
                if (end != lastEndIndex) {
                    lastEndIndex = end
                    ChatStore.items.value = list.subList(maxOf(0, end - MAX_ITEMS), end).toList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, Log.getStackTraceString(e))
        }
        lastPositionMs = position
    }

    private fun addEntry(item: BulletCommentsInfoItem, start: Long) {
        if (item.position != BulletCommentsInfoItem.Position.SUPERCHAT) return
        val total = item.pinnedTimeMillis
        if (total > 0) scEntries.add(Entry(item, start, total))
    }

    private fun publishPinned(position: Long) {
        val active = scEntries
            .filter { position >= it.start && position < it.start + it.total }
            .sortedWith(compareByDescending<Entry> { it.total }.thenByDescending { it.start })
            .map {
                val left = (it.start + it.total - position).toFloat() / it.total
                PinnedSc(it.item, Math.round(left * 100) / 100f)
            }
        if (active != ChatStore.pinned.value) {
            ChatStore.pinned.value = active
        }
    }

    /** Number of items whose time is not after [positionMs]. */
    private fun upperBound(list: List<BulletCommentsInfoItem>, positionMs: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].duration.toMillis() <= positionMs) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
