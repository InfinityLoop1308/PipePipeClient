package org.schabi.newpipe.player.resolver

import android.net.Uri
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.dash.manifest.DashManifest
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylistParser
import com.google.android.exoplayer2.source.smoothstreaming.manifest.SsManifestParser
import org.schabi.newpipe.App
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.datasource.SabrDashMediaSource
import org.schabi.newpipe.player.datasource.SabrSessionHelper
import org.schabi.newpipe.player.helper.NonUriHlsPlaylistParserFactory
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.util.StreamTypeUtil
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Turns extracted streams into ExoPlayer [MediaSource]s.
 *
 * Each stream is played with the headers of its service ([ServicePlayback.defaultHeaders])
 * overlaid with the headers the stream declares ([Stream.getRequestHeaders]); live manifests
 * use [StreamInfo.getManifestRequestHeaders] instead. A stream is kept in the media cache if
 * it declares so ([Stream.getCacheable]), otherwise by the default of its delivery method.
 * Services that need more than that (YouTube, NicoNico, BiliBili) get a [ServicePlayback];
 * every other service, including plugins, uses the generic path chosen by [DeliveryMethod].
 */
object MediaSourceBuilder {
    private const val TAG = "MediaSourceBuilder"

    /** Everything needed to build the source of one stream. */
    internal class Request(
        val dataSource: PlayerDataSource,
        val stream: Stream,
        val info: StreamInfo,
        val cacheKey: String,
        val metadata: PlayerMediaItem,
        val initialPositionMs: Long,
        val headers: Map<String, String>,
    ) {
        /** The cache policy declared by the stream, or [default] if it declares none. */
        fun cache(default: Boolean): Boolean = stream.cacheable ?: default

        fun mediaItem(uri: String = stream.content): MediaItem = MediaItem.Builder()
            .setTag(metadata)
            .setUri(Uri.parse(uri))
            .setCustomCacheKey(cacheKey)
            .build()
    }

    /** Per-service playback rules. Defaults mean "nothing special". */
    internal interface ServicePlayback {
        fun defaultHeaders(info: StreamInfo): Map<String, String> = emptyMap()

        /** A source for [request], or `null` to use the generic path. */
        @Throws(IOException::class)
        fun build(request: Request): MediaSource? = null

        /** A live source for [url], or `null` to use the generic live path. */
        fun buildLive(
            dataSource: PlayerDataSource,
            url: String,
            metadata: PlayerMediaItem,
        ): MediaSource? = null
    }

    private object GenericPlayback : ServicePlayback

    private fun playbackOf(info: StreamInfo): ServicePlayback = when (info.serviceId) {
        ServiceList.YouTube.serviceId -> YoutubePlayback
        ServiceList.NicoNico.serviceId -> NicoNicoPlayback
        ServiceList.BiliBili.serviceId -> BiliBiliPlayback
        else -> GenericPlayback
    }

    private fun headersOf(playback: ServicePlayback, info: StreamInfo, stream: Stream) =
        playback.defaultHeaders(info) + stream.requestHeaders.orEmpty()

    // region Entry points

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun build(
        dataSource: PlayerDataSource,
        stream: Stream,
        info: StreamInfo,
        cacheKey: String,
        metadata: PlayerMediaItem,
        initialPositionMs: Long = 0,
    ): MediaSource {
        val playback = playbackOf(info)
        val request = Request(
            dataSource, stream, info, cacheKey, metadata, initialPositionMs,
            headersOf(playback, info, stream),
        )
        return playback.build(request) ?: buildGeneric(request)
    }

    /** The live source of [info], or `null` if it is not a live stream with a manifest URL. */
    @JvmStatic
    fun buildLive(dataSource: PlayerDataSource, info: StreamInfo): MediaSource? {
        if (!StreamTypeUtil.isLiveStream(info.streamType)) {
            return null
        }
        // Prefer DASH over HLS because of an ExoPlayer bug that causes the background player
        // to also fetch the video stream even if it is supposed to just fetch the audio stream.
        val (url, type) = when {
            !info.dashMpdUrl.isNullOrEmpty() -> info.dashMpdUrl to C.CONTENT_TYPE_DASH
            !info.hlsUrl.isNullOrEmpty() -> info.hlsUrl to C.CONTENT_TYPE_HLS
            else -> return null
        }
        val playback = playbackOf(info)
        val metadata = PlayerMediaItem.forStreamInfo(info)
        playback.buildLive(dataSource, url, metadata)?.let { return it }

        val headers = playback.defaultHeaders(info) + info.manifestRequestHeaders.orEmpty()
        val factory = when (type) {
            C.CONTENT_TYPE_DASH -> dataSource.liveDashFactory(
                headers, youtubeManifest = playback === YoutubePlayback
            )
            else -> dataSource.liveHlsFactory(headers)
        }
        return factory.createMediaSource(liveMediaItem(url, metadata))
    }

