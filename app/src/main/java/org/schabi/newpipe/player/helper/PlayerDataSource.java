package org.schabi.newpipe.player.helper;

import static org.schabi.newpipe.extractor.ServiceList.Niconico;

import android.content.Context;
import android.net.Uri;

import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.SingleSampleMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.hls.playlist.DefaultHlsPlaylistTracker;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.ResolvingDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import androidx.annotation.NonNull;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.niconico.NiconicoService;
import org.schabi.newpipe.extractor.services.niconico.extractors.NiconicoDMCPayloadBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerDataSource {

    public static final int LIVE_STREAM_EDGE_GAP_MILLIS = 10000;

    /**
     * An approximately 4.3 times greater value than the
     * {@link DefaultHlsPlaylistTracker#DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT default}
     * to ensure that (very) low latency livestreams which got stuck for a moment don't crash too
     * early.
     */
    private static final double PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 15;
    private static final int MANIFEST_MINIMUM_RETRY = 5;
    private static final int EXTRACTOR_MINIMUM_RETRY = Integer.MAX_VALUE;

    private final int continueLoadingCheckIntervalBytes;
    private final DataSource.Factory cacheDataSourceFactory;
    private final DataSource.Factory cachelessDataSourceFactory;

    public PlayerDataSource(@NonNull final Context context,
                            @NonNull final String userAgent,
                            @NonNull final TransferListener transferListener) {
        continueLoadingCheckIntervalBytes = PlayerHelper.getProgressiveLoadIntervalBytes(context);
        cacheDataSourceFactory = new CacheFactory(context, userAgent, transferListener);
        cachelessDataSourceFactory = new DefaultDataSource
                .Factory(context, new DefaultHttpDataSource.Factory().setUserAgent(userAgent)
                .setDefaultRequestProperties(Map.of("Referer", "https://www.bilibili.com")))
                .setTransferListener(transferListener);
    }

    public ProgressiveMediaSource.Factory getNicoDataSource() {

        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Content-Type", Collections.singletonList("application/json"));

        DataSource.Factory newFactory = new ResolvingDataSource.Factory(cachelessDataSourceFactory, dataSpec -> {
            DownloaderImpl downloader = DownloaderImpl.getInstance();
            Response response;
            try {
                response = downloader.get(String.valueOf(dataSpec.uri), null, NiconicoService.LOCALE);
                final Document page = Jsoup.parse(response.responseBody());
                JsonObject watch = JsonParser.object().from(
                        page.getElementById("js-initial-watch-data").attr("data-api-data"));
                final JsonObject session
                        = watch.getObject("media").getObject("delivery").getObject("movie");
                final String s = NiconicoDMCPayloadBuilder.buildJSON(session.getObject("session"));
                response = downloader.post("https://api.dmc.nico/api/sessions?_format=json", headers, s.getBytes(StandardCharsets.UTF_8), NiconicoService.LOCALE);
                final JsonObject content = JsonParser.object().from(response.responseBody());
                final String contentURL = content.getObject("data").getObject("session")
                        .getString("content_uri");
                return dataSpec.withUri(Uri.parse(contentURL));
            } catch (ReCaptchaException | JsonParserException e) {
                e.printStackTrace();
            }
            return dataSpec;
        });
        return new ProgressiveMediaSource.Factory(newFactory)
                .setContinueLoadingCheckIntervalBytes(continueLoadingCheckIntervalBytes)
                .setLoadErrorHandlingPolicy(
                        new DefaultLoadErrorHandlingPolicy(EXTRACTOR_MINIMUM_RETRY));
    }

    public SsMediaSource.Factory getLiveSsMediaSourceFactory() {
        return new SsMediaSource.Factory(
                new DefaultSsChunkSource.Factory(cachelessDataSourceFactory),
                cachelessDataSourceFactory
        )
                .setLoadErrorHandlingPolicy(
                        new DefaultLoadErrorHandlingPolicy(MANIFEST_MINIMUM_RETRY))
                .setLivePresentationDelayMs(LIVE_STREAM_EDGE_GAP_MILLIS);
    }

    public HlsMediaSource.Factory getLiveHlsMediaSourceFactory() {
        return new HlsMediaSource.Factory(cachelessDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(
                        MANIFEST_MINIMUM_RETRY))
                .setPlaylistTrackerFactory((dataSourceFactory, loadErrorHandlingPolicy,
                                            playlistParserFactory) ->
                        new DefaultHlsPlaylistTracker(dataSourceFactory, loadErrorHandlingPolicy,
                                playlistParserFactory, PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT)
                );
    }

    public DashMediaSource.Factory getLiveDashMediaSourceFactory() {
        return new DashMediaSource.Factory(
                getDefaultDashChunkSourceFactory(cachelessDataSourceFactory),
                cachelessDataSourceFactory
        )
                .setLoadErrorHandlingPolicy(
                        new DefaultLoadErrorHandlingPolicy(MANIFEST_MINIMUM_RETRY));
    }

    private DefaultDashChunkSource.Factory getDefaultDashChunkSourceFactory(
            final DataSource.Factory dataSourceFactory
    ) {
        return new DefaultDashChunkSource.Factory(dataSourceFactory);
    }

    public HlsMediaSource.Factory getHlsMediaSourceFactory() {
        return new HlsMediaSource.Factory(cacheDataSourceFactory);
    }

    public DashMediaSource.Factory getDashMediaSourceFactory() {
        return new DashMediaSource.Factory(
                getDefaultDashChunkSourceFactory(cacheDataSourceFactory),
                cacheDataSourceFactory
        );
    }

    public ProgressiveMediaSource.Factory getExtractorMediaSourceFactory() {
        return new ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                .setContinueLoadingCheckIntervalBytes(continueLoadingCheckIntervalBytes)
                .setLoadErrorHandlingPolicy(
                        new DefaultLoadErrorHandlingPolicy(EXTRACTOR_MINIMUM_RETRY));
    }

    public SingleSampleMediaSource.Factory getSampleMediaSourceFactory() {
        return new SingleSampleMediaSource.Factory(cacheDataSourceFactory);
    }
}
