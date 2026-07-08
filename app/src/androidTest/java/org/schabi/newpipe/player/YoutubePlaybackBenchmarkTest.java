package org.schabi.newpipe.player;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.App;
import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.SharedWebViewRuntime;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrNextRequestPolicy;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.datasource.SabrSessionStore;
import org.schabi.newpipe.player.helper.LegacySubtitleRenderersFactory;
import org.schabi.newpipe.player.helper.LoadController;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.resolver.QualityResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end online playback benchmark. Each client is extracted once per instrumentation run;
 * repetitions reuse that StreamInfo but start with empty media/session caches.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class YoutubePlaybackBenchmarkTest {
    private static final String DEFAULT_URL =
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final long START_TIMEOUT_MS = 150_000;

    @Test
    public void compareSabrHlsAndGeneratedDash() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        assertTrue(context instanceof App);
        SharedWebViewRuntime.get(context).ensureReady(120_000L, "benchmark WebView warmup");
        final Bundle args = InstrumentationRegistry.getArguments();
        final String url = args.getString("url", DEFAULT_URL);
        final int repetitions = positive(args.getString("repetitions", "5"), "repetitions");
        final int warmups = Integer.parseInt(args.getString("warmups", "1"));
        final int playSeconds = positive(args.getString("playSeconds", "10"), "playSeconds");
        final long startPositionMs = Long.parseLong(args.getString("startPositionMs", "-1"));
        assertTrue("startPositionMs must be unset or non-negative: " + startPositionMs,
                startPositionMs >= -1);
        final int maxHeight = positive(args.getString("maxVideoHeight", "1080"),
                "maxVideoHeight");
        final String targetCodec = args.getString("targetCodec", "avc")
                .toLowerCase(Locale.ROOT);
        final int hlsExtractionRetries = positive(args.getString("hlsExtractionRetries", "5"),
                "hlsExtractionRetries");
        final boolean replacePlayerCache = Boolean.parseBoolean(
                args.getString("replacePlayerCache", "false"));
        final String pathFilter = args.getString("paths", "");
        final File playerCacheDirectory = new File(context.getFilesDir(),
                "youtube-playback-benchmark/player-responses");
        DownloaderImpl.getInstance().configureYoutubePlayerResponseCacheForBenchmark(
                playerCacheDirectory, replacePlayerCache);
        emit("PIPEPIPE_BENCHMARK_PLAYER_CACHE", new JSONObject()
                .put("directory", playerCacheDirectory.getAbsolutePath())
                .put("replace", replacePlayerCache));

        final List<Path> paths = filterPaths(Arrays.asList(
                new Path("sabr", "mweb", DeliveryMethod.SABR),
                new Path("hls", "web_safari", DeliveryMethod.HLS),
                new Path("android_vr_generated_dash", "android_vr",
                        DeliveryMethod.PROGRESSIVE_HTTP)), pathFilter);
        assertTrue("No benchmark paths selected by paths=" + pathFilter, !paths.isEmpty());
        final Map<Path, CachedExtraction> extractions = new LinkedHashMap<>();
        for (final Path path : paths) {
            final int maxAttempts = path.sourceDelivery == DeliveryMethod.HLS
                    ? hlsExtractionRetries : 1;
            StreamInfo info = null;
            long extractionMs = 0;
            int attempts = 0;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                attempts = attempt;
                final boolean replaceAttempt = replacePlayerCache
                        || (path.sourceDelivery == DeliveryMethod.HLS && attempt > 1);
                DownloaderImpl.getInstance().configureYoutubePlayerResponseCacheForBenchmark(
                        playerCacheDirectory, replaceAttempt);
                final long before = SystemClock.elapsedRealtimeNanos();
                NewPipe.setYoutubePlayerClient(path.client);
                final StreamInfo candidate = StreamInfo.getInfo(ServiceList.YouTube, url);
                extractionMs += elapsedMs(before);
                final SelectingQualityResolver selector = new SelectingQualityResolver(
                        path.sourceDelivery, maxHeight, targetCodec);
                if (selector.find(candidate) >= 0) {
                    info = candidate;
                    break;
                }
                emit("PIPEPIPE_BENCHMARK_FETCH", new JSONObject()
                        .put("path", path.name).put("client", path.client)
                        .put("extractionMs", extractionMs).put("fetchCount", attempt)
                        .put("playerCacheReplace", replaceAttempt)
                        .put("selectable", false));
            }
            DownloaderImpl.getInstance().configureYoutubePlayerResponseCacheForBenchmark(
                    playerCacheDirectory, replacePlayerCache);
            assertNotNull("No selectable " + path.name + " stream after "
                    + attempts + " extraction attempts", info);
            extractions.put(path, new CachedExtraction(info, extractionMs));
            emit("PIPEPIPE_BENCHMARK_FETCH", new JSONObject()
                    .put("path", path.name).put("client", path.client)
                    .put("extractionMs", extractionMs).put("fetchCount", attempts)
                    .put("playerCacheReplace", replacePlayerCache)
                    .put("selectable", true));
        }

        final List<Result> measured = new ArrayList<>();
        for (int round = -warmups; round < repetitions; round++) {
            final List<Path> roundOrder = new ArrayList<>(paths);
            Collections.rotate(roundOrder, Math.floorMod(round, roundOrder.size()));
            for (final Path path : roundOrder) {
                final boolean warmup = round < 0;
                final Result result = runTrial(context, path, extractions.get(path).info,
                        round, warmup, playSeconds, startPositionMs, maxHeight, targetCodec);
                emit("PIPEPIPE_BENCHMARK_RESULT", result.toJson());
                if (!warmup) {
                    measured.add(result);
                }
            }
        }
        for (final Path path : paths) {
            emit("PIPEPIPE_BENCHMARK_SUMMARY", summarize(path, measured,
                    extractions.get(path).extractionMs));
        }
    }

    private static Result runTrial(final Context context, final Path path, final StreamInfo info,
                                   final int round, final boolean warmup, final int playSeconds,
                                   final long startPositionMs,
                                   final int maxHeight, final String targetCodec) throws Exception {
        NewPipe.setYoutubePlayerClient(path.client);
        SabrSessionStore.evict(info.getId());
        final CountingTransferListener transfers = new CountingTransferListener();
        final PlayerDataSource dataSource = new PlayerDataSource(context,
                DownloaderImpl.USER_AGENT, transfers);
        // Constructing PlayerDataSource opens any persistent cache from an earlier app run; clear it
        // afterwards so the first trial is cold too, while the in-memory StreamInfo remains intact.
        PlayerDataSource.clearMediaCacheForBenchmark();
        final SelectingQualityResolver selector = new SelectingQualityResolver(
                path.sourceDelivery, maxHeight, targetCodec);
        final long resolveStart = SystemClock.elapsedRealtimeNanos();
        final MediaSource source = new VideoPlaybackResolver(context, dataSource, selector)
                .resolve(info);
        final long resolveMs = elapsedMs(resolveStart);
        assertNotNull("Resolver returned no source for " + path.name, source);
        assertNotNull("Resolver did not select a stream for " + path.name, selector.selected);
        final SabrSessionStore.Holder sabrHolder = path.sourceDelivery == DeliveryMethod.SABR
                ? sabrHolder(info.getId()) : null;
        if (sabrHolder != null) {
            sabrHolder.session.setTraceEnabled(true);
        } else if (path.sourceDelivery == DeliveryMethod.SABR) {
            throw new AssertionError("SABR benchmark session was not created");
        }

        final AtomicReference<PlaybackException> error = new AtomicReference<>();
        final AtomicLong readyNs = new AtomicLong();
        final AtomicLong frameNs = new AtomicLong();
        final AtomicLong audioNs = new AtomicLong();
        final AtomicInteger droppedFrames = new AtomicInteger();
        final AtomicInteger rebufferCount = new AtomicInteger();
        final AtomicLong rebufferNs = new AtomicLong();
        final AtomicLong bufferingStartNs = new AtomicLong();
        final AtomicBoolean started = new AtomicBoolean();
        final AtomicBoolean countRebuffers = new AtomicBoolean(true);
        final AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        final AtomicReference<SurfaceTexture> textureRef = new AtomicReference<>();
        final AtomicReference<Surface> surfaceRef = new AtomicReference<>();
        final AtomicBoolean sampleMemory = new AtomicBoolean(true);
        final long baselinePssKb = Debug.getPss();
        final AtomicLong peakPssKb = new AtomicLong(baselinePssKb);
        final Thread memorySampler = new Thread(() -> {
            while (sampleMemory.get()) {
                peakPssKb.accumulateAndGet(Debug.getPss(), Math::max);
                SystemClock.sleep(100);
            }
        }, "PlaybackBenchmarkMemory");
        memorySampler.start();

        final long uidRxBefore = TrafficStats.getUidRxBytes(Process.myUid());
        final long cpuBefore = Process.getElapsedCpuTime();
        final long prepareNs = SystemClock.elapsedRealtimeNanos();
        if (startPositionMs >= 0) {
            transfers.startSeekTrace();
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final SurfaceTexture texture = new SurfaceTexture(0);
            final Surface surface = new Surface(texture);
            final LegacySubtitleRenderersFactory renderers =
                    new LegacySubtitleRenderersFactory(context);
            renderers.setEnableDecoderFallback(true);
            final ExoPlayer player = new ExoPlayer.Builder(context, renderers)
                    .setTrackSelector(new DefaultTrackSelector(context))
                    .setLoadControl(path.sourceDelivery == DeliveryMethod.SABR
                            ? LoadController.forSabr() : new LoadController()).build();
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(final int state) {
                    final long now = SystemClock.elapsedRealtimeNanos();
                    if (state == Player.STATE_READY) {
                        readyNs.compareAndSet(0, now);
                        final long buffering = bufferingStartNs.getAndSet(0);
                        if (buffering != 0) {
                            rebufferNs.addAndGet(now - buffering);
                        }
                    } else if (state == Player.STATE_BUFFERING && started.get()
                            && countRebuffers.get()
                            && bufferingStartNs.compareAndSet(0, now)) {
                        rebufferCount.incrementAndGet();
                    }
                }

                @Override
                public void onPlayerError(final PlaybackException failure) {
                    error.compareAndSet(null, failure);
                }
            });
            player.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onRenderedFirstFrame(final EventTime eventTime, final Object output,
                                                 final long renderTimeMs) {
                    frameNs.compareAndSet(0, SystemClock.elapsedRealtimeNanos());
                    started.set(true);
                }

                @Override
                public void onAudioPositionAdvancing(final EventTime eventTime,
                                                     final long playoutStartSystemTimeMs) {
                    audioNs.compareAndSet(0, SystemClock.elapsedRealtimeNanos());
                }

                @Override
                public void onDroppedVideoFrames(final EventTime eventTime, final int count,
                                                 final long elapsedMs) {
                    droppedFrames.addAndGet(count);
                }
            });
            player.setVideoSurface(surface);
            player.setVolume(0f);
            player.setMediaSource(source);
            if (startPositionMs >= 0) {
                player.seekTo(startPositionMs);
            }
            player.prepare();
            player.play();
            textureRef.set(texture);
            surfaceRef.set(surface);
            playerRef.set(player);
        });

        long seekRecoveryMs = -1;
        SabrStats sabrStats = SabrStats.EMPTY;
        SeekTrace seekTrace = SeekTrace.EMPTY;
        try {
            waitUntil(() -> frameNs.get() != 0 || error.get() != null, START_TIMEOUT_MS);
            throwPlayerError(error.get());
            final long playbackStartPositionMs = startPositionMs >= 0 ? startPositionMs : 0;
            waitUntil(() -> position(playerRef.get(), info.getId())
                            >= playbackStartPositionMs + playSeconds * 1_000L
                    || error.get() != null, START_TIMEOUT_MS);
            throwPlayerError(error.get());
            if (startPositionMs >= 0) {
                countRebuffers.set(false);
                if (path.sourceDelivery == DeliveryMethod.SABR) {
                    sabrStats = sabrStats(info.getId());
                    seekTrace = SeekTrace.fromSabrStartup(sabrHolder, startPositionMs);
                } else {
                    seekTrace = transfers.finishSeekTrace();
                }
            } else {
                final long duration = duration(playerRef.get());
                final long target = duration == C.TIME_UNSET ? 30_000
                        : Math.max(1_000, Math.min(30_000, duration / 2));
                countRebuffers.set(false);
                final long linearBuffer = bufferingStartNs.getAndSet(0);
                if (linearBuffer != 0) {
                    rebufferNs.addAndGet(SystemClock.elapsedRealtimeNanos() - linearBuffer);
                }
                final org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
                        .TraceSnapshot sabrTraceBefore = sabrHolder == null ? null
                        : sabrHolder.session.getTraceSnapshot();
                final SeekCacheSnapshot sabrCacheBefore = SeekCacheSnapshot.fromSabr(
                        sabrHolder, target);
                transfers.startSeekTrace();
                final long seekStart = SystemClock.elapsedRealtimeNanos();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(
                        () -> playerRef.get().seekTo(target));
                waitUntil(() -> position(playerRef.get(), info.getId()) >= target + 1_000
                        || error.get() != null, START_TIMEOUT_MS);
                throwPlayerError(error.get());
                seekRecoveryMs = elapsedMs(seekStart);
                if (path.sourceDelivery == DeliveryMethod.SABR) {
                    sabrStats = sabrStats(info.getId());
                    final org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
                            .TraceSnapshot sabrTraceAfter = sabrHolder == null ? null
                            : sabrHolder.session.getTraceSnapshot();
                    final SeekCacheSnapshot sabrCacheAfter = SeekCacheSnapshot.fromSabr(
                            sabrHolder, target);
                    seekTrace = SeekTrace.fromSabr(sabrTraceBefore, sabrTraceAfter,
                            sabrCacheBefore, sabrCacheAfter);
                } else {
                    seekTrace = transfers.finishSeekTrace();
                }
            }
        } finally {
            final long openBuffer = bufferingStartNs.getAndSet(0);
            if (openBuffer != 0) {
                rebufferNs.addAndGet(SystemClock.elapsedRealtimeNanos() - openBuffer);
            }
            sampleMemory.set(false);
            memorySampler.join(2_000);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (playerRef.get() != null) {
                    playerRef.get().release();
                }
                if (surfaceRef.get() != null) {
                    surfaceRef.get().release();
                }
                if (textureRef.get() != null) {
                    textureRef.get().release();
                }
            });
            SabrSessionStore.evict(info.getId());
        }
        final long uidRxAfter = TrafficStats.getUidRxBytes(Process.myUid());
        final long uidRxBytes = uidRxBefore < 0 || uidRxAfter < 0 ? -1 : uidRxAfter - uidRxBefore;
        final long mediaBytes = path.sourceDelivery == DeliveryMethod.SABR
                ? sabrStats.responseBytes : transfers.networkBytes.get();
        return new Result(path, round, warmup, selector.selected, resolveMs,
                toMs(readyNs.get() - prepareNs), toMs(frameNs.get() - prepareNs),
                toMs(audioNs.get() - prepareNs), seekRecoveryMs, rebufferCount.get(),
                durationMs(rebufferNs.get()), droppedFrames.get(), mediaBytes, uidRxBytes,
                Process.getElapsedCpuTime() - cpuBefore, peakPssKb.get(), baselinePssKb,
                sabrStats, seekTrace);
    }

    private static SabrSessionStore.Holder sabrHolder(final String videoId) throws Exception {
        final Field sessions = SabrSessionStore.class.getDeclaredField("SESSIONS");
        sessions.setAccessible(true);
        @SuppressWarnings("unchecked") final Map<String, SabrSessionStore.Holder> values =
                (Map<String, SabrSessionStore.Holder>) sessions.get(null);
        return values.get(videoId);
    }

    private static SabrStats sabrStats(final String videoId) throws Exception {
        final SabrSessionStore.Holder holder = sabrHolder(videoId);
        if (holder == null) {
            return SabrStats.EMPTY;
        }
        final SabrNextRequestPolicy policy = holder.session.getStreamState()
                .getNextRequestPolicy();
        return new SabrStats(holder.session.getTotalResponseBytes(),
                holder.session.getRequestNumber(), holder.session.getPeakCachedBytes(),
                holder.session.getStreamState().getBandwidthEstimate(),
                policy == null ? -1 : policy.getTargetAudioReadaheadMs(),
                policy == null ? -1 : policy.getTargetVideoReadaheadMs(),
                policy == null ? -1 : policy.getMinAudioReadaheadMs(),
                policy == null ? -1 : policy.getMinVideoReadaheadMs(),
                policy == null ? -1 : policy.getMaxTimeSinceLastRequestMs());
    }

    private static JSONObject summarize(final Path path, final List<Result> all,
                                        final long extractionMs) throws Exception {
        final List<Result> values = new ArrayList<>();
        for (final Result result : all) {
            if (result.path == path) {
                values.add(result);
            }
        }
        return new JSONObject().put("path", path.name).put("client", path.client)
                .put("samples", values.size()).put("cachedExtractionMs", extractionMs)
                .put("firstFrameMsP50", percentile(values, r -> r.firstFrameMs, 0.50))
                .put("firstFrameMsP95", percentile(values, r -> r.firstFrameMs, 0.95))
                .put("seekRecoveryMsP50", percentile(values, r -> r.seekRecoveryMs, 0.50))
                .put("rebufferMsP50", percentile(values, r -> r.rebufferMs, 0.50))
                .put("mediaBytesP50", percentile(values, r -> r.mediaBytes, 0.50))
                .put("seekNetworkBytesP50", percentile(values,
                        r -> r.seekTrace.networkBytes, 0.50))
                .put("seekSabrResponseBytesP50", percentile(values,
                        r -> r.seekTrace.sabrResponseBytes, 0.50))
                .put("seekSabrMediaPayloadBytesP50", percentile(values,
                        r -> r.seekTrace.sabrMediaPayloadBytes, 0.50))
                .put("seekSabrControlPayloadBytesP50", percentile(values,
                        r -> r.seekTrace.sabrControlPayloadBytes, 0.50))
                .put("seekSabrDiscardedBytesP50", percentile(values,
                        r -> r.seekTrace.sabrDiscardedBytes, 0.50))
                .put("sabrRequestCountP50", percentile(values,
                        r -> r.sabrStats.requestCount, 0.50))
                .put("sabrPeakCachedBytesP50", percentile(values,
                        r -> r.sabrStats.peakCachedBytes, 0.50))
                .put("cpuMsP50", percentile(values, r -> r.cpuMs, 0.50))
                .put("peakPssDeltaKbP50", percentile(values, r -> r.peakPssDeltaKb, 0.50));
    }

    private interface Value { long get(Result result); }

    private static long percentile(final List<Result> values, final Value value,
                                   final double percentile) {
        final List<Long> sorted = new ArrayList<>();
        for (final Result result : values) sorted.add(value.get(result));
        sorted.sort(Comparator.naturalOrder());
        return sorted.get(Math.min(sorted.size() - 1,
                (int) Math.ceil(percentile * sorted.size()) - 1));
    }

    private static void emit(final String marker, final JSONObject json) throws Exception {
        json.put("record", marker.substring("PIPEPIPE_BENCHMARK_".length())
                .toLowerCase(Locale.ROOT));
        System.out.println(marker + " " + json);
    }

    private static int positive(final String value, final String name) {
        final int parsed = Integer.parseInt(value);
        if (parsed <= 0) throw new IllegalArgumentException(name + " must be positive");
        return parsed;
    }

    private interface Condition { boolean test() throws Exception; }

    private static void waitUntil(final Condition condition, final long timeoutMs) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (!condition.test() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50);
        }
        assertTrue("Benchmark phase timed out after " + timeoutMs + "ms", condition.test());
    }

    private static void throwPlayerError(final PlaybackException error) {
        if (error != null) throw new AssertionError("Playback failed", error);
    }

    private static long position(final ExoPlayer player, final String videoId) {
        final AtomicLong value = new AtomicLong();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> value.set(player.getCurrentPosition()));
        SabrSessionStore.updatePlayerTime(videoId, value.get());
        return value.get();
    }

    private static long duration(final ExoPlayer player) {
        final AtomicLong value = new AtomicLong();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> value.set(player.getDuration()));
        return value.get();
    }

    private static List<Path> filterPaths(final List<Path> paths, final String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return paths;
        }
        final List<String> selected = Arrays.asList(filter.toLowerCase(Locale.ROOT).split(","));
        final List<Path> filtered = new ArrayList<>();
        for (final Path path : paths) {
            if (selected.contains(path.name.toLowerCase(Locale.ROOT))) {
                filtered.add(path);
            }
        }
        return filtered;
    }

    private static long elapsedMs(final long startNs) {
        return toMs(SystemClock.elapsedRealtimeNanos() - startNs);
    }

    private static long toMs(final long ns) { return ns <= 0 ? -1 : ns / 1_000_000; }

    private static long durationMs(final long ns) { return Math.max(0, ns / 1_000_000); }

    private static final class CountingTransferListener implements TransferListener {
        private final AtomicLong networkBytes = new AtomicLong();
        private final AtomicBoolean traceSeek = new AtomicBoolean();
        private final AtomicLong seekNetworkBytes = new AtomicLong();
        private final List<String> seekTransfers = Collections.synchronizedList(new ArrayList<>());
        @Override public void onTransferInitializing(@NonNull final DataSource source,
                @NonNull final DataSpec spec, final boolean network) { }
        @Override public void onTransferStart(@NonNull final DataSource source,
                @NonNull final DataSpec spec, final boolean network) { }
        @Override public void onBytesTransferred(@NonNull final DataSource source,
                @NonNull final DataSpec spec, final boolean network, final int bytes) {
            if (network) {
                networkBytes.addAndGet(bytes);
                if (traceSeek.get()) {
                    seekNetworkBytes.addAndGet(bytes);
                }
            }
        }
        @Override public void onTransferEnd(@NonNull final DataSource source,
                @NonNull final DataSpec spec, final boolean network) {
            if (network && traceSeek.get()) {
                seekTransfers.add("uri=" + spec.uri
                        + ",position=" + spec.position
                        + ",length=" + spec.length);
            }
        }
        private void startSeekTrace() {
            seekNetworkBytes.set(0);
            seekTransfers.clear();
            traceSeek.set(true);
        }
        private SeekTrace finishSeekTrace() {
            traceSeek.set(false);
            synchronized (seekTransfers) {
                return SeekTrace.fromNetwork(seekNetworkBytes.get(),
                        new ArrayList<>(seekTransfers));
            }
        }
    }

    private static final class SelectingQualityResolver implements QualityResolver {
        private final DeliveryMethod delivery;
        private final int maxHeight;
        private final String codec;
        private VideoStream selected;
        private SelectingQualityResolver(final DeliveryMethod delivery, final int maxHeight,
                                         final String codec) {
            this.delivery = delivery; this.maxHeight = maxHeight; this.codec = codec;
        }
        private int find(final StreamInfo info) {
            final List<VideoStream> streams = new ArrayList<>(info.getVideoStreams());
            streams.addAll(info.getVideoOnlyStreams());
            return choose(streams);
        }
        private int choose(final List<VideoStream> streams) {
            int best = -1; int bestHeight = -1;
            for (int i = 0; i < streams.size(); i++) {
                final VideoStream stream = streams.get(i);
                final int height = effectiveHeight(stream);
                if (stream.getDeliveryMethod() != delivery || height > maxHeight) continue;
                final String streamCodec = stream.getCodec() == null ? ""
                        : stream.getCodec().toLowerCase(Locale.ROOT);
                if (!codec.isEmpty() && !streamCodec.isEmpty()
                        && !streamCodec.contains(codec)) continue;
                if (height > bestHeight) { best = i; bestHeight = height; }
            }
            if (best >= 0) selected = streams.get(best);
            return best;
        }
        @Override public int getDefaultResolutionIndex(final List<VideoStream> streams) {
            final int result = choose(streams);
            if (result < 0) throw new AssertionError("No stream for " + delivery);
            return result;
        }
        @Override public int getOverrideResolutionIndex(final List<VideoStream> streams,
                                                        final int selectedIndex) {
            return getDefaultResolutionIndex(streams);
        }
        @Override public int getCurrentAudioQualityIndex(final List<AudioStream> streams) { return 0; }
        private static int effectiveHeight(final VideoStream stream) {
            if (stream.getHeight() > 0) return stream.getHeight();
            final String resolution = stream.getResolution();
            if (resolution == null) return 0;
            final java.util.regex.Matcher match = java.util.regex.Pattern
                    .compile("(?:x)?(\\d{3,4})p?(?:\\d{2})?$").matcher(resolution);
            return match.find() ? Integer.parseInt(match.group(1)) : 0;
        }
    }

    private static final class Path {
        private final String name; private final String client; private final DeliveryMethod sourceDelivery;
        private Path(final String name, final String client, final DeliveryMethod sourceDelivery) {
            this.name = name; this.client = client; this.sourceDelivery = sourceDelivery;
        }
    }
    private static final class CachedExtraction {
        private final StreamInfo info; private final long extractionMs;
        private CachedExtraction(final StreamInfo info, final long extractionMs) {
            this.info = info; this.extractionMs = extractionMs;
        }
    }
    private static final class Result {
        private final Path path; private final int round; private final boolean warmup;
        private final VideoStream stream; private final long resolveMs, readyMs, firstFrameMs,
                audioMs, seekRecoveryMs, rebufferMs, mediaBytes, uidRxBytes, cpuMs, peakPssKb,
                peakPssDeltaKb;
        private final int rebufferCount, droppedFrames;
        private final SabrStats sabrStats;
        private final SeekTrace seekTrace;
        private Result(final Path path, final int round, final boolean warmup,
                       final VideoStream stream, final long resolveMs, final long readyMs,
                       final long firstFrameMs, final long audioMs, final long seekRecoveryMs,
                       final int rebufferCount, final long rebufferMs, final int droppedFrames,
                       final long mediaBytes, final long uidRxBytes, final long cpuMs,
                       final long peakPssKb, final long baselinePssKb,
                       final SabrStats sabrStats, final SeekTrace seekTrace) {
            this.path=path; this.round=round; this.warmup=warmup; this.stream=stream;
            this.resolveMs=resolveMs; this.readyMs=readyMs; this.firstFrameMs=firstFrameMs;
            this.audioMs=audioMs; this.seekRecoveryMs=seekRecoveryMs;
            this.rebufferCount=rebufferCount; this.rebufferMs=rebufferMs;
            this.droppedFrames=droppedFrames; this.mediaBytes=mediaBytes;
            this.uidRxBytes=uidRxBytes; this.cpuMs=cpuMs; this.peakPssKb=peakPssKb;
            this.peakPssDeltaKb=Math.max(0, peakPssKb-baselinePssKb);
            this.sabrStats=sabrStats;
            this.seekTrace=seekTrace;
        }
        private JSONObject toJson() throws Exception {
            return new JSONObject().put("path",path.name).put("client",path.client)
                    .put("round",round).put("warmup",warmup)
                    .put("height",SelectingQualityResolver.effectiveHeight(stream))
                    .put("itag",stream.getItag()).put("codec",String.valueOf(stream.getCodec()))
                    .put("sourceDelivery",stream.getDeliveryMethod().name())
                    .put("resolveMs",resolveMs).put("readyMs",readyMs)
                    .put("firstFrameMs",firstFrameMs).put("audioMs",audioMs)
                    .put("seekRecoveryMs",seekRecoveryMs).put("rebufferCount",rebufferCount)
                    .put("rebufferMs",rebufferMs).put("droppedFrames",droppedFrames)
                    .put("mediaBytes",mediaBytes).put("uidRxBytes",uidRxBytes)
                    .put("cpuMs",cpuMs).put("peakPssKb",peakPssKb)
                    .put("peakPssDeltaKb",peakPssDeltaKb)
                    .put("sabrRequestCount",sabrStats.requestCount)
                    .put("sabrPeakCachedBytes",sabrStats.peakCachedBytes)
                    .put("sabrBandwidthEstimate",sabrStats.bandwidthEstimate)
                    .put("sabrTargetAudioReadaheadMs",sabrStats.targetAudioReadaheadMs)
                    .put("sabrTargetVideoReadaheadMs",sabrStats.targetVideoReadaheadMs)
                    .put("sabrMinAudioReadaheadMs",sabrStats.minAudioReadaheadMs)
                    .put("sabrMinVideoReadaheadMs",sabrStats.minVideoReadaheadMs)
                    .put("sabrMaxTimeSinceLastRequestMs",sabrStats.maxTimeSinceLastRequestMs)
                    .put("seekNetworkBytes",seekTrace.networkBytes)
                    .put("seekSabrResponseBytes",seekTrace.sabrResponseBytes)
                    .put("seekSabrMediaPayloadBytes",seekTrace.sabrMediaPayloadBytes)
                    .put("seekSabrControlPayloadBytes",seekTrace.sabrControlPayloadBytes)
                    .put("seekSabrUmpOverheadBytes",seekTrace.sabrUmpOverheadBytes)
                    .put("seekSabrDiscardedBytes",seekTrace.sabrDiscardedBytes)
                    .put("seekSabrRequestCount",seekTrace.sabrRequestCount)
                    .put("seekSabrCachedBytesDelta",seekTrace.sabrCachedBytesDelta)
                    .put("seekSabrCacheBefore",seekTrace.sabrCacheBefore.toJson())
                    .put("seekSabrCacheAfter",seekTrace.sabrCacheAfter.toJson())
                    .put("seekSabrSegmentsBefore",new JSONArray(seekTrace.sabrSegmentsBefore))
                    .put("seekSabrDiscardsBefore",new JSONArray(seekTrace.sabrDiscardsBefore))
                    .put("seekSabrSegments",new JSONArray(seekTrace.sabrSegments))
                    .put("seekSabrDiscards",new JSONArray(seekTrace.sabrDiscards))
                    .put("seekTransfers",new JSONArray(seekTrace.transfers));
        }
    }

    private static final class SeekCacheSnapshot {
        private static final SeekCacheSnapshot EMPTY = new SeekCacheSnapshot(-1, -1, -1, -1,
                false, -1, false, -1, false, -1, -1, -1, false, -1, -1, -1, -1, -1);
        private final long targetMs;
        private final int videoSeq;
        private final long videoStartMs, videoEndMs;
        private final boolean videoHit;
        private final int previousVideoSeq;
        private final boolean previousVideoHit;
        private final int nextVideoSeq;
        private final boolean nextVideoHit;
        private final int audioSeq;
        private final long audioStartMs, audioEndMs;
        private final boolean audioHit;
        private final long edgeMs, videoBufferedEndMs, audioBufferedEndMs, cachedBytes,
                requestNumber;

        private SeekCacheSnapshot(final long targetMs,
                                  final int videoSeq,
                                  final long videoStartMs,
                                  final long videoEndMs,
                                  final boolean videoHit,
                                  final int previousVideoSeq,
                                  final boolean previousVideoHit,
                                  final int nextVideoSeq,
                                  final boolean nextVideoHit,
                                  final int audioSeq,
                                  final long audioStartMs,
                                  final long audioEndMs,
                                  final boolean audioHit,
                                  final long edgeMs,
                                  final long videoBufferedEndMs,
                                  final long audioBufferedEndMs,
                                  final long cachedBytes,
                                  final long requestNumber) {
            this.targetMs = targetMs;
            this.videoSeq = videoSeq;
            this.videoStartMs = videoStartMs;
            this.videoEndMs = videoEndMs;
            this.videoHit = videoHit;
            this.previousVideoSeq = previousVideoSeq;
            this.previousVideoHit = previousVideoHit;
            this.nextVideoSeq = nextVideoSeq;
            this.nextVideoHit = nextVideoHit;
            this.audioSeq = audioSeq;
            this.audioStartMs = audioStartMs;
            this.audioEndMs = audioEndMs;
            this.audioHit = audioHit;
            this.edgeMs = edgeMs;
            this.videoBufferedEndMs = videoBufferedEndMs;
            this.audioBufferedEndMs = audioBufferedEndMs;
            this.cachedBytes = cachedBytes;
            this.requestNumber = requestNumber;
        }

        private static SeekCacheSnapshot fromSabr(final SabrSessionStore.Holder holder,
                                                  final long targetMs) {
            if (holder == null) {
                return EMPTY;
            }
            final int videoSeq = holder.session.getStreamState()
                    .getSegmentNumberAtOrAfterTimeMs(holder.videoFormat, targetMs);
            final int previousVideoSeq = Math.max(1, videoSeq - 1);
            final int nextVideoSeq = videoSeq + 1;
            final int audioSeq = holder.session.getStreamState()
                    .getSegmentNumberAtOrAfterTimeMs(holder.audioFormat, targetMs);
            return new SeekCacheSnapshot(targetMs, videoSeq,
                    holder.session.getStreamState().getSegmentStartMs(holder.videoFormat,
                            videoSeq),
                    holder.session.getStreamState().getSegmentEndMs(holder.videoFormat,
                            videoSeq),
                    hasMediaSegment(holder, holder.videoFormat, videoSeq),
                    previousVideoSeq,
                    hasMediaSegment(holder, holder.videoFormat, previousVideoSeq),
                    nextVideoSeq,
                    hasMediaSegment(holder, holder.videoFormat, nextVideoSeq),
                    audioSeq,
                    holder.session.getStreamState().getSegmentStartMs(holder.audioFormat,
                            audioSeq),
                    holder.session.getStreamState().getSegmentEndMs(holder.audioFormat,
                            audioSeq),
                    hasMediaSegment(holder, holder.audioFormat, audioSeq),
                    holder.session.getStreamState().getMinBufferedEndMs(),
                    holder.session.getStreamState().getBufferedEndMs(holder.videoFormat),
                    holder.session.getStreamState().getBufferedEndMs(holder.audioFormat),
                    holder.session.getCachedBytes(),
                    holder.session.getRequestNumber());
        }

        private static boolean hasMediaSegment(final SabrSessionStore.Holder holder,
                                               final org.schabi.newpipe.extractor.services.youtube
                                                       .sabr.YoutubeSabrFormat format,
                                               final int sequence) {
            return holder.session.getCachedSegment(SabrSegmentRequest.media(format, sequence))
                    != null;
        }

        private JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("targetMs", targetMs)
                    .put("videoSeq", videoSeq)
                    .put("videoStartMs", videoStartMs)
                    .put("videoEndMs", videoEndMs)
                    .put("videoHit", videoHit)
                    .put("previousVideoSeq", previousVideoSeq)
                    .put("previousVideoHit", previousVideoHit)
                    .put("nextVideoSeq", nextVideoSeq)
                    .put("nextVideoHit", nextVideoHit)
                    .put("audioSeq", audioSeq)
                    .put("audioStartMs", audioStartMs)
                    .put("audioEndMs", audioEndMs)
                    .put("audioHit", audioHit)
                    .put("edgeMs", edgeMs)
                    .put("videoBufferedEndMs", videoBufferedEndMs)
                    .put("audioBufferedEndMs", audioBufferedEndMs)
                    .put("cachedBytes", cachedBytes)
                    .put("requestNumber", requestNumber);
        }
    }

    private static final class SeekTrace {
        private static final SeekTrace EMPTY = new SeekTrace(-1, -1, -1, -1, -1,
                -1, -1, -1, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                SeekCacheSnapshot.EMPTY, SeekCacheSnapshot.EMPTY,
                Collections.emptyList(), Collections.emptyList());
        private final long networkBytes, sabrResponseBytes, sabrMediaPayloadBytes,
                sabrControlPayloadBytes, sabrUmpOverheadBytes, sabrDiscardedBytes,
                sabrRequestCount, sabrCachedBytesDelta;
        private final List<String> sabrSegments, sabrDiscards, transfers,
                sabrSegmentsBefore, sabrDiscardsBefore;
        private final SeekCacheSnapshot sabrCacheBefore, sabrCacheAfter;

        private SeekTrace(final long networkBytes,
                          final long sabrResponseBytes,
                          final long sabrMediaPayloadBytes,
                          final long sabrControlPayloadBytes,
                          final long sabrUmpOverheadBytes,
                          final long sabrDiscardedBytes,
                          final long sabrRequestCount,
                          final long sabrCachedBytesDelta,
                          final List<String> sabrSegments,
                          final List<String> sabrDiscards,
                          final List<String> transfers,
                          final SeekCacheSnapshot sabrCacheBefore,
                          final SeekCacheSnapshot sabrCacheAfter,
                          final List<String> sabrSegmentsBefore,
                          final List<String> sabrDiscardsBefore) {
            this.networkBytes = networkBytes;
            this.sabrResponseBytes = sabrResponseBytes;
            this.sabrMediaPayloadBytes = sabrMediaPayloadBytes;
            this.sabrControlPayloadBytes = sabrControlPayloadBytes;
            this.sabrUmpOverheadBytes = sabrUmpOverheadBytes;
            this.sabrDiscardedBytes = sabrDiscardedBytes;
            this.sabrRequestCount = sabrRequestCount;
            this.sabrCachedBytesDelta = sabrCachedBytesDelta;
            this.sabrSegments = sabrSegments;
            this.sabrDiscards = sabrDiscards;
            this.transfers = transfers;
            this.sabrCacheBefore = sabrCacheBefore;
            this.sabrCacheAfter = sabrCacheAfter;
            this.sabrSegmentsBefore = sabrSegmentsBefore;
            this.sabrDiscardsBefore = sabrDiscardsBefore;
        }

        private static SeekTrace fromNetwork(final long networkBytes,
                                             final List<String> transfers) {
            return new SeekTrace(networkBytes, -1, -1, -1, -1, -1, -1, -1,
                    Collections.emptyList(), Collections.emptyList(),
                    transfers,
                    SeekCacheSnapshot.EMPTY, SeekCacheSnapshot.EMPTY,
                    Collections.emptyList(), Collections.emptyList());
        }

        private static SeekTrace fromSabr(
                final org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
                        .TraceSnapshot before,
                final org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
                        .TraceSnapshot after,
                final SeekCacheSnapshot cacheBefore,
                final SeekCacheSnapshot cacheAfter) {
            if (before == null || after == null) {
                return EMPTY;
            }
            return new SeekTrace(-1,
                    after.getResponseBytes() - before.getResponseBytes(),
                    after.getMediaPayloadBytes() - before.getMediaPayloadBytes(),
                    after.getControlPayloadBytes() - before.getControlPayloadBytes(),
                    after.getUmpOverheadBytes() - before.getUmpOverheadBytes(),
                    after.getDiscardedBytes() - before.getDiscardedBytes(),
                    after.getRequestNumber() - before.getRequestNumber(),
                    after.getCachedBytes() - before.getCachedBytes(),
                    delta(after.getSegments(), before.getSegments().size()),
                    delta(after.getDiscards(), before.getDiscards().size()),
                    Collections.emptyList(), cacheBefore, cacheAfter,
                    tail(before.getSegments(), 24), tail(before.getDiscards(), 24));
        }

        private static SeekTrace fromSabrStartup(final SabrSessionStore.Holder holder,
                                                 final long startPositionMs) {
            if (holder == null) {
                return EMPTY;
            }
            final org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
                    .TraceSnapshot after = holder.session.getTraceSnapshot();
            return new SeekTrace(-1,
                    after.getResponseBytes(),
                    after.getMediaPayloadBytes(),
                    after.getControlPayloadBytes(),
                    after.getUmpOverheadBytes(),
                    after.getDiscardedBytes(),
                    after.getRequestNumber(),
                    after.getCachedBytes(),
                    new ArrayList<>(after.getSegments()),
                    new ArrayList<>(after.getDiscards()),
                    Collections.emptyList(),
                    SeekCacheSnapshot.EMPTY,
                    SeekCacheSnapshot.fromSabr(holder, startPositionMs),
                    Collections.emptyList(),
                    Collections.emptyList());
        }

        private static List<String> delta(final List<String> values, final int start) {
            if (start >= values.size()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(values.subList(Math.max(0, start), values.size()));
        }

        private static List<String> tail(final List<String> values, final int count) {
            if (values.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(values.subList(Math.max(0, values.size() - count),
                    values.size()));
        }
    }

    private static final class SabrStats {
        private static final SabrStats EMPTY = new SabrStats(-1, -1, -1, -1,
                -1, -1, -1, -1, -1);
        private final long responseBytes;
        private final long requestCount;
        private final long peakCachedBytes;
        private final long bandwidthEstimate;
        private final long targetAudioReadaheadMs;
        private final long targetVideoReadaheadMs;
        private final long minAudioReadaheadMs;
        private final long minVideoReadaheadMs;
        private final long maxTimeSinceLastRequestMs;

        private SabrStats(final long responseBytes, final long requestCount,
                          final long peakCachedBytes, final long bandwidthEstimate,
                          final long targetAudioReadaheadMs, final long targetVideoReadaheadMs,
                          final long minAudioReadaheadMs, final long minVideoReadaheadMs,
                          final long maxTimeSinceLastRequestMs) {
            this.responseBytes = responseBytes;
            this.requestCount = requestCount;
            this.peakCachedBytes = peakCachedBytes;
            this.bandwidthEstimate = bandwidthEstimate;
            this.targetAudioReadaheadMs = targetAudioReadaheadMs;
            this.targetVideoReadaheadMs = targetVideoReadaheadMs;
            this.minAudioReadaheadMs = minAudioReadaheadMs;
            this.minVideoReadaheadMs = minVideoReadaheadMs;
            this.maxTimeSinceLastRequestMs = maxTimeSinceLastRequestMs;
        }
    }
}