    /** A sideloaded subtitle source; [subtitle] must have a URL as content. */
    @JvmStatic
    fun buildSubtitle(
        dataSource: PlayerDataSource,
        info: StreamInfo,
        subtitle: SubtitlesStream,
        configuration: MediaItem.SubtitleConfiguration,
    ): MediaSource {
        val headers = headersOf(playbackOf(info), info, subtitle)
        return dataSource.singleSampleFactory(headers, subtitle.cacheable ?: true)
            .createMediaSource(configuration, C.TIME_UNSET)
    }

    // endregion
    // region Generic path

    @Throws(IOException::class)
    private fun buildGeneric(request: Request): MediaSource {
        val stream = request.stream
        if (stream.isUrl && stream.content.isNullOrEmpty()) {
            throw IOException(
                "Try to generate a ${stream.deliveryMethod} media source from an empty URL"
            )
        }
        val ds = request.dataSource
        val headers = request.headers
        return when (stream.deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP -> ds.progressiveFactory(headers, request.cache(false))
                .createMediaSource(request.mediaItem())

            DeliveryMethod.DASH -> if (stream.isUrl) {
                ds.dashFactory(headers, request.cache(true)).createMediaSource(request.mediaItem())
            } else {
                ds.dashFactory(headers, request.cache(true)).createMediaSource(
                    parseDashManifest(stream.content, stream),
                    request.mediaItem(manifestBaseUrl(stream)),
                )
            }

            DeliveryMethod.HLS -> if (stream.isUrl) {
                ds.hlsFactory(headers, request.cache(true)).createMediaSource(request.mediaItem())
            } else {
                val playlist = parseManifest("HLS", stream.content) {
                    HlsPlaylistParser().parse(Uri.parse(manifestBaseUrl(stream)), it)
                }
                val parserFactory = NonUriHlsPlaylistParserFactory(playlist)
                ds.hlsFactory(headers, request.cache(true), parserFactory)
                    .createMediaSource(request.mediaItem())
            }

            DeliveryMethod.SS -> if (stream.isUrl) {
                ds.ssFactory(headers, request.cache(false)).createMediaSource(request.mediaItem())
            } else {
                val manifest = parseManifest("SmoothStreaming", stream.content) {
                    SsManifestParser().parse(Uri.parse(manifestBaseUrl(stream)), it)
                }
                ds.ssFactory(headers, request.cache(false))
                    .createMediaSource(manifest, request.mediaItem(manifestBaseUrl(stream)))
            }

            // Torrent streams are not supported by ExoPlayer
            else -> throw IllegalArgumentException(
                "Unsupported delivery type: ${stream.deliveryMethod}"
            )
        }
    }

