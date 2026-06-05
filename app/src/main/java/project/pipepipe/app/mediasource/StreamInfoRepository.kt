package project.pipepipe.app.mediasource

import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap

object StreamInfoRepository {
    private val streamInfos = ConcurrentHashMap<String, StreamInfo>()

    fun put(streamInfo: StreamInfo) {
        streamInfos[streamInfo.url] = streamInfo
    }

    fun get(url: String): StreamInfo? = streamInfos[url]

    fun remove(url: String) {
        streamInfos.remove(url)
    }

    fun clear() {
        streamInfos.clear()
    }
}
