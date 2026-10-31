package org.schabi.newpipe.fragments.detail

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.fragments.detail.tabs.DetailTabsState
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.util.NO_SERVICE_ID
import org.schabi.newpipe.util.ExtractorHelper

/**
 * State of the video detail page which survives configuration changes: the stream shown, its
 * loading and the tabs. A rotation thus neither reloads the stream nor rebuilds the tabs.
 */
class VideoDetailViewModel : ViewModel() {

    /** Result of [load], delivered once to the fragment shown at that time. */
    sealed class LoadResult {
        class Success(val info: StreamInfo, val addToBackStack: Boolean) : LoadResult()
        class Failure(val error: Throwable, val serviceId: Int, val url: String?) : LoadResult()
    }

    /** Wraps a [LoadResult] so that a recreated fragment does not handle it a second time. */
    class Once<T>(private val value: T) {
        private var consumed = false
        val isPending get() = !consumed
        fun consume(): T? = if (consumed) null else value.also { consumed = true }
    }

    var serviceId = NO_SERVICE_ID
    var url: String? = null
    var title = ""
    var playQueue: PlayQueue? = null

    /** The stream shown, null while loading and after the page was cleared. */
    var currentInfo: StreamInfo? = null

    /** Whether [setInitialData] was called at least once. */
    var initialized = false
        private set

    val tabsState = DetailTabsState()

    private var worker: Disposable? = null
    private val _results = MutableLiveData<Once<LoadResult>>()
    val results: LiveData<Once<LoadResult>> = _results

    val isLoading: Boolean get() = worker?.isDisposed == false

    /** A result arrived while no fragment view could handle it. */
    val hasPendingResult: Boolean get() = _results.value?.isPending == true

    fun setInitialData(newServiceId: Int, newUrl: String?, newTitle: String,
                       newPlayQueue: PlayQueue?) {
        serviceId = newServiceId
        url = newUrl
        title = newTitle
        playQueue = newPlayQueue
        initialized = true
    }

    /** Swiped away: empty, but ready for the next stream. */
    val wasCleared: Boolean get() = url == null

    fun load(forceLoad: Boolean, addToBackStack: Boolean) {
        cancelLoading()
        currentInfo = null
        val loadServiceId = serviceId
        val loadUrl = url ?: return
        worker = ExtractorHelper.getStreamInfo(loadServiceId, loadUrl,
            forceLoad || loadUrl.contains("live.bilibili.com"))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { _results.value = Once(LoadResult.Success(it, addToBackStack)) },
                { _results.value = Once(LoadResult.Failure(it, loadServiceId, loadUrl)) },
            )
    }

    fun cancelLoading() {
        worker?.dispose()
        worker = null
    }

    override fun onCleared() {
        cancelLoading()
    }
}