    private fun liveMediaItem(url: String, metadata: PlayerMediaItem): MediaItem =
        MediaItem.Builder()
            .setTag(metadata)
            .setUri(Uri.parse(url))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(PlayerDataSource.LIVE_STREAM_EDGE_GAP_MILLIS.toLong())
                    .build()
            )
            .build()

    private fun manifestBaseUrl(stream: Stream): String = stream.manifestUrl ?: ""

    @Throws(IOException::class)
    private fun <T> parseManifest(
        kind: String,
        manifest: String,
        parse: (ByteArrayInputStream) -> T,
    ): T = try {
        parse(ByteArrayInputStream(manifest.toByteArray(StandardCharsets.UTF_8)))
    } catch (e: IOException) {
        throw IOException("Error when parsing manual $kind manifest", e)
    }

    @Throws(IOException::class)
    private fun parseDashManifest(manifest: String, stream: Stream): DashManifest =
        parseManifest("DASH", manifest) {
            DashManifestParser().parse(Uri.parse(manifestBaseUrl(stream)), it)
        }

    // endregion
    // region YouTube

    private object YoutubePlayback : ServicePlayback {
        override fun build(request: Request): MediaSource {
            val stream = request.stream
            if (stream !is AudioStream && stream !is VideoStream) {
                throw IOException(
                    "Try to generate a DASH manifest of a YouTube ${stream.javaClass} " +
                        stream.content
                )
            }
            return when (request.info.streamType) {
                StreamType.VIDEO_STREAM -> buildVideo(request)
                StreamType.POST_LIVE_STREAM -> buildPostLive(request)
                else -> throw IllegalArgumentException(
                    "DASH manifest generation of YouTube livestreams is not supported"
                )
            }
        }

        private fun buildPostLive(request: Request): MediaSource {
            val stream = request.stream
            if (stream.deliveryMethod == DeliveryMethod.HLS) {
                return buildGeneric(request)
            }
            // A non-URL DASH post live stream is an ended livestream: the manifest has to be
            // generated from its content, which is the last segment of the stream.
            val manifest = generateManifest("ended live stream", stream) { itagItem ->
                YoutubePostLiveStreamDvrDashManifestCreator.fromPostLiveStreamDvrStreamingUrl(
                    stream.content, itagItem, itagItem.targetDurationSec, request.info.duration
                )
            }
            return manualDash(request, manifest)
        }

        /** Runs a YouTube DASH manifest creator, turning its failures into an [IOException]. */
        @Throws(IOException::class)
        private fun generateManifest(
            kind: String,
            stream: Stream,
            create: (ItagItem) -> String,
        ): String {
            val itagItem = stream.itagItem
                ?: throw IOException("No itag item for YouTube $kind ${stream.content}")
            return try {
                create(itagItem)
            } catch (e: CreationException) {
                Log.e(TAG, "Error when generating the DASH manifest of YouTube $kind", e)
                throw IOException(
                    "Error when generating the DASH manifest of YouTube $kind ${stream.content}",
                    e,
                )
            }
        }

        private fun buildVideo(request: Request): MediaSource {
            val stream = request.stream
            val ds = request.dataSource
            return when (stream.deliveryMethod) {
                DeliveryMethod.PROGRESSIVE_HTTP -> {
                    val separated = (stream is VideoStream && stream.isVideoOnly()) ||
                        stream is AudioStream
                    if (!separated) {
                        // Legacy progressive streams, subtitles are handled by
                        // VideoPlaybackResolver
                        return progressive(request)
                    }
                    try {
                        val manifest = generateManifest("progressive stream", stream) {
                            YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                                stream.content, it, request.info.duration
                            )
                        }
                        manualDash(request, manifest)
                    } catch (e: IOException) {
                        Log.w(
                            TAG, "Error when generating or parsing DASH manifest of YouTube " +
                                "progressive stream, falling back to a ProgressiveMediaSource.",
                            e
                        )
                        progressive(request)
                    }
                }

                // A non-URL DASH video stream is an OTF stream: the manifest has to be
                // generated from its content, which is the base URL of the OTF stream.
                DeliveryMethod.DASH -> manualDash(
                    request,
                    generateManifest("OTF stream", stream) {
                        YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(
                            stream.content, it, request.info.duration
                        )
                    },
                )

                DeliveryMethod.HLS -> ds.youtubeHlsFactory()
                    .createMediaSource(request.mediaItem())

                DeliveryMethod.SABR -> sabr(request)

                else -> throw IOException(
                    "Unsupported delivery method for YouTube contents: ${stream.deliveryMethod}"
                )
            }
        }

        private fun manualDash(request: Request, manifest: String): MediaSource =
            request.dataSource.youtubeDashFactory().createMediaSource(
                parseDashManifest(manifest, request.stream), request.mediaItem()
            )

        private fun progressive(request: Request): MediaSource =
            request.dataSource.youtubeProgressiveFactory().createMediaSource(request.mediaItem())

        private fun sabr(request: Request): MediaSource {
            val stream = request.stream
            val videoId = request.info.id
            val preferredVideoItag = (stream as? VideoStream)?.getItag() ?: 0
            val sabrInfo = stream.deliveryMethodInfo as? YoutubeSabrInfo
            val spec = try {
                SabrSessionHelper.createSourceSpec(
                    videoId, preferredVideoItag, request.info.audioStreams, sabrInfo
                )
            } catch (e: ExtractionException) {
                throw IOException("Could not describe SABR source for $videoId", e)
            }
            return SabrDashMediaSource(
                App.getApp(), request.mediaItem("sabr://$videoId"), spec,
                request.dataSource, request.initialPositionMs,
            )
        }
    }

    // endregion
    // region NicoNico

    private object NicoNicoPlayback : ServicePlayback {
        private const val COOKIE_MARKER = "#cookie="
        private const val LENGTH_MARKER = "&length="
        private const val LIVE_WATCH_URL = "live.nicovideo.jp/watch"

        /**
         * NicoNico HLS variants carry their session cookie in a `#cookie=...&length=...` URL
         * fragment (also read by the downloader); it is sent as a `Cookie` header instead.
         */
        override fun build(request: Request): MediaSource? {
            val content = request.stream.content
            val markerIndex = content.indexOf(COOKIE_MARKER)
            if (markerIndex < 0) {
                return null
            }
            val fragment = URLDecoder.decode(
                content.substring(markerIndex + COOKIE_MARKER.length),
                StandardCharsets.UTF_8.name(),
            )
            val headers = request.headers + ("Cookie" to fragment.substringBefore(LENGTH_MARKER))
            return request.dataSource.hlsFactory(headers, request.cache(true))
                .createMediaSource(request.mediaItem(content.substring(0, markerIndex)))
        }

        override fun buildLive(
            dataSource: PlayerDataSource,
            url: String,
            metadata: PlayerMediaItem,
        ): MediaSource? {
            if (!url.contains(LIVE_WATCH_URL)) {
                return null
            }
            return dataSource.nicoLiveHlsFactory(url)
                .createMediaSource(liveMediaItem(url, metadata))
        }
    }

    // endregion
    // region BiliBili

    private object BiliBiliPlayback : ServicePlayback {
        private val HEADERS = mapOf("Referer" to "https://www.bilibili.com")

        override fun defaultHeaders(info: StreamInfo) = HEADERS

        override fun build(request: Request): MediaSource {
            val ds = request.dataSource
            val manifest = BiliBiliDashManifest.create(request.stream, request.info)
            if (manifest != null) {
                return ds.dashFactory(request.headers, request.cache(true)).createMediaSource(
                    parseDashManifest(manifest, request.stream), request.mediaItem()
                )
            }
            val cache = request.cache(!request.info.url.contains("live.bilibili.com"))
            return ds.progressiveFactory(request.headers, cache)
                .createMediaSource(request.mediaItem())
        }
    }

    /**
     * A single-representation DASH manifest for a BiliBili stream with known init/index
     * ranges, so ExoPlayer can seek without downloading the whole file, or `null` if the
     * stream lacks the data.
     */
    private object BiliBiliDashManifest {
        fun create(stream: Stream, info: StreamInfo): String? {
            val initStart: Int
            val initEnd: Int
            val indexStart: Int
            val indexEnd: Int
            val mimeType: String
            val codecs: String?
            val bandwidth: Int
            val extraAttributes: String
            when (stream) {
                is AudioStream -> {
                    initStart = stream.initStart
                    initEnd = stream.initEnd
                    indexStart = stream.indexStart
                    indexEnd = stream.indexEnd
                    mimeType = "audio/mp4"
                    codecs = stream.codec
                    bandwidth = if (stream.bitrate > 0) stream.bitrate else stream.averageBitrate
                    extraAttributes = ""
                }
                is VideoStream -> {
                    initStart = stream.initStart
                    initEnd = stream.initEnd
                    indexStart = stream.indexStart
                    indexEnd = stream.indexEnd
                    mimeType = "video/mp4"
                    codecs = stream.codec
                    bandwidth = stream.bitrate
                    extraAttributes = buildString {
                        if (stream.getWidth() > 0) append(" width=\"${stream.getWidth()}\"")
                        if (stream.getHeight() > 0) append(" height=\"${stream.getHeight()}\"")
                        if (stream.getFps() > 0) append(" frameRate=\"${stream.getFps()}\"")
                    }
                }
                else -> return null
            }
            if (initEnd <= initStart || indexEnd <= indexStart || bandwidth <= 0 ||
                codecs.isNullOrEmpty()
            ) {
                return null
            }
            val contentType = if (stream is AudioStream) "audio" else "video"
            val duration = maxOf(1L, info.duration)
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" " +
                "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" " +
                "minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"PT${duration}S\">" +
                "<Period duration=\"PT${duration}S\">" +
                "<AdaptationSet contentType=\"$contentType\" mimeType=\"$mimeType\" " +
                "subsegmentAlignment=\"true\">" +
                "<Representation id=\"${escapeXml(stream.id)}\" bandwidth=\"$bandwidth\" " +
                "codecs=\"${escapeXml(codecs)}\"$extraAttributes>" +
                "<BaseURL>${escapeXml(stream.content)}</BaseURL>" +
                "<SegmentBase indexRange=\"$indexStart-$indexEnd\">" +
                "<Initialization range=\"$initStart-$initEnd\"/>" +
                "</SegmentBase>" +
                "</Representation>" +
                "</AdaptationSet>" +
                "</Period>" +
                "</MPD>"
        }

        private fun escapeXml(value: String): String = value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    // endregion
}
