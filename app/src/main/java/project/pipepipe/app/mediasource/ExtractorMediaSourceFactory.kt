package project.pipepipe.app.mediasource

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.util.ExtractorHelper
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@UnstableApi
class ExtractorMediaSourceFactory(
    context: Context
) : MediaSource.Factory {
    private val context = context.applicationContext
    private val dataSourceFactory = createDataSourceFactory(
        mapOf("Referer" to "https://www.bilibili.com")
    )

    private fun createDataSourceFactory(headers: Map<String, String>): DefaultDataSource.Factory =
        DefaultDataSource.Factory(
            context.applicationContext,
            DefaultHttpDataSource.Factory()
                .setUserAgent(DownloaderImpl.USER_AGENT)
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
        )

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider
    ): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy
    ): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray = intArrayOf(
        C.CONTENT_TYPE_DASH,
        C.CONTENT_TYPE_HLS,
        C.CONTENT_TYPE_OTHER
    )

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val streamInfo = StreamInfoRepository.get(mediaItem.mediaId)
            ?: ExtractorHelper.getNewStreamInfo(
                mediaItem.mediaMetadata.extras?.getInt(MediaItemFactory.KEY_SERVICE_ID) ?: -1,
                mediaItem.mediaId
            ).also(StreamInfoRepository::put)
        return createMediaSource(mediaItem, streamInfo)
    }

    fun createMediaSource(mediaItem: MediaItem, streamInfo: StreamInfo): MediaSource {
        if (streamInfo.dashMpdUrl.isNotEmpty()) {
            return DashMediaSource.Factory(dataSourceFactory).createMediaSource(
                mediaItem.withUri(streamInfo.dashMpdUrl, MimeTypes.APPLICATION_MPD)
            )
        }
        if (streamInfo.hlsUrl.isNotEmpty()) {
            return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                mediaItem.withUri(streamInfo.hlsUrl, MimeTypes.APPLICATION_M3U8)
            )
        }

        val video = selectVideo(streamInfo)
        val audio = selectAudio(streamInfo)
        val sources = buildList {
            video?.let { add(createStreamSource(mediaItem, it, streamInfo)) }
            if (video == null || video.isVideoOnly()) {
                audio?.let { add(createStreamSource(mediaItem, it, streamInfo)) }
            }
        }
        require(sources.isNotEmpty())
        return if (sources.size == 1) sources.single() else MergingMediaSource(*sources.toTypedArray())
    }

    private fun selectVideo(streamInfo: StreamInfo): VideoStream? =
        (streamInfo.videoStreams + streamInfo.videoOnlyStreams)
            .filter { it.deliveryMethod != DeliveryMethod.TORRENT }
            .maxWithOrNull(compareBy<VideoStream> { it.height }.thenBy { it.fps })

    private fun selectAudio(streamInfo: StreamInfo): AudioStream? =
        streamInfo.audioStreams
            .filter { it.deliveryMethod != DeliveryMethod.TORRENT }
            .maxByOrNull { it.averageBitrate }

    private fun createStreamSource(
        mediaItem: MediaItem,
        stream: Stream,
        streamInfo: StreamInfo
    ): MediaSource {
        if (streamInfo.service == ServiceList.YouTube) {
            return createYoutubeStreamSource(mediaItem, stream, streamInfo)
        }
        if (streamInfo.service == ServiceList.NicoNico && stream.content.contains("#cookie=")) {
            val sourceUrl = stream.content.substringBefore("#cookie=")
            val cookie = URLDecoder.decode(
                stream.content.substringAfter("#cookie=").substringBefore("&length="),
                StandardCharsets.UTF_8.name()
            )
            return HlsMediaSource.Factory(createDataSourceFactory(mapOf("Cookie" to cookie)))
                .createMediaSource(mediaItem.withUri(sourceUrl, MimeTypes.APPLICATION_M3U8))
        }
        if (streamInfo.service == ServiceList.BiliBili) {
            return ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem.withUri(stream.content, null))
        }
        return when (stream.deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP -> ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem.withUri(stream.content, null))
            DeliveryMethod.DASH -> createDashSource(mediaItem, stream)
            DeliveryMethod.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem.withUri(stream.content, MimeTypes.APPLICATION_M3U8))
            else -> error("Unsupported delivery method: ${stream.deliveryMethod}")
        }
    }

    private fun createYoutubeStreamSource(
        mediaItem: MediaItem,
        stream: Stream,
        streamInfo: StreamInfo
    ): MediaSource {
        if (streamInfo.streamType == StreamType.POST_LIVE_STREAM) {
            val itag = requireNotNull(stream.itagItem)
            val manifest = YoutubePostLiveStreamDvrDashManifestCreator
                .fromPostLiveStreamDvrStreamingUrl(
                    stream.content,
                    itag,
                    itag.targetDurationSec,
                    streamInfo.duration
                )
            return createDashManifestSource(mediaItem, manifest, stream.content)
        }
        return when (stream.deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP -> {
                if ((stream is VideoStream && stream.isVideoOnly()) || stream is AudioStream) {
                    runCatching {
                        YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                            stream.content,
                            requireNotNull(stream.itagItem),
                            streamInfo.duration
                        )
                    }.fold(
                        onSuccess = { createDashManifestSource(mediaItem, it, stream.content) },
                        onFailure = {
                            ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(mediaItem.withUri(stream.content, null))
                        }
                    )
                } else {
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem.withUri(stream.content, null))
                }
            }
            DeliveryMethod.DASH -> {
                val manifest = YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(
                    stream.content,
                    requireNotNull(stream.itagItem),
                    streamInfo.duration
                )
                createDashManifestSource(mediaItem, manifest, stream.content)
            }
            DeliveryMethod.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem.withUri(stream.content, MimeTypes.APPLICATION_M3U8))
            else -> error("Unsupported YouTube delivery method: ${stream.deliveryMethod}")
        }
    }

    private fun createDashSource(mediaItem: MediaItem, stream: Stream): MediaSource {
        val factory = DashMediaSource.Factory(dataSourceFactory)
        if (stream.isUrl) {
            return factory.createMediaSource(mediaItem.withUri(stream.content, MimeTypes.APPLICATION_MPD))
        }
        return createDashManifestSource(mediaItem, stream.content, stream.manifestUrl ?: "")
    }

    private fun createDashManifestSource(
        mediaItem: MediaItem,
        manifestContent: String,
        manifestUrl: String
    ): MediaSource {
        val manifestUri = Uri.parse(manifestUrl)
        val manifest = DashManifestParser().parse(
            manifestUri,
            ByteArrayInputStream(manifestContent.toByteArray(StandardCharsets.UTF_8))
        )
        return DashMediaSource.Factory(dataSourceFactory)
            .createMediaSource(manifest, mediaItem.withUri(manifestUri, MimeTypes.APPLICATION_MPD))
    }

    private fun MediaItem.withUri(uri: String, mimeType: String?): MediaItem =
        withUri(Uri.parse(uri), mimeType)

    private fun MediaItem.withUri(uri: Uri, mimeType: String?): MediaItem =
        buildUpon().setUri(uri).setMimeType(mimeType).build()
}
