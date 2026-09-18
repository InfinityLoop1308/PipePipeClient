package org.schabi.newpipe.player.mediaitem

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.PlaybackStartupTrace
import org.schabi.newpipe.util.ExtractorHelper

/**
 * Resolves the [StreamInfo] behind a queue entry.
 *
 * Extraction is deliberately kept out of [PlayerMediaItem]: the value type stays portable, and
 * whoever needs to actually play an entry injects a resolver instead of the entry carrying a
 * network call.
 */
fun interface StreamInfoResolver {
    fun streamOf(item: PlayerMediaItem): Single<StreamInfo>
}

/**
 * The default [StreamInfoResolver], backed by the app's extractor.
 */
object ExtractorStreamInfoResolver : StreamInfoResolver {
    override fun streamOf(item: PlayerMediaItem): Single<StreamInfo> =
        ExtractorHelper.getStreamInfo(item.serviceId, item.url, false)
            .subscribeOn(Schedulers.io())
            .doOnSubscribe {
                PlaybackStartupTrace.markForUrl(item.url, "stream_info_requested")
            }
            .doOnSuccess {
                PlaybackStartupTrace.markForUrl(item.url, "stream_info_ready")
            }
}
