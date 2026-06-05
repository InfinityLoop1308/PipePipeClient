package project.pipepipe.app.mediasource

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
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

@UnstableApi
class ExtractorMediaSourceFactory(
    private val dataSourceFactory: DefaultDataSource.Factory
) : MediaSource.Factory {
    constructor(context: android.content.Context) : this(
        DefaultDataSource.Factory(
            context,
            DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
        )
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
        val streamInfo = requireNotNull(StreamInfoRepository.get(mediaItem.mediaId))
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
            video?.let { add(createStreamSource(mediaItem, it)) }
            if (video == null || video.isVideoOnly()) {
                audio?.let { add(createStreamSource(mediaItem, it)) }
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

    private fun createStreamSource(mediaItem: MediaItem, stream: Stream): MediaSource =
        when (stream.deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP -> ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem.withUri(stream.content, null))
            DeliveryMethod.DASH -> createDashSource(mediaItem, stream)
            DeliveryMethod.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem.withUri(stream.content, MimeTypes.APPLICATION_M3U8))
            else -> error("Unsupported delivery method: ${stream.deliveryMethod}")
        }

    private fun createDashSource(mediaItem: MediaItem, stream: Stream): MediaSource {
        val factory = DashMediaSource.Factory(dataSourceFactory)
        if (stream.isUrl) {
            return factory.createMediaSource(mediaItem.withUri(stream.content, MimeTypes.APPLICATION_MPD))
        }
        val manifestUri = Uri.parse(stream.manifestUrl ?: "")
        val manifest = DashManifestParser().parse(
            manifestUri,
            ByteArrayInputStream(stream.content.toByteArray(StandardCharsets.UTF_8))
        )
        return factory.createMediaSource(manifest, mediaItem.withUri(manifestUri, MimeTypes.APPLICATION_MPD))
    }

    private fun MediaItem.withUri(uri: String, mimeType: String?): MediaItem =
        withUri(Uri.parse(uri), mimeType)

    private fun MediaItem.withUri(uri: Uri, mimeType: String?): MediaItem =
        buildUpon().setUri(uri).setMimeType(mimeType).build()
}
