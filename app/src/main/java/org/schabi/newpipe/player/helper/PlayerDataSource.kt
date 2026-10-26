package org.schabi.newpipe.player.helper

import android.content.Context
import android.net.Uri
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.SingleSampleMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.hls.DefaultHlsExtractorFactory
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.hls.playlist.DefaultHlsPlaylistTracker
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylistParserFactory
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy
import com.google.android.exoplayer2.upstream.ResolvingDataSource
import com.google.android.exoplayer2.upstream.TransferListener
import com.google.android.exoplayer2.upstream.cache.CacheKeyFactory
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import org.jsoup.Jsoup
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.niconico.NicoWebSocketClient
import org.schabi.newpipe.extractor.services.niconico.NiconicoService
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.player.datasource.YoutubeHttpDataSource
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Builds the ExoPlayer media source factories used by the player.
 *
 * Every factory is created on demand from immutable parts, so the request headers of one
 * stream never end up on the requests of another. Media requests go through
 * [PurifiedHttpDataSource], except YouTube which needs [YoutubeHttpDataSource] and NicoNico
 * live which needs [NiconicoLiveHttpDataSource].
 */
class PlayerDataSource(
    private val context: Context,
    private val userAgent: String,
    private val transferListener: TransferListener,
) {
    private val continueLoadingCheckIntervalBytes =
        PlayerHelper.getProgressiveLoadIntervalBytes(context)

    private var nicoWebSocketClient: NicoWebSocketClient? = null

    init {
        YoutubeProgressiveDashManifestCreator.getCache()
            .setMaximumSize(MAXIMUM_SIZE_CACHED_GENERATED_MANIFESTS_PER_CACHE)
        YoutubeOtfDashManifestCreator.getCache()
            .setMaximumSize(MAXIMUM_SIZE_CACHED_GENERATED_MANIFESTS_PER_CACHE)
        YoutubePostLiveStreamDvrDashManifestCreator.getCache()
            .setMaximumSize(MAXIMUM_SIZE_CACHED_GENERATED_MANIFESTS_PER_CACHE)
    }

    // region Upstream

    /**
     * HTTP upstream sending [headers] on every request. A `User-Agent` entry replaces the
     * default user agent instead of being sent twice.
     */
    private fun httpFactory(headers: Map<String, String>): PurifiedHttpDataSource.Factory {
        var ua = userAgent
        val properties = HashMap<String, String>()
        for ((key, value) in headers) {
            if (key.equals(USER_AGENT_HEADER, ignoreCase = true)) {
                ua = value
            } else {
                properties[key] = value
            }
        }
        return PurifiedHttpDataSource.Factory()
            .setUserAgent(ua)
            .setDefaultRequestProperties(properties)
    }

    private fun cacheless(upstream: DataSource.Factory): DataSource.Factory =
        DefaultDataSource.Factory(context, upstream).setTransferListener(transferListener)

    private fun cached(
        upstream: DataSource.Factory,
        cacheKeyFactory: CacheKeyFactory? = null,
    ): DataSource.Factory = CacheFactory(context, transferListener, upstream, cacheKeyFactory)

    private fun source(headers: Map<String, String>, cache: Boolean): DataSource.Factory =
        httpFactory(headers).let { if (cache) cached(it) else cacheless(it) }

    /** Wraps a custom playback source in the same disk cache used by other media sources. */
    fun getCacheDataSourceFactory(
        upstreamDataSourceFactory: DataSource.Factory,
        cacheKeyFactory: CacheKeyFactory,
    ): DataSource.Factory = cached(upstreamDataSourceFactory, cacheKeyFactory)

    // endregion
    // region Generic factories

    fun progressiveFactory(
        headers: Map<String, String>,
        cache: Boolean,
    ): ProgressiveMediaSource.Factory =
        ProgressiveMediaSource.Factory(source(headers, cache))
            .setContinueLoadingCheckIntervalBytes(continueLoadingCheckIntervalBytes)

    fun dashFactory(headers: Map<String, String>, cache: Boolean): DashMediaSource.Factory =
        dashFactory(source(headers, cache))

    @JvmOverloads
    fun hlsFactory(
        headers: Map<String, String>,
        cache: Boolean,
        playlistParserFactory: HlsPlaylistParserFactory? = null,
    ): HlsMediaSource.Factory = HlsMediaSource.Factory(source(headers, cache)).apply {
        if (playlistParserFactory != null) {
            setPlaylistParserFactory(playlistParserFactory)
        }
    }

    fun ssFactory(headers: Map<String, String>, cache: Boolean): SsMediaSource.Factory {
        val dataSource = source(headers, cache)
        return SsMediaSource.Factory(DefaultSsChunkSource.Factory(dataSource), dataSource)
    }

    fun singleSampleFactory(
        headers: Map<String, String>,
        cache: Boolean,
    ): SingleSampleMediaSource.Factory = SingleSampleMediaSource.Factory(source(headers, cache))

    fun liveHlsFactory(headers: Map<String, String>): HlsMediaSource.Factory =
        HlsMediaSource.Factory(source(headers, cache = false))
            .setAllowChunklessPreparation(true)
            .setPlaylistTrackerFactory(::stuckTolerantPlaylistTracker)

    fun liveDashFactory(
        headers: Map<String, String>,
        youtubeManifest: Boolean,
    ): DashMediaSource.Factory = dashFactory(source(headers, cache = false)).apply {
        if (youtubeManifest) {
            setManifestParser(YoutubeDashLiveManifestParser())
        }
    }

    private fun dashFactory(dataSource: DataSource.Factory): DashMediaSource.Factory =
        DashMediaSource.Factory(DefaultDashChunkSource.Factory(dataSource), dataSource)

    // endregion
    // region YouTube

    fun youtubeDashFactory(): DashMediaSource.Factory =
        dashFactory(cached(youtubeHttpFactory(rangeParameter = true, rnParameter = true)))

    fun youtubeHlsFactory(): HlsMediaSource.Factory =
        HlsMediaSource.Factory(
            cached(youtubeHttpFactory(rangeParameter = false, rnParameter = false))
        ).setExtractorFactory(
            DefaultHlsExtractorFactory(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS, true)
        )

    fun youtubeProgressiveFactory(): ProgressiveMediaSource.Factory =
        ProgressiveMediaSource.Factory(
            cached(youtubeHttpFactory(rangeParameter = false, rnParameter = true))
        ).setContinueLoadingCheckIntervalBytes(continueLoadingCheckIntervalBytes)

    private fun youtubeHttpFactory(
        rangeParameter: Boolean,
        rnParameter: Boolean,
    ): YoutubeHttpDataSource.Factory = YoutubeHttpDataSource.Factory()
        .setRangeParameterEnabled(rangeParameter)
        .setRnParameterEnabled(rnParameter)
        .setUserAgentForNonMobileStreams(userAgent)

    // endregion
    // region NicoNico live

    fun nicoLiveHlsFactory(liveUrl: String): HlsMediaSource.Factory {
        val httpFactory = NiconicoLiveHttpDataSource.Factory(liveUrl)
            .setDefaultRequestProperties(NICO_LIVE_HEADERS)
            .setTransferListener(transferListener)
        val resolving = ResolvingDataSource.Factory(
            NiconicoLiveDataSource.Factory(context, httpFactory)
        ) { dataSpec ->
            if (dataSpec.uri.toString().contains(NICO_LIVE_WATCH)) {
                try {
                    val streamData = fetchNicoLiveStreamData(dataSpec.uri.toString())
                    httpFactory.setDefaultRequestProperty("Cookie", streamData.cookie)
                    dataSpec.withUri(Uri.parse(streamData.url))
                } catch (e: ParsingException) {
                    throw RuntimeException(e)
                } catch (e: ReCaptchaException) {
                    throw RuntimeException(e)
                } catch (e: JsonParserException) {
                    throw RuntimeException(e)
                }
            } else {
                dataSpec
            }
        }
        return HlsMediaSource.Factory(resolving)
            .setAllowChunklessPreparation(true)
            .setPlaylistTrackerFactory(::stuckTolerantPlaylistTracker)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy())
    }

    private class NicoLiveStreamData(val url: String, val cookie: String)

    @Throws(
        ParsingException::class,
        IOException::class,
        ReCaptchaException::class,
        JsonParserException::class,
    )
    private fun fetchNicoLiveStreamData(url: String): NicoLiveStreamData {
        val page = Jsoup.parse(DownloaderImpl.getInstance().get(url).responseBody())
        val webSocketUrl = JsonParser.`object`()
            .from(page.select("script#embedded-data").attr("data-props"))
            .getObject("site").getObject("relive").getString("webSocketUrl")
        disconnectWebSocketClients()
        val client = NicoWebSocketClient(
            URI.create(webSocketUrl), NiconicoService.getWebSocketHeaders()
        )
        nicoWebSocketClient = client
        val webSocketClient = client.webSocketClient
        webSocketClient.connect()
        val startTime = System.nanoTime()
        do {
            val liveUrl = client.url
            val streamCookie = client.streamCookie
            if (liveUrl != null && !streamCookie.isNullOrEmpty()) {
                return NicoLiveStreamData(liveUrl, streamCookie)
            }
        } while (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime) <= 10)
        webSocketClient.close()
        throw RuntimeException("Failed to get live url")
    }

    fun disconnectWebSocketClients() {
        try {
            nicoWebSocketClient?.disconnect()
        } catch (ignored: Exception) {
        }
    }

    // endregion

    companion object {
        const val LIVE_STREAM_EDGE_GAP_MILLIS = 10000

        /**
         * An approximately 4.3 times greater value than the
         * [DefaultHlsPlaylistTracker.DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT] default
         * to ensure that (very) low latency livestreams which got stuck for a moment don't crash
         * too early.
         */
        private const val PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 15.0

        /**
         * The maximum number of generated manifests per cache, in
         * [YoutubeProgressiveDashManifestCreator], [YoutubeOtfDashManifestCreator] and
         * [YoutubePostLiveStreamDvrDashManifestCreator].
         */
        private const val MAXIMUM_SIZE_CACHED_GENERATED_MANIFESTS_PER_CACHE = 500

        private const val USER_AGENT_HEADER = "User-Agent"
        private const val NICO_LIVE_WATCH = "live.nicovideo.jp/watch"
        private val NICO_LIVE_HEADERS = mapOf(
            "Referer" to "https://live.nicovideo.jp",
            "Origin" to "https://live.nicovideo.jp",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.90 Safari/537.36",
        )

        private fun stuckTolerantPlaylistTracker(
            dataSourceFactory: com.google.android.exoplayer2.source.hls.HlsDataSourceFactory,
            loadErrorHandlingPolicy: com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy,
            playlistParserFactory: HlsPlaylistParserFactory,
        ) = DefaultHlsPlaylistTracker(
            dataSourceFactory,
            loadErrorHandlingPolicy,
            playlistParserFactory,
            PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT,
        )

        /** Clear only downloaded media while retaining fetched extractor responses in memory. */
        @JvmStatic
        @Throws(IOException::class)
        fun clearMediaCacheForBenchmark() = CacheFactory.clearMediaCache()
    }
}
