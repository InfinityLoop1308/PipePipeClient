package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.NotificationManager;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.Surface;
import android.view.accessibility.AccessibilityEvent;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.App;
import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.downloader.StreamingResponse;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrRequestDumper;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrResponseDecoder;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.datasource.SabrDashMediaSource;
import org.schabi.newpipe.player.datasource.SabrSegmentDataSource;
import org.schabi.newpipe.player.helper.LegacySubtitleRenderersFactory;
import org.schabi.newpipe.player.helper.LoadController;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.resolver.AudioPlaybackResolver;
import org.schabi.newpipe.player.resolver.QualityResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver;
import org.schabi.newpipe.player.datasource.SabrSessionStore;
import org.schabi.newpipe.player.datasource.SabrSourceSpec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/**
 * Online smoke test for the production Extractor -> SABR MediaSource -> Media3 pipeline.
 *
 * <p>Run only this test with:</p>
 * <pre>
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * org.schabi.newpipe.player.SabrPlaybackSmokeTest \
 *   -Pandroid.testInstrumentationRunnerArguments.url=\
 * https://www.youtube.com/watch?v=G-eNlqqkn1w
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class SabrPlaybackSmokeTest {
    private static final int SMOKE_AUDIO_ITAG = 140;
    private static final int SMOKE_VIDEO_ITAG = 248;
    private static final int PROTO_WIRE_VARINT = 0;
    private static final int PROTO_WIRE_LENGTH_DELIMITED = 2;
    private static final String DEFAULT_URL =
            "https://www.youtube.com/watch?v=G-eNlqqkn1w";
    private static final String RICKROLL_URL =
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final int DEFAULT_MAX_VIDEO_HEIGHT = 720;
    private static final long DEFAULT_SEEK_POSITION_MS = (49 * 60 + 55) * 1000L;
    private static final long DEFAULT_LINEAR_PLAYBACK_MS = 3_000;
    private static final long DEFAULT_POST_SEEK_PLAYBACK_MS = 30_000;
    private static final long DEFAULT_POST_REWIND_PLAYBACK_MS = 30_000;
    private static final long PREPARE_TIMEOUT_SECONDS = 150;
    private static final long PLAYBACK_TIMEOUT_SECONDS = 75;

    @Test
    public void extractorToMedia3PlaysAndSeeks() throws Exception {
        runSmokeCase(SmokeCase.playback());
    }

    @Test
    public void anonymousSequentialAudioCrossesSabrProtectionBoundaries() throws Exception {
        final Bundle arguments = InstrumentationRegistry.getArguments();
        final String playlistUrl = arguments.getString("anonymousPlaylistUrl", "");
        assumeTrue("Set anonymousPlaylistUrl to run the sequential anonymous SABR probe",
                !playlistUrl.isEmpty());

        final Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        assertTrue("The target process must use PipePipe's App initialization",
                context instanceof App);
        final int videoCount = Integer.parseInt(arguments.getString(
                "anonymousVideoCount", "10"));
        final long playbackMs = Long.parseLong(arguments.getString(
                "anonymousPlaybackMs", "130000"));
        assertTrue("The probe must cross the 60s SABR protection boundary",
                playbackMs > 60_000);

        ServiceList.YouTube.setTokens("");
        NewPipe.setYoutubePlayerClient("mweb");

        final PlaylistInfo playlist = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl);
        assertTrue("Playlist has fewer items than requested: requested=" + videoCount
                        + " actual=" + playlist.getRelatedItems().size(),
                playlist.getRelatedItems().size() >= videoCount);

        for (int index = 0; index < videoCount; index++) {
            final StreamInfoItem item = playlist.getRelatedItems().get(index);
            final StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, item.getUrl());
            assertTrue("Extractor returned no SABR audio stream for item=" + index
                            + " video=" + info.getId(),
                    info.getAudioStreams().stream().anyMatch(SabrPlaybackSmokeTest::isSabr));
            runAnonymousAudioWindow(context, info, index, playbackMs);
        }
    }

    @Test
    public void recoversMissingInitializationFromPump() throws Exception {
        runSmokeCase(SmokeCase.missingInitialization());
    }

    @Test
    public void recoversEvictedSegmentRewind() throws Exception {
        runSmokeCase(SmokeCase.evictedRewind());
    }

    @Test
    public void boundsReadAheadForStalledReader() throws Exception {
        runSmokeCase(SmokeCase.stalledReader());
    }

    @Test
    public void rewindClearsBufferedStateAndCookie() throws Exception {
        runSmokeCase(SmokeCase.rewindState());
    }

    @Test
    public void playbackIntoSponsorBlockSkipsToDuration() throws Exception {
        runSmokeCase(SmokeCase.sponsorBlockPlayback());
    }

    @Test
    public void seekIntoSponsorBlockSkipsToDuration() throws Exception {
        runSmokeCase(SmokeCase.sponsorBlockSeek());
    }

    @Test
    public void demandRepositionsAfterNonTargetMediaBatch() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.setPlayerTimeMs(20_000);
            harness.downloader.enqueue(new UmpFixture()
                    .initSegment(0, SMOKE_VIDEO_ITAG)
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 5_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(2, SMOKE_VIDEO_ITAG, 4, 15_000, 5_000)
                    .segment(3, SMOKE_VIDEO_ITAG, 5, 20_000, 5_000)
                    .segment(4, SMOKE_VIDEO_ITAG, 6, 25_000, 5_000)
                    .segment(5, SMOKE_VIDEO_ITAG, 7, 30_000, 5_000)
                    .segment(6, SMOKE_VIDEO_ITAG, 8, 35_000, 5_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(7, SMOKE_VIDEO_ITAG, 3, 10_000, 5_000)
                    .bytes());

            harness.openMediaSegment(
                    SabrSegmentRequest.media(harness.videoFormat, 3), 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("A non-target media batch did not force demand repositioning: " + trace,
                    trace.contains("pump_demand_reposition itag="
                            + SMOKE_VIDEO_ITAG + " seq=3"));
            assertTrue("Expected initial, non-target, and repositioned target requests",
                    harness.downloader.requestBodies.size() >= 3);
            final String repositionedRequest = SabrRequestDumper.summarize(
                    harness.downloader.requestBodies.get(2));
            assertTrue("Repositioned demand did not report the target as the next segment: "
                            + repositionedRequest,
                    repositionedRequest.contains("seq=1-2"));
            assertTrue("Repositioned demand kept the ahead-of-hole player time: "
                            + repositionedRequest,
                    repositionedRequest.contains("playerTimeMs=10000"));
        }
    }

    @Test
    public void companionOnlyResponseTriggersDemandRecovery() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 5_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(2, SMOKE_AUDIO_ITAG, 1, 0, 5_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(3, SMOKE_VIDEO_ITAG, 3, 10_000, 5_000)
                    .bytes());

            harness.openMediaSegment(
                    SabrSegmentRequest.media(harness.videoFormat, 3), 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Companion-only response did not record exact target omission: " + trace,
                    trace.contains("pump_demand_omission itag="
                            + SMOKE_VIDEO_ITAG + " seq=3 omissions=1"));
            assertTrue("Companion-only response did not trigger target recovery: " + trace,
                    trace.contains("pump_demand_reposition itag="
                            + SMOKE_VIDEO_ITAG + " seq=3"));
        }
    }

    @Test
    public void repeatedNonTargetMediaBatchesFailWithinDemandBudget() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .initSegment(0, SMOKE_VIDEO_ITAG)
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 5_000)
                    .bytes());
            for (int response = 0; response < 3; response++) {
                harness.downloader.enqueue(new UmpFixture()
                        .segment(2 + response, SMOKE_VIDEO_ITAG,
                                4 + response, 15_000 + response * 5_000L, 5_000)
                        .bytes());
            }

            harness.openMediaSegmentExpectFailure(
                    SabrSegmentRequest.media(harness.videoFormat, 3), 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Demand did not record the bounded third target omission: " + trace,
                    trace.contains("pump_demand_omission itag="
                            + SMOKE_VIDEO_ITAG + " seq=3 omissions=3"));
            assertTrue("Demand exceeded its response budget plus one resumed prefetch: requests="
                            + harness.downloader.requestBodies.size() + " trace=" + trace,
                    harness.downloader.requestBodies.size() <= 5);
        }
    }

    @Test
    public void activePrefetchDoesNotDeadZoneBelowSessionCacheLimit() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            final int firstSegmentBytes = 28 * 1024 * 1024;
            final SabrSegmentRequest first =
                    SabrSegmentRequest.media(harness.videoFormat, 1);
            final SabrSegmentRequest second =
                    SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.downloader.enqueue(new GeneratedLargeMediaResponse(
                    1, SMOKE_VIDEO_ITAG, 1, 0, 5_000, firstSegmentBytes));
            harness.downloader.enqueue(new UmpFixture()
                    .segment(2, SMOKE_VIDEO_ITAG, 2, 5_000, 30_000)
                    .bytes());

            harness.openMediaSegment(first, 30_000);
            harness.setPlayerTimeMs(5_000);
            final long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (harness.holder.session.getCachedSegment(second) == null
                    && System.nanoTime() < deadlineNs) {
                Thread.sleep(50);
            }

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertNotNull("Active prefetch stopped between the pump and session byte limits: "
                            + trace,
                    harness.holder.session.getCachedSegment(second));
            assertTrue("Active prefetch did not make a second request: " + trace,
                    harness.downloader.requestBodies.size() >= 2);
        }
    }

    @Test
    public void demandHonorsFullServerBackoff() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(3_000))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final long elapsedMs = harness.openMediaSegment(
                    SabrSegmentRequest.media(harness.videoFormat, 2), 6_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Demand path did not request the target segment: " + trace,
                    trace.contains("pump_demand itag=" + SMOKE_VIDEO_ITAG + " seq=2"));
            final List<Long> requestTimesMs = harness.downloader.requestTimesSnapshot();
            assertTrue("Expected initial, policy-only, and target requests: " + requestTimesMs,
                    requestTimesMs.size() >= 3);
            final long retryDelayMs = requestTimesMs.get(2) - requestTimesMs.get(1);
            assertTrue("Demand retry ignored the server backoff entirely: delayMs="
                            + retryDelayMs + " trace=" + trace,
                    retryDelayMs >= 2_800);
            assertTrue("Demand retry did not honor the full server backoff: elapsedMs="
                            + elapsedMs + " trace=" + trace, elapsedMs < 5_000);
        }
    }

    @Test
    public void demandBackoffRemainsCancelableWithoutEarlyRequest() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000).bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(3_000))
                    .bytes());
            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            final SabrSegmentDataSource dataSource = new SabrSegmentDataSource(
                    harness.holder, harness.readerOwner, request.getFormat(),
                    new Localization("en", "US"), false);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final Thread loader = new Thread(() -> {
                try {
                    dataSource.open(new DataSpec(harness.segmentUri(request)));
                } catch (final Throwable e) {
                    failure.set(e);
                } finally {
                    done.countDown();
                }
            }, "SabrSmokeCancelableBackoff");
            loader.start();
            boolean completed;
            try {
                final long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (harness.holder.session.getDemandBackoffRemainingMs() == 0
                        && System.nanoTime() < deadlineNs) {
                    Thread.sleep(25);
                }
                assertTrue("Demand did not enter the server backoff: "
                                + harness.holder.session.getDiagnosticTrace(),
                        harness.holder.session.getDemandBackoffRemainingMs() > 0);
                harness.advanceReaderGeneration();
                completed = done.await(1_500, TimeUnit.MILLISECONDS);
                Thread.sleep(250);
            } finally {
                dataSource.close();
                loader.interrupt();
                done.await(2, TimeUnit.SECONDS);
            }
            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Backoff kept the stale loader blocked: " + trace, completed);
            assertTrue("Backoff cancellation should surface as a recoverable load failure: "
                            + failure.get(), failure.get() instanceof IOException);
            assertEquals("Cancellation sent a request before the server deadline: " + trace,
                    2, harness.downloader.requestTimesSnapshot().size());
            assertTrue("Backoff cancellation failed the shared session: " + trace,
                    !trace.contains("terminal_failure"));
        }
    }

    @Test
    public void startupPumpDefersLongBackoffBeforeLoaderDemand() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(30_000))
                    .bytes());

            final long startedAtMs = System.currentTimeMillis();
            assertEquals(0, harness.holder.session.pumpOnceStreamingForStartup(
                    new Localization("en", "US")));
            final long elapsedMs = System.currentTimeMillis() - startedAtMs;
            final long remainingMs = harness.holder.session.getDemandBackoffRemainingMs();

            assertTrue("Startup pump blocked on the full server backoff: elapsedMs=" + elapsedMs,
                    elapsedMs < 1_000);
            assertTrue("Startup pump did not retain a bounded pacing delay: remainingMs="
                            + remainingMs,
                    remainingMs >= 1_500 && remainingMs <= 2_000);
        }
    }

    @Test
    public void demandBackoffPublishesStandaloneNotificationWhileBuffering() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        final SabrBackoffCoordinator coordinator = SabrBackoffCoordinator.getInstance();
        coordinator.setPlayerBuffering(context, true);
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(3_000))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch completed = new CountDownLatch(1);
            final Thread demand = new Thread(() -> {
                try {
                    harness.openMediaSegment(
                            SabrSegmentRequest.media(harness.videoFormat, 2), 5_000);
                } catch (final Throwable error) {
                    failure.set(error);
                } finally {
                    completed.countDown();
                }
            }, "SabrBackoffNotificationSmoke");
            demand.start();

            final StatusBarNotification notification = awaitBackoffNotification(context, true);
            assertNotNull("SABR demand backoff did not publish its standalone notification",
                    notification);
            assertTrue("Demand completed before the backoff notification was observed",
                    completed.getCount() > 0);
            assertTrue("SABR demand did not recover after the server backoff",
                    completed.await(5, TimeUnit.SECONDS));
            assertNull("SABR demand failed after the server backoff", failure.get());
            assertNull("Backoff notification remained after the demanded segment recovered",
                    awaitBackoffNotification(context, false));
        } finally {
            coordinator.setPlayerBuffering(context, false);
        }
    }

    @Test
    public void pumpBackoffPublishesStandaloneNotificationWhileBuffering() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        final SabrBackoffCoordinator coordinator = SabrBackoffCoordinator.getInstance();
        coordinator.setPlayerBuffering(context, true);
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(30_000))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());

            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch completed = new CountDownLatch(1);
            final Thread reader = new Thread(() -> {
                try {
                    harness.openMediaSegment(
                            SabrSegmentRequest.media(harness.videoFormat, 1), 5_000);
                } catch (final Throwable error) {
                    failure.set(error);
                } finally {
                    completed.countDown();
                }
            }, "SabrPumpBackoffNotificationSmoke");
            reader.start();

            final StatusBarNotification notification = awaitBackoffNotification(context, true);
            assertNotNull("Initial SABR pump backoff did not publish its notification",
                    notification);
            assertTrue("Pump completed before the backoff notification was observed",
                    completed.getCount() > 0);
            assertTrue("SABR pump did not recover after the server backoff",
                    completed.await(5, TimeUnit.SECONDS));
            assertNull("SABR pump failed after the server backoff", failure.get());
            final List<Long> requestTimesMs = harness.downloader.requestTimesSnapshot();
            assertTrue("Expected policy-only and media requests: " + requestTimesMs,
                    requestTimesMs.size() >= 2);
            assertTrue("SABR pump ignored the server backoff: " + requestTimesMs,
                    requestTimesMs.get(1) - requestTimesMs.get(0) >= 1_500);
            assertTrue("Initial SABR pump honored the full 30 second backoff instead of the "
                            + "bounded startup wait: " + requestTimesMs,
                    requestTimesMs.get(1) - requestTimesMs.get(0) < 5_000);
            assertNull("Backoff notification remained after the pump resumed",
                    awaitBackoffNotification(context, false));
        } finally {
            coordinator.setPlayerBuffering(context, false);
        }
    }

    @Test
    public void rejectedAttestationFailsWithoutEnteringBackoff() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.STREAM_PROTECTION_STATUS,
                            streamProtection(3, 20))
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY,
                            nextRequestPolicy(59_000))
                    .bytes());

            final long startMs = System.currentTimeMillis();
            harness.openMediaSegmentExpectFailure(
                    SabrSegmentRequest.media(harness.videoFormat, 2), 5_000);
            final long elapsedMs = System.currentTimeMillis() - startMs;

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Rejected attestation response was not exercised: " + trace,
                    trace.contains("protection=3/20"));
            assertTrue("Rejected attestation incorrectly entered the 59 second backoff: elapsedMs="
                            + elapsedMs + " trace=" + trace,
                    elapsedMs < 2_000);
            assertTrue("Rejected attestation triggered another SABR request: "
                            + harness.downloader.requestTimesSnapshot(),
                    harness.downloader.requestTimesSnapshot().size() <= 2);
        }
    }

    @Test
    public void pendingAttestationDoesNotReloadOrFail() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.STREAM_PROTECTION_STATUS,
                            streamProtection(2, 20))
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY,
                            nextRequestPolicy(2_000))
                    .bytes());

            final YoutubeSabrSession.DemandResponseResult result =
                    harness.holder.session.pumpOnceStreamingForDemand(
                            new Localization("en", "US"),
                            SabrSegmentRequest.media(harness.videoFormat, 1));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Pending attestation response was not exercised: " + trace,
                    trace.contains("protection=2/20"));
            assertTrue("Pending attestation did not return through normal response handling",
                    result.wasRequestPerformed());
            assertEquals("Pending attestation unexpectedly returned media", 0,
                    result.getSegmentCount());
            assertEquals("Pending attestation triggered an implicit retry", 1,
                    harness.downloader.requestTimesSnapshot().size());
        }
    }

    @Test
    public void nearEdgeServerBackoffsDoNotTriggerLocalRecovery() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            for (int i = 0; i < 6; i++) {
                harness.downloader.enqueue(new UmpFixture()
                        .part(SabrResponseDecoder.NEXT_REQUEST_POLICY,
                                nextRequestPolicy(2_000))
                        .bytes());
            }
            harness.downloader.enqueue(new UmpFixture()
                    .segment(8, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final long elapsedMs = harness.openMediaSegment(
                    SabrSegmentRequest.media(harness.videoFormat, 2), 15_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Near-edge server pacing response was not exercised: " + trace,
                    trace.contains("pump_demand_no_media itag=" + SMOKE_VIDEO_ITAG + " seq=2"));
            assertTrue("Server-directed backoff incorrectly triggered local recovery: " + trace,
                    !trace.contains("recovery type=near_edge_refetch")
                            && !trace.contains("pump_rewind itag=" + SMOKE_VIDEO_ITAG + " seq=2"));
            assertTrue("Demand did not preserve the repeated server backoffs: elapsedMs="
                            + elapsedMs + " trace=" + trace,
                    elapsedMs >= 11_500);
            assertTrue("Near-edge server pacing failed the shared SABR session: " + trace,
                    !trace.contains("terminal_failure"));
        }
    }

    @Test
    public void staleReaderDemandStopsWithoutFailingSession() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            final CountDownLatch responseStarted = new CountDownLatch(1);
            final CountDownLatch releaseResponse = new CountDownLatch(1);
            harness.downloader.enqueue(() -> {
                responseStarted.countDown();
                try {
                    if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release stale demand response");
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting to release stale demand response", e);
                }
                return new ByteArrayInputStream(new UmpFixture()
                        .segment(2, SMOKE_VIDEO_ITAG, 3, 60_000, 5_000)
                        .bytes());
            });

            final SabrSegmentRequest request =
                    SabrSegmentRequest.media(harness.videoFormat, 2);
            final SabrSegmentDataSource dataSource = new SabrSegmentDataSource(
                    harness.holder, harness.readerOwner, request.getFormat(),
                    new Localization("en", "US"), false);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final Thread loader = new Thread(() -> {
                try {
                    dataSource.open(new DataSpec(harness.segmentUri(request)));
                } catch (final Throwable e) {
                    failure.set(e);
                } finally {
                    done.countDown();
                }
            }, "SabrSmokeStaleDemand");
            loader.start();

            boolean completed;
            try {
                assertTrue("Demand request did not reach the controlled response",
                        responseStarted.await(5, TimeUnit.SECONDS));
                harness.advanceReaderGeneration();
                releaseResponse.countDown();
                completed = done.await(1_500, TimeUnit.MILLISECONDS);
            } finally {
                releaseResponse.countDown();
                dataSource.close();
                loader.interrupt();
                done.await(2, TimeUnit.SECONDS);
            }

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Stale reader demand kept waiting until the no-progress watchdog: " + trace,
                    completed);
            assertTrue("Stale reader demand should end as a recoverable load cancellation: "
                            + failure.get(),
                    failure.get() instanceof IOException);
            assertTrue("Stale reader demand failed the shared SABR session: " + trace,
                    !trace.contains("terminal_failure"));
            assertTrue("Media-bearing stale demand changed the session after cancellation: " + trace,
                    !trace.contains("pump_demand_target_miss itag=" + SMOKE_VIDEO_ITAG + " seq=2")
                            && !trace.contains("pump_demand_reposition itag="
                            + SMOKE_VIDEO_ITAG + " seq=2")
                            && !trace.contains("pump_demand_failed itag="
                            + SMOKE_VIDEO_ITAG + " seq=2"));
        }
    }

    @Test
    public void interruptedUmpReadStopsDemandWithoutFailingSession() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(() -> new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new InterruptedIOException("Interrupted while reading UMP stream");
                }
            });

            final SabrSegmentRequest request =
                    SabrSegmentRequest.media(harness.videoFormat, 2);
            final SabrSegmentDataSource dataSource = new SabrSegmentDataSource(
                    harness.holder, harness.readerOwner, request.getFormat(),
                    new Localization("en", "US"), false);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final Thread loader = new Thread(() -> {
                try {
                    dataSource.open(new DataSpec(harness.segmentUri(request)));
                } catch (final Throwable e) {
                    failure.set(e);
                } finally {
                    done.countDown();
                }
            }, "SabrSmokeInterruptedDemand");
            loader.start();

            final boolean completed;
            try {
                completed = done.await(1_500, TimeUnit.MILLISECONDS);
            } finally {
                dataSource.close();
                loader.interrupt();
                done.await(2, TimeUnit.SECONDS);
            }

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Interrupted UMP read was retried until the watchdog: " + trace,
                    completed);
            assertTrue("Interrupted UMP read should surface as a recoverable load failure: "
                            + failure.get(),
                    failure.get() instanceof IOException);
            assertTrue("Interrupted UMP read failed the shared SABR session: " + trace,
                    !trace.contains("terminal_failure"));
        }
    }

    @Test
    public void initializationPumpKeepsMidStartTarget() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.setPlayerTimeMs(300_000);
            harness.downloader.enqueue(new UmpFixture()
                    .initSegment(1, SMOKE_VIDEO_ITAG)
                    .bytes());

            final SabrSegmentRequest request =
                    SabrSegmentRequest.initialization(harness.videoFormat);
            harness.openSegment(request, 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Initialization pump did not anchor the target: " + trace,
                    trace.contains("pump_initialization_target itag=" + SMOKE_VIDEO_ITAG));
            assertTrue("No SABR request body was captured",
                    !harness.downloader.requestBodies.isEmpty());
            final String requestSummary = SabrRequestDumper.summarize(
                    harness.downloader.requestBodies.get(0));
            assertTrue("Initial SABR request did not keep player time: " + requestSummary,
                    requestSummary.contains("playerTimeMs=300000"));
            assertTrue("Initial SABR request did not report target time: " + requestSummary,
                    requestSummary.contains("topPlayerTimeMs=300000"));
        }
    }

    @Test
    public void nativeBootstrapBuildsExactTimelineWithoutAdaptiveRangeRequests() throws Exception {
        final YoutubeSabrFormat audioFormat = smokeFormat(SMOKE_AUDIO_ITAG, true);
        final YoutubeSabrFormat videoFormat = smokeFormat(SMOKE_VIDEO_ITAG, false);
        final byte[] audioInit = mp4Sidx(20_001, 20_000, 19_999);
        final byte[] videoInit = mp4Sidx(5_000, 5_000, 5_000, 5_000);
        try (SabrSmokeHarness harness = SabrSmokeHarness.create(audioFormat, videoFormat)) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.FORMAT_INITIALIZATION_METADATA,
                            initializationMetadata(SMOKE_AUDIO_ITAG, 3, 60_000, "audio/mp4"))
                    .part(SabrResponseDecoder.FORMAT_INITIALIZATION_METADATA,
                            initializationMetadata(SMOKE_VIDEO_ITAG, 4, 20_000, "video/mp4"))
                    .initSegment(1, SMOKE_AUDIO_ITAG, audioInit)
                    .initSegment(2, SMOKE_VIDEO_ITAG, videoInit)
                    .bytes());

            harness.holder.session.bootstrapInitialization(new Localization("en", "US"));
            assertTrue(harness.holder.session.getStreamState().hasSegmentIndex(audioFormat));
            assertTrue(harness.holder.session.getStreamState().hasSegmentIndex(videoFormat));
            assertEquals(20_001, harness.holder.session.getStreamState()
                    .getSegmentStartMs(audioFormat, 2));
            assertEquals(40_001, harness.holder.session.getStreamState()
                    .getSegmentStartMs(audioFormat, 3));

            final SabrSourceSpec spec = new SabrSourceSpec("smoke-video", harness.holder.info,
                    audioFormat, videoFormat, new Localization("en", "US"),
                    audioInit, videoInit);
            new SabrDashMediaSource(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    new MediaItem.Builder()
                    .setUri(Uri.parse("sabr://smoke-video"))
                    .build(), spec);

            assertTrue("Bootstrap unexpectedly used adaptive range transport",
                    harness.downloader.streamingTimeoutsMs.isEmpty());
        }
    }

    @Test
    public void adaptiveExactRangesBuildIndexesInParallel() throws Exception {
        final byte[] poToken = new byte[]{(byte) 0xfb, (byte) 0xef, 1};
        final String encodedPoToken = "--8B";
        final byte[] audioInit = mp4Sidx(20_001, 20_000, 19_999);
        final byte[] videoInit = mp4Sidx(5_000, 5_000, 5_000, 5_000);
        final YoutubeSabrFormat audioFormat = smokeFormat(SMOKE_AUDIO_ITAG, true,
                "https://adaptive/audio", 0, audioInit.length - 1);
        final YoutubeSabrFormat videoFormat = smokeFormat(SMOKE_VIDEO_ITAG, false,
                "https://adaptive/video", 0, videoInit.length - 1);
        try (SabrSmokeHarness harness = SabrSmokeHarness.create(audioFormat, videoFormat)) {
            harness.downloader.enqueueGet("https://adaptive/audio?pot=" + encodedPoToken,
                    206, audioInit);
            harness.downloader.enqueueGet("https://adaptive/video?pot=" + encodedPoToken,
                    206, videoInit);

            final Method method = SabrSessionStore.class.getDeclaredMethod(
                    "createAdaptiveInitialization", YoutubeSabrInfo.class,
                    YoutubeSabrFormat.class, YoutubeSabrFormat.class, Localization.class,
                    byte[].class);
            method.setAccessible(true);
            final Object result = method.invoke(null, harness.holder.info, audioFormat,
                    videoFormat, new Localization("en", "US"), poToken);

            final Field audioData = result.getClass().getDeclaredField("audioInitialization");
            final Field videoData = result.getClass().getDeclaredField("videoInitialization");
            audioData.setAccessible(true);
            videoData.setAccessible(true);
            assertArrayEquals(audioInit, (byte[]) audioData.get(result));
            assertArrayEquals(videoInit, (byte[]) videoData.get(result));
            assertEquals(2, harness.downloader.streamingTimeoutsMs.size());
            assertTrue(harness.downloader.requestedUrls.contains(
                    "https://adaptive/audio?pot=" + encodedPoToken));
            assertTrue(harness.downloader.requestedUrls.contains(
                    "https://adaptive/video?pot=" + encodedPoToken));
            assertTrue(harness.downloader.requestBodies.isEmpty());
        }
    }

    @Test
    public void preparedNativeSessionIsTransferredToPlaybackOnce() throws Exception {
        final YoutubeSabrFormat audioFormat = smokeFormat(SMOKE_AUDIO_ITAG, true);
        final YoutubeSabrFormat videoFormat = smokeFormat(SMOKE_VIDEO_ITAG, false);
        final byte[] audioInit = mp4Sidx(20_001, 20_000);
        final byte[] videoInit = mp4Sidx(5_000, 5_000);
        try (SabrSmokeHarness harness = SabrSmokeHarness.create(audioFormat, videoFormat)) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.FORMAT_INITIALIZATION_METADATA,
                            initializationMetadata(SMOKE_AUDIO_ITAG, 2, 40_001, "audio/mp4"))
                    .part(SabrResponseDecoder.FORMAT_INITIALIZATION_METADATA,
                            initializationMetadata(SMOKE_VIDEO_ITAG, 2, 10_000, "video/mp4"))
                    .initSegment(1, SMOKE_AUDIO_ITAG, audioInit)
                    .initSegment(2, SMOKE_VIDEO_ITAG, videoInit)
                    .bytes());
            harness.holder.session.bootstrapInitialization(new Localization("en", "US"));

            final Constructor<SabrSourceSpec> constructor = SabrSourceSpec.class
                    .getDeclaredConstructor(String.class, YoutubeSabrInfo.class,
                            YoutubeSabrFormat.class, YoutubeSabrFormat.class, Localization.class,
                            byte[].class, byte[].class, YoutubeSabrSession.class);
            constructor.setAccessible(true);
            final SabrSourceSpec spec = constructor.newInstance("smoke-video", harness.holder.info,
                    audioFormat, videoFormat, new Localization("en", "US"), audioInit, videoInit,
                    harness.holder.session);
            final Method acquire = SabrSessionStore.class.getDeclaredMethod(
                    "acquire", Context.class, SabrSourceSpec.class);
            acquire.setAccessible(true);
            final SabrSessionStore.Lease lease = (SabrSessionStore.Lease) acquire.invoke(null,
                    InstrumentationRegistry.getInstrumentation().getTargetContext(), spec);
            try {
                assertSame(harness.holder.session, holderOf(lease).session);
                assertTrue(harness.holder.session.getDiagnosticTrace()
                        .contains("bootstrap_session_handoff"));
            } finally {
                lease.close();
            }
        }
    }

    @Test
    public void nativeBootstrapHonorsInitialAndSkipsCompletedResponseBackoff() throws Exception {
        final YoutubeSabrFormat audioFormat = smokeFormat(SMOKE_AUDIO_ITAG, true);
        final YoutubeSabrFormat videoFormat = smokeFormat(SMOKE_VIDEO_ITAG, false);
        final byte[] audioInit = mp4Sidx(20_000);
        final byte[] videoInit = mp4Sidx(5_000);
        try (SabrSmokeHarness harness = SabrSmokeHarness.create(audioFormat, videoFormat)) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(500))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(5_000))
                    .part(SabrResponseDecoder.FORMAT_INITIALIZATION_METADATA,
                            initializationMetadata(SMOKE_AUDIO_ITAG, 1, 20_000, "audio/mp4"))
                    .part(SabrResponseDecoder.FORMAT_INITIALIZATION_METADATA,
                            initializationMetadata(SMOKE_VIDEO_ITAG, 1, 5_000, "video/mp4"))
                    .initSegment(1, SMOKE_AUDIO_ITAG, audioInit)
                    .initSegment(2, SMOKE_VIDEO_ITAG, videoInit)
                    .bytes());

            final long bootstrapStartNs = System.nanoTime();
            harness.holder.session.bootstrapInitialization(new Localization("en", "US"));
            final long bootstrapElapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - bootstrapStartNs);

            final List<Long> requestTimesMs = harness.downloader.requestTimesSnapshot();
            assertEquals(2, requestTimesMs.size());
            assertTrue("Bootstrap ignored the initial SABR backoff: " + requestTimesMs,
                    requestTimesMs.get(1) - requestTimesMs.get(0) >= 400);
            assertTrue("Bootstrap waited for the completed init response backoff: elapsedMs="
                            + bootstrapElapsedMs,
                    bootstrapElapsedMs < 2_000);
        }
    }

    @Test
    public void demandIncompleteMediaResponseRetriesThroughPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .media(2)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(3, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.openMediaSegment(request, 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Incomplete media response was not exercised: " + trace,
                    trace.contains("missing-media-end:2"));
            assertNotNull("Demand retry did not fetch the target segment: " + trace,
                    harness.holder.session.getCachedSegment(request));
        }
    }

    @Test
    public void demandRecoverableIntegrityShapesRetryThroughPump() throws Exception {
        verifyDemandIntegrityRetry("length-mismatch:2", new UmpFixture()
                .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000, 4)
                .media(2, new byte[]{10, 11})
                .mediaEnd(2));
        verifyDemandIntegrityRetry("missing-media:2", new UmpFixture()
                .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                .mediaEnd(2));
        verifyDemandIntegrityRetry("media-without-header:2", new UmpFixture()
                .media(2)
                .mediaEnd(2));
        verifyDemandIntegrityRetry("media-end-without-header:2", new UmpFixture()
                .mediaEnd(2));
    }

    @Test
    public void malformedControlPartDoesNotDropMediaInPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 1);
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, new byte[]{0x0f})
                    .segment(1, SMOKE_VIDEO_ITAG, 1)
                    .bytes());

            assertEquals(1, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertNotNull("Malformed control part caused media to be dropped: " + trace,
                    harness.holder.session.getCachedSegment(request));
            assertTrue("Malformed control part was not exercised: " + trace,
                    trace.contains("malformedParts=[35:1:"));
        }
    }

    @Test
    public void malformedMediaHeaderRetriesThroughDemandPump() throws Exception {
        verifyDemandIntegrityRetry("media-without-header:2", new UmpFixture()
                .part(SabrResponseDecoder.MEDIA_HEADER, new byte[]{0x0f})
                .media(2)
                .mediaEnd(2));
    }

    @Test
    public void duplicateMediaHeaderFailsThroughDemandPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .mediaHeader(2, SMOKE_VIDEO_ITAG, 3, 35_000, 5_000)
                    .bytes());

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.openMediaSegmentExpectFailure(request, 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Duplicate media header was not exercised: " + trace,
                    trace.contains("duplicate-media-header:2"));
        }
    }

    @Test
    public void demandPendingAttestationHonorsServerBackoff() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.STREAM_PROTECTION_STATUS, streamProtection(2, 7))
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(3_000))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final long elapsedMs = harness.openMediaSegment(
                    SabrSegmentRequest.media(harness.videoFormat, 2), 6_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Pending attestation response was not exercised: " + trace,
                    trace.contains("protection=2/7"));
            final List<Long> requestTimesMs = harness.downloader.requestTimesSnapshot();
            assertTrue("Expected initial, protected, and target requests: " + requestTimesMs,
                    requestTimesMs.size() >= 3);
            final long retryDelayMs = requestTimesMs.get(2) - requestTimesMs.get(1);
            assertTrue("Pending attestation next request ignored backoff: delayMs=" + retryDelayMs
                            + " trace=" + trace,
                    retryDelayMs >= 2_800);
            assertTrue("Pending attestation did not honor the server backoff: elapsedMs="
                            + elapsedMs, elapsedMs < 5_000);
        }
    }

    @Test
    public void requestPolicyLiveAndInitializationMetadataUpdateSessionState()
            throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY,
                            nextRequestPolicy(2_000, playbackCookie(), "smoke-video"))
                    .part(SabrResponseDecoder.LIVE_METADATA,
                            liveMetadata(40, 200_000, true))
                    .part(SabrResponseDecoder.FORMAT_INITIALIZATION_METADATA,
                            initializationMetadata(SMOKE_VIDEO_ITAG, 60, 300_000, "video/webm"))
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertNotNull("Next request policy was not applied: " + trace,
                    harness.holder.session.getStreamState().getNextRequestPolicy());
            assertEquals("Policy backoff was not applied", 2_000,
                    harness.holder.session.getStreamState()
                            .getNextRequestPolicy().getBackoffTimeMs());
            assertNotNull("Playback cookie was not applied: " + trace,
                    harness.holder.session.getStreamState().getPlaybackCookie());
            assertTrue("Live metadata was not applied: " + trace,
                    harness.holder.session.getStreamState().isLive());
            assertTrue("Post-live DVR flag was not applied: " + trace,
                    harness.holder.session.getStreamState().isPostLiveDvr());
            assertEquals("Initialization metadata did not set end segment",
                    60, harness.holder.session.getStreamState()
                            .getEndSegment(harness.videoFormat));
            assertEquals("Initialization metadata did not derive segment time",
                    50_000, harness.holder.session.getStreamState()
                            .getSegmentStartMs(harness.videoFormat, 11));
        }
    }

    @Test
    public void redirectUpdatesFollowUpSabrStreamingUrl() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_REDIRECT,
                            redirect("https://redirect.googlevideo.com/sabr"))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1)
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));
            assertEquals(1, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            assertTrue("First SABR request did not use original URL: "
                            + harness.downloader.requestedUrls,
                    harness.downloader.requestedUrls.get(0).contains("https://sabr.test"));
            assertTrue("Follow-up SABR request did not use redirect URL: "
                            + harness.downloader.requestedUrls,
                    harness.downloader.requestedUrls.get(1)
                            .contains("https://redirect.googlevideo.com/sabr"));
        }
    }

    @Test
    public void sabrErrorFailsThroughPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_ERROR, sabrError("blocked", 403))
                    .bytes());

            try {
                harness.holder.session.pumpOnceStreaming(new Localization("en", "US"));
            } catch (final Exception expected) {
                final String trace = harness.holder.session.getDiagnosticTrace();
                assertTrue("SABR error details were not decoded: " + trace,
                        trace.contains("type=blocked, code=403"));
                return;
            }
            throw new AssertionError("SABR error response did not fail the pump");
        }
    }

    @Test
    public void reloadPlayerResponseFailsBoundedThroughPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.RELOAD_PLAYER_RESPONSE,
                            reloadPlayerResponse("reload-token"))
                    .bytes());

            try {
                harness.holder.session.pumpOnceStreaming(new Localization("en", "US"));
            } catch (final Exception expected) {
                final String trace = harness.holder.session.getDiagnosticTrace();
                assertTrue("Reload player response was not decoded: " + trace,
                        trace.contains("46=[reloadPlaybackParamsTokenLength=12]"));
                assertTrue("Reload response did not mark no-media reload state: " + trace,
                        trace.contains("reload=true"));
                return;
            }
            throw new AssertionError("SABR reload response unexpectedly succeeded");
        }
    }

    @Test
    public void unknownAndGenericControlsRemainDiagnosticsInPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(99, proto().u64(1, 7).bytes())
                    .part(SabrResponseDecoder.CONFIG, proto().u64(2, 9).bytes())
                    .part(SabrResponseDecoder.REQUEST_IDENTIFIER,
                            requestIdentifier("request-token"))
                    .part(SabrResponseDecoder.SNACKBAR_MESSAGE, snackbar(12))
                    .part(SabrResponseDecoder.REQUEST_CANCELLATION_POLICY, cancellationPolicy())
                    .part(SabrResponseDecoder.PREWARM_CONNECTION, prewarmConnection())
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Unknown part was not retained for diagnostics: " + trace,
                    trace.contains("unknownParts=[99]"));
            assertTrue("CONFIG control was not summarized: " + trace,
                    trace.contains("30=[2=9]"));
            assertTrue("Request identifier was not summarized: " + trace,
                    trace.contains("52=[tokenLength=13]"));
            assertTrue("Snackbar was not summarized: " + trace,
                    trace.contains("67=[id=12]"));
            assertTrue("Cancellation policy was not summarized: " + trace,
                    trace.contains("53=[field1=1"));
            assertTrue("Prewarm connection was not summarized: " + trace,
                    trace.contains("65=[connections=1["));
        }
    }

    @Test
    public void advancedControlsRemainDiagnosticsInPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_SEEK, sabrSeek(45_000, 1000, 2))
                    .part(SabrResponseDecoder.PLAYBACK_START_POLICY, playbackStartPolicy())
                    .part(SabrResponseDecoder.FORMAT_SELECTION_CONFIG, formatSelectionConfig())
                    .part(SabrResponseDecoder.SELECTABLE_FORMATS, selectableFormats())
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("SABR seek control was not summarized: " + trace,
                    trace.contains("45=[seek=45000/1000, source=2]"));
            assertTrue("Playback start policy was not summarized: " + trace,
                    trace.contains("47=[start=1[1500ms/100000Bps]"));
            assertTrue("Format selection config was not summarized: " + trace,
                    trace.contains("37=[itags=2[248,140]"));
            assertTrue("Selectable formats were not summarized: " + trace,
                    trace.contains("51=[video=1[itag:248+lm+xtags]"));
        }
    }

    @Test
    public void onesieControlsRemainDiagnosticsInPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.ONESIE_HEADER, onesieHeader(0, 1, false))
                    .part(SabrResponseDecoder.ONESIE_DATA, onesieInnertubeResponse())
                    .part(SabrResponseDecoder.ONESIE_HEADER, onesieHeader(25, 2, true))
                    .part(SabrResponseDecoder.ONESIE_ENCRYPTED_MEDIA, new byte[]{1, 2, 3})
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Clear onesie header was not summarized: " + trace,
                    trace.contains("10=[type=0/ONESIE_PLAYER_RESPONSE"));
            assertTrue("Clear onesie data was not associated with the header: " + trace,
                    trace.contains("11=[encrypted=false, payloadBytes="));
            assertTrue("Innertube payload was not decoded: " + trace,
                    trace.contains("innertubeResponse=[proxyStatus=1, httpStatus=200"));
            assertTrue("Encrypted onesie data was not summarized: " + trace,
                    trace.contains("12=[encrypted=true, payloadBytes=3"));
        }
    }

    @Test
    public void contextKeepExistingAndDiscardUpdateSessionState() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                            contextUpdate(30, new byte[]{1}, true, 1))
                    .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                            contextUpdate(30, new byte[]{2}, false, 2))
                    .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                            contextUpdate(40, new byte[]{3}, true, 1))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_CONTEXT_SENDING_POLICY,
                            contextPolicy(new int[0], new int[0], new int[]{40}))
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));
            assertTrue("Context 30 should be active after first update",
                    activeContextTypes(harness).contains(30));
            assertTrue("KEEP_EXISTING should not make context 30 unsent",
                    !unsentContextTypes(harness).contains(30));

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Discard policy was not decoded: " + trace,
                    trace.contains("59=[start=[], stop=[], discard=[40]]"));
            assertTrue("Context 40 was not discarded",
                    !activeContextTypes(harness).contains(40)
                            && !unsentContextTypes(harness).contains(40));
        }
    }

    @Test
    public void compressedMediaSegmentCachesDecompressedBytesThroughDemandPump()
            throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            final byte[] raw = new byte[]{40, 41, 42, 43, 44};
            final byte[] gzipped = gzip(raw);
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000,
                            gzipped.length, 1)
                    .media(2, gzipped)
                    .mediaEnd(2)
                    .bytes());

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.openMediaSegment(request, 5_000);

            final String trace = waitForTrace(harness, "compression=1", 2_000);
            assertTrue("Compressed media header was not exercised: " + trace,
                    trace.contains("compression=1"));
            assertEquals("Demand path did not cache decompressed media length",
                    raw.length, harness.holder.session.getCachedSegment(request).getLength());
        }
    }

    @Test
    public void growingMediaSegmentReadsBeforeMediaEndAndCompletesAtEof() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            final byte[] firstMediaBytes = filledBytes(64 * 1024, 10);
            final byte[] remainingMediaBytes = new byte[0];
            final byte[] expectedMediaBytes = concatBytes(firstMediaBytes, remainingMediaBytes);
            final GatedMediaResponse response = new GatedMediaResponse(
                    1, SMOKE_VIDEO_ITAG, 1, 0, 5_000,
                    firstMediaBytes, remainingMediaBytes, 0, false, null);
            harness.downloader.enqueue(response);

            final AsyncSegmentReader reader = new AsyncSegmentReader(
                    harness.holder, harness.readerOwner,
                    SabrSegmentRequest.media(harness.videoFormat, 1),
                    firstMediaBytes.length - 1);
            reader.start();
            try {
                assertTrue("Producer did not reach the MEDIA payload gate",
                        response.awaitGate(2_000));
                assertTrue("DataSource did not expose initial media bytes before MEDIA_END",
                        reader.awaitFirstBytes(1_000));
                assertEquals("DataSource did not hold its final byte for MEDIA_END validation",
                        firstMediaBytes.length - 1, reader.bytesSnapshot().length);
                assertTrue("DataSource reached EOF while MEDIA_END was still blocked",
                        !reader.isEofObserved());
            } finally {
                response.release();
            }

            assertTrue("DataSource did not finish after MEDIA_END was released",
                    reader.awaitDone(2_000));
            assertNull("Growing media read failed", reader.getFailure());
            assertTrue("Growing media read did not observe EOF", reader.isEofObserved());
            assertTrue("Growing media read returned incomplete bytes",
                    Arrays.equals(expectedMediaBytes, reader.bytesSnapshot()));
        }
    }

    @Test
    public void growingMediaSegmentFailureWakesReaderWithIOException() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            final byte[] firstMediaBytes = filledBytes(64 * 1024, 20);
            final GatedMediaResponse response = new GatedMediaResponse(
                    1, SMOKE_VIDEO_ITAG, 1, 0, 5_000,
                    firstMediaBytes, new byte[0], 0, false,
                    new IOException("gated SABR media failure"));
            harness.downloader.enqueue(response);

            final AsyncSegmentReader reader = new AsyncSegmentReader(
                    harness.holder, harness.readerOwner,
                    SabrSegmentRequest.media(harness.videoFormat, 1),
                    firstMediaBytes.length - 1);
            reader.start();
            try {
                assertTrue("Producer did not reach the failing MEDIA payload gate",
                        response.awaitGate(2_000));
                assertTrue("DataSource did not expose bytes before the producer failure",
                        reader.awaitFirstBytes(1_000));
            } finally {
                response.release();
            }

            assertTrue("Producer failure did not wake the DataSource reader",
                    reader.awaitDone(2_000));
            assertTrue("Producer failure did not end as IOException: " + reader.getFailure(),
                    reader.getFailure() instanceof IOException);
            assertTrue("Failed growing media unexpectedly reached EOF",
                    !reader.isEofObserved());
            assertNull("Failed growing media left a readable stale segment",
                    harness.holder.session.getReadableSegment(
                            SabrSegmentRequest.media(harness.videoFormat, 1)));
        }
    }

    @Test
    public void closingGrowingMediaReadWakesBlockedReader() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            final byte[] mediaBytes = filledBytes(64 * 1024, 50);
            final GatedMediaResponse response = new GatedMediaResponse(
                    1, SMOKE_VIDEO_ITAG, 1, 0, 5_000,
                    mediaBytes, new byte[0], 0, false, null);
            harness.downloader.enqueue(response);
            final AsyncSegmentReader reader = new AsyncSegmentReader(
                    harness.holder, harness.readerOwner,
                    SabrSegmentRequest.media(harness.videoFormat, 1), mediaBytes.length - 1);
            reader.start();
            try {
                assertTrue("Producer did not reach MEDIA_END gate", response.awaitGate(2_000));
                assertTrue("Reader did not consume the growing prefix",
                        reader.awaitFirstBytes(1_000));
                reader.closeDataSource();
                assertTrue("Closing DataSource did not wake its growing-file read",
                        reader.awaitDone(1_000));
                assertTrue("Closed growing read did not fail with IOException: "
                                + reader.getFailure(),
                        reader.getFailure() instanceof IOException);
            } finally {
                response.release();
            }
        }
    }

    @Test
    public void clearingSessionDoesNotResurrectGrowingSegment() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            final byte[] mediaBytes = filledBytes(64 * 1024, 70);
            final SabrSegmentRequest request =
                    SabrSegmentRequest.media(harness.videoFormat, 1);
            final GatedMediaResponse response = new GatedMediaResponse(
                    1, SMOKE_VIDEO_ITAG, 1, 0, 5_000,
                    mediaBytes, new byte[0], 0, false, null);
            harness.downloader.enqueue(response);
            final AsyncSegmentReader reader = new AsyncSegmentReader(
                    harness.holder, harness.readerOwner, request, mediaBytes.length - 1);
            reader.start();
            try {
                assertTrue("Producer did not reach MEDIA_END gate", response.awaitGate(2_000));
                assertTrue("Reader did not consume the growing prefix",
                        reader.awaitFirstBytes(1_000));
                harness.holder.session.clearCache();
                assertTrue("Clearing the session did not wake the growing-file reader",
                        reader.awaitDone(1_000));
                assertNull("Cleared session retained a readable in-flight segment",
                        harness.holder.session.getReadableSegment(request));
            } finally {
                response.release();
            }
            waitForTrace(harness, "response n=0", 2_000);
            assertNull("Completed producer resurrected a cleared segment",
                    harness.holder.session.getCachedSegment(request));
        }
    }

    @Test
    public void compressedAndInitializationSegmentsRemainCompletionOnly() throws Exception {
        final byte[] rawCompressedMedia = new byte[]{30, 31, 32, 33, 34, 35};
        final byte[] compressedMedia = gzip(rawCompressedMedia);
        final int compressedSplit = Math.max(1, compressedMedia.length / 2);
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            final GatedMediaResponse response = new GatedMediaResponse(
                    1, SMOKE_VIDEO_ITAG, 1, 0, 5_000,
                    Arrays.copyOfRange(compressedMedia, 0, compressedSplit),
                    Arrays.copyOfRange(compressedMedia, compressedSplit, compressedMedia.length),
                    1, false, null);
            verifyCompletionOnly(harness,
                    SabrSegmentRequest.media(harness.videoFormat, 1),
                    response, rawCompressedMedia, "compressed media");
        }

        final byte[] initializationBytes = new byte[]{40, 41, 42, 43};
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            final GatedMediaResponse response = new GatedMediaResponse(
                    1, SMOKE_VIDEO_ITAG, 0, 0, 0,
                    Arrays.copyOfRange(initializationBytes, 0, 2),
                    Arrays.copyOfRange(initializationBytes, 2, initializationBytes.length),
                    0, true, null);
            verifyInitializationCompletionOnly(harness,
                    SabrSegmentRequest.initialization(harness.videoFormat),
                    response, initializationBytes, "initialization segment");
        }
    }

    @Test
    public void recoverableCompressedAndOverflowMediaRetryThroughDemandPump()
            throws Exception {
        verifyDemandIntegrityRetry("Could not decompress gzip SABR media segment",
                new UmpFixture()
                        .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000,
                                4, 1)
                        .media(2, new byte[]{1, 2, 3, 4})
                        .mediaEnd(2));
        verifyDemandIntegrityRetry("SABR media length overflow: headerId=2",
                new UmpFixture()
                        .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000, 1)
                        .media(2, new byte[]{1, 2})
                        .mediaEnd(2));
    }

    @Test
    public void terminalMediaCollectorErrorsFailThroughDemandPump() throws Exception {
        verifyDemandIntegrityFailure("SABR media segment too large: headerId=2",
                new UmpFixture()
                        .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000,
                                (long) Integer.MAX_VALUE + 1L)
                        .mediaEnd(2));
        verifyDemandIntegrityFailure("Unsupported SABR media compression: 99",
                new UmpFixture()
                        .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000,
                                4, 99)
                        .media(2)
                        .mediaEnd(2));
    }

    @Test
    public void generatedLargeMediaPartStaysOffHeap() throws Exception {
        final Bundle arguments = InstrumentationRegistry.getArguments();
        final String mediaBytesArgument = arguments.getString("sabrStressMediaBytes");
        assumeTrue("Set sabrStressMediaBytes to run the SABR heap pressure regression test",
                mediaBytesArgument != null);
        final int mediaBytes = Integer.parseInt(mediaBytesArgument);
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new GeneratedLargeMediaResponse(
                    2, SMOKE_VIDEO_ITAG, 1, 0, 5_000, mediaBytes));

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 1);
            final long beforeUsed = usedHeapBytes();
            harness.openMediaSegment(request, 30_000);

            final long peakCached = harness.holder.session.getPeakCachedBytes();
            final SabrMediaSegment segment = harness.holder.session.getCachedSegment(request);
            assertNotNull("Large SABR media segment was not cached", segment);
            System.out.println("SABR_OOM_REGRESSION mediaBytes=" + mediaBytes
                    + " beforeUsed=" + beforeUsed
                    + " afterUsed=" + usedHeapBytes()
                    + " peakCachedBytes=" + peakCached
                    + " diskBacked=" + segment.isDiskBacked()
                    + " trace=" + harness.holder.session.getDiagnosticTrace());
            assertEquals("Large SABR segment cache accounting changed", mediaBytes, peakCached);
            assertTrue("Large SABR media segment must be disk-backed", segment.isDiskBacked());
        }
    }

    @Test
    public void generatedSabrCachePressureStaysOffHeap()
            throws Exception {
        final Bundle arguments = InstrumentationRegistry.getArguments();
        final String segmentBytesArgument = arguments.getString("sabrStressSegmentBytes");
        assumeTrue("Set sabrStressSegmentBytes to run the accessibility OOM regression test",
                segmentBytesArgument != null);
        final int segmentBytes = Integer.parseInt(segmentBytesArgument);
        final int segmentCount = Integer.parseInt(arguments.getString(
                "sabrStressSegmentCount", "7"));
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            for (int i = 1; i <= segmentCount; i++) {
                harness.downloader.enqueue(new GeneratedLargeMediaResponse(
                        i, SMOKE_VIDEO_ITAG, i, (i - 1L) * 5_000L, 5_000L,
                        segmentBytes));
            }

            final long beforeUsed = usedHeapBytes();
            for (int i = 1; i <= segmentCount; i++) {
                harness.holder.session.pumpOnceStreaming(new Localization("en", "US"));
                final SabrMediaSegment segment = harness.holder.session.getCachedSegment(
                        SabrSegmentRequest.media(harness.videoFormat, i));
                assertNotNull("Generated SABR segment was not cached: " + i, segment);
                assertTrue("Generated SABR media segment must be disk-backed: " + i,
                        segment.isDiskBacked());
                System.out.println("SABR_ACCESSIBILITY_OOM_REGRESSION cachedSegment=" + i
                        + " used=" + usedHeapBytes()
                        + " peakCachedBytes=" + harness.holder.session.getPeakCachedBytes());
            }

            final AtomicReference<Throwable> allocationFailure = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    AccessibilityEvent.obtain().recycle();
                } catch (final Throwable e) {
                    allocationFailure.set(e);
                }
            });

            final Throwable thrown = allocationFailure.get();
            final long expectedCachedBytes = (long) segmentBytes * segmentCount;
            System.out.println("SABR_ACCESSIBILITY_OOM_REGRESSION beforeUsed=" + beforeUsed
                    + " afterUsed=" + usedHeapBytes()
                    + " segmentBytes=" + segmentBytes
                    + " segmentCount=" + segmentCount
                    + " peakCachedBytes=" + harness.holder.session.getPeakCachedBytes()
                    + " allocationFailure=" + (thrown == null ? "" : messageChain(thrown)));
            assertNull("Accessibility small allocation failed after SABR cache pressure",
                    thrown);
            assertEquals("SABR cache accounting did not include generated media",
                    expectedCachedBytes, harness.holder.session.getPeakCachedBytes());
        }
    }

    @Test
    public void contextUpdateAndSendingPolicyUpdateSessionState() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                            contextUpdate(10, new byte[]{1}, true, 1))
                    .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                            contextUpdate(20, new byte[]{2}, false, 1))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_CONTEXT_SENDING_POLICY,
                            contextPolicy(new int[]{20}, new int[]{10}, new int[0]))
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));
            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Context update was not decoded: " + trace,
                    trace.contains("57=[type=10"));
            assertTrue("Context sending policy was not decoded: " + trace,
                    trace.contains("59=[start=[20], stop=[10], discard=[]]"));
            assertTrue("Context 20 was not activated by sending policy",
                    activeContextTypes(harness).contains(20));
            assertTrue("Context 10 was not made unsent by sending policy",
                    unsentContextTypes(harness).contains(10));
        }
    }

    private static void runSmokeCase(final SmokeCase smokeCase) throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        assertTrue("The target process must use PipePipe's App initialization",
                context instanceof App);

        final Bundle arguments = InstrumentationRegistry.getArguments();
        final String cookieFile = arguments.getString("cookieFile", "");
        if (!cookieFile.isEmpty()) {
            ServiceList.YouTube.setTokens(readTextFile(new File(cookieFile)).trim());
        }
        final String url = arguments.getString("url",
                smokeCase.isSponsorBlockCase() ? RICKROLL_URL : DEFAULT_URL);
        final String client = arguments.getString("youtubeClient", "mweb");
        NewPipe.setYoutubePlayerClient(client);

        // This is intentionally live extraction: the test should detect upstream protocol changes.
        final StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
        assertTrue("Extractor returned no SABR video stream for client=" + client,
                info.getVideoStreams().stream().anyMatch(SabrPlaybackSmokeTest::isSabr)
                        || info.getVideoOnlyStreams().stream()
                        .anyMatch(SabrPlaybackSmokeTest::isSabr));

        final int maxVideoHeight = Integer.parseInt(arguments.getString("maxVideoHeight",
                String.valueOf(DEFAULT_MAX_VIDEO_HEIGHT)));
        final String targetCodec = arguments.getString("targetCodec", "");
        final PlayerDataSource dataSource = new PlayerDataSource(context,
                DownloaderImpl.USER_AGENT, new DefaultBandwidthMeter.Builder(context).build());
        final VideoPlaybackResolver resolver = new VideoPlaybackResolver(context, dataSource,
                new BoundedQualityResolver(maxVideoHeight, targetCodec));
        final MediaSource mediaSource = resolver.resolve(info);
        assertNotNull("VideoPlaybackResolver returned no MediaSource", mediaSource);
        assertNull("Resolving a SABR MediaSource eagerly created a session",
                findHolder(info.getId()));
        final long tailStartPositionMs;
        if (smokeCase.isSponsorBlockCase()) {
            final long extractedDurationMs = info.getDuration() * 1000L;
            assertTrue("Video is too short for the SponsorBlock tail test: "
                    + extractedDurationMs, extractedDurationMs > 30_000);
            tailStartPositionMs = extractedDurationMs - 30_000;
        } else {
            tailStartPositionMs = C.TIME_UNSET;
        }
        if (smokeCase.kind == SmokeCase.Kind.STALLED_READER) {
            try (SabrSessionStore.Lease lease = acquireSourceLease(context, mediaSource)) {
                verifyStalledReaderReadAhead(holderOf(lease));
            } finally {
                SabrSessionStore.evict(info.getId());
            }
            return;
        }
        if (smokeCase.kind == SmokeCase.Kind.REWIND_STATE) {
            try (SabrSessionStore.Lease lease = acquireSourceLease(context, mediaSource)) {
                verifyRewindResetsSabrState(holderOf(lease));
            } finally {
                SabrSessionStore.evict(info.getId());
            }
            return;
        }
        final boolean simulateEvictedRewind = smokeCase.kind == SmokeCase.Kind.EVICTED_REWIND;
        final SabrSessionStore.Lease injectedLease =
                smokeCase.kind == SmokeCase.Kind.MISSING_INITIALIZATION
                        ? acquireSourceLease(context, mediaSource) : null;
        final SabrSessionStore.Holder injectedHolder = injectedLease == null
                ? null : discardSabrInitialization(holderOf(injectedLease));

        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch firstVideoFrame = new CountDownLatch(1);
        final CountDownLatch audioStarted = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        final AtomicReference<CountDownLatch> seekProcessed =
                new AtomicReference<>(new CountDownLatch(1));
        final AtomicReference<PlaybackException> playerError = new AtomicReference<>();
        final AtomicReference<Long> seekPositionReported = new AtomicReference<>();
        final AtomicBoolean endedEarly = new AtomicBoolean();
        final AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        final AtomicReference<SurfaceTexture> textureRef = new AtomicReference<>();
        final AtomicReference<Surface> surfaceRef = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final SurfaceTexture texture = new SurfaceTexture(0);
            final Surface surface = new Surface(texture);
            final LegacySubtitleRenderersFactory renderersFactory =
                    new LegacySubtitleRenderersFactory(context);
            renderersFactory.setEnableDecoderFallback(true);
            final ExoPlayer player = new ExoPlayer.Builder(context, renderersFactory)
                    .setTrackSelector(new DefaultTrackSelector(context))
                    .setLoadControl(new LoadController())
                    .build();
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(final int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        ready.countDown();
                    } else if (playbackState == Player.STATE_ENDED) {
                        endedEarly.set(true);
                        ended.countDown();
                    }
                }

                @Override
                public void onPlayerError(final PlaybackException error) {
                    playerError.compareAndSet(null, error);
                    ready.countDown();
                    firstVideoFrame.countDown();
                    audioStarted.countDown();
                }

                @Override
                public void onPositionDiscontinuity(final Player.PositionInfo oldPosition,
                                                    final Player.PositionInfo newPosition,
                                                    final int reason) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        seekPositionReported.set(newPosition.positionMs);
                        seekProcessed.get().countDown();
                    }
                }
            });
            player.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onRenderedFirstFrame(final EventTime eventTime,
                                                 final Object output,
                                                 final long renderTimeMs) {
                    firstVideoFrame.countDown();
                }

                @Override
                public void onAudioPositionAdvancing(final EventTime eventTime,
                                                     final long playoutStartSystemTimeMs) {
                    audioStarted.countDown();
                }
            });
            player.setVideoSurface(surface);
            player.setVolume(0f);
            player.setMediaSource(mediaSource);
            if (tailStartPositionMs != C.TIME_UNSET) {
                player.seekTo(tailStartPositionMs);
            }
            player.prepare();
            player.play();
            textureRef.set(texture);
            surfaceRef.set(surface);
            playerRef.set(player);
        });

        try {
            assertTrue("Player did not reach READY within " + PREPARE_TIMEOUT_SECONDS + "s",
                    ready.await(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Player failed while preparing", playerError.get());
            assertTrue("MediaCodec did not render a video frame",
                    firstVideoFrame.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Player failed while starting video", playerError.get());
            assertTrue("Audio output did not start",
                    audioStarted.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Player failed while starting audio", playerError.get());
            if (injectedHolder != null) {
                verifyInitializationRecovery(injectedHolder);
            }
            if (smokeCase.isSponsorBlockCase()) {
                verifySponsorBlockSkipToEnd(playerRef.get(), smokeCase, tailStartPositionMs,
                        seekProcessed, seekPositionReported, playerError, ended);
                return;
            }

            final long linearPlaybackMs = Long.parseLong(arguments.getString(
                    "linearPlaybackMs", String.valueOf(DEFAULT_LINEAR_PLAYBACK_MS)));
            final long initialPositionMs = positionOf(playerRef.get());
            waitForPosition(playerRef.get(), initialPositionMs + linearPlaybackMs,
                    PLAYBACK_TIMEOUT_SECONDS);
            assertNull("Player failed during linear playback", playerError.get());

            final long postSeekPlaybackMs = Long.parseLong(arguments.getString(
                    "postSeekPlaybackMs", String.valueOf(DEFAULT_POST_SEEK_PLAYBACK_MS)));
            final long durationMs = durationOf(playerRef.get());
            final long seekPositionMs = seekPositionMs(arguments, durationMs,
                    simulateEvictedRewind ? Math.max(20_000, postSeekPlaybackMs)
                            : postSeekPlaybackMs);
            if (simulateEvictedRewind) {
                assertTrue("Video is too short for an eviction/rewind test: " + durationMs,
                        seekPositionMs >= 60_000);
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> playerRef.get().seekTo(seekPositionMs));
            assertTrue("Player did not report processing the seek",
                    seekProcessed.get().await(10, TimeUnit.SECONDS));
            assertNotNull("Seek discontinuity did not report a new position",
                    seekPositionReported.get());
            assertTrue("Seek landed outside the expected position: requested=" + seekPositionMs
                            + " reported=" + seekPositionReported.get(),
                    Math.abs(seekPositionReported.get() - seekPositionMs) <= 1_000);
            waitForPosition(playerRef.get(), seekPositionMs + postSeekPlaybackMs,
                    PLAYBACK_TIMEOUT_SECONDS);
            assertNull("Player failed after seek", playerError.get());
            if (simulateEvictedRewind) {
                final SabrSessionStore.Holder holder = getHolder(info.getId());
                final long rewindPositionMs = 10_000;
                discardCachedWindow(holder, holder.audioFormat, rewindPositionMs);
                discardCachedWindow(holder, holder.videoFormat, rewindPositionMs);
                final long edgeBeforeRewindMs = holder.session.getStreamState()
                        .getMinBufferedEndMs();
                assertTrue("Rewind target is not behind the SABR edge: target="
                                + rewindPositionMs + " edge=" + edgeBeforeRewindMs,
                        rewindPositionMs < edgeBeforeRewindMs);

                final CountDownLatch rewindProcessed = new CountDownLatch(1);
                seekProcessed.set(rewindProcessed);
                seekPositionReported.set(null);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(
                        () -> playerRef.get().seekTo(rewindPositionMs));
                assertTrue("Player did not process the backward seek",
                        rewindProcessed.await(10, TimeUnit.SECONDS));
                assertNotNull("Backward seek did not report a new position",
                        seekPositionReported.get());
                assertTrue("Backward seek landed outside the expected position: requested="
                                + rewindPositionMs + " reported=" + seekPositionReported.get(),
                        Math.abs(seekPositionReported.get() - rewindPositionMs) <= 1_000);
                final long postRewindPlaybackMs = Long.parseLong(arguments.getString(
                        "postRewindPlaybackMs",
                        String.valueOf(DEFAULT_POST_REWIND_PLAYBACK_MS)));
                waitForPosition(playerRef.get(), rewindPositionMs + postRewindPlaybackMs,
                        PLAYBACK_TIMEOUT_SECONDS);
                assertNull("Player failed after evicted-segment rewind", playerError.get());
                final String trace = holder.session.getDiagnosticTrace();
                // MediaPeriod now asks the pump to rewind as soon as it sees an out-of-buffer seek.
                // The old data-source timeout path ("recovery type=rewind") is only a fallback.
                assertTrue("SABR pump did not execute rewind recovery: " + trace,
                        trace.contains("pump_rewind"));
            }
            assertTrue("Content ended before playback and seek checks completed",
                    !endedEarly.get() || durationMs < 8_000);
            final String maxCachedBytesArgument = arguments.getString("maxCachedBytes");
            if (maxCachedBytesArgument != null) {
                final long maximum = Long.parseLong(maxCachedBytesArgument);
                final SabrSessionStore.Holder holder = getHolder(info.getId());
                final long observed = holder.session.getPeakCachedBytes();
                System.out.println("SABR_MEMORY height=" + holder.videoFormat.getHeight()
                        + " itag=" + holder.videoFormat.getItag()
                        + " peakCachedBytes=" + observed
                        + " maxCachedBytes=" + maximum);
                assertTrue("SABR cache exceeded bound: observed=" + observed
                        + " maximum=" + maximum, observed <= maximum);
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                final ExoPlayer player = playerRef.get();
                if (player != null) {
                    player.release();
                }
                final Surface surface = surfaceRef.get();
                if (surface != null) {
                    surface.release();
                }
                final SurfaceTexture texture = textureRef.get();
                if (texture != null) {
                    texture.release();
                }
            });
            if (injectedLease != null) {
                injectedLease.close();
            }
            SabrSessionStore.evict(info.getId());
        }
    }

    private static String readTextFile(final File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isSabr(final VideoStream stream) {
        return stream.getDeliveryMethod() == DeliveryMethod.SABR;
    }

    private static boolean isSabr(final AudioStream stream) {
        return stream.getDeliveryMethod() == DeliveryMethod.SABR;
    }

    private static void runAnonymousAudioWindow(final Context context,
                                                final StreamInfo info,
                                                final int index,
                                                final long playbackMs) throws Exception {
        final PlayerDataSource dataSource = new PlayerDataSource(context,
                DownloaderImpl.USER_AGENT, new DefaultBandwidthMeter.Builder(context).build());
        final MediaSource mediaSource = new AudioPlaybackResolver(context, dataSource).resolve(info);
        assertNotNull("Audio resolver returned no MediaSource for item=" + index
                + " video=" + info.getId(), mediaSource);

        final AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        final AtomicReference<PlaybackException> playerError = new AtomicReference<>();
        final CountDownLatch ready = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final ExoPlayer player = new ExoPlayer.Builder(context)
                    .setLoadControl(new LoadController())
                    .build();
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(final int state) {
                    if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                        ready.countDown();
                    }
                }

                @Override
                public void onPlayerError(final PlaybackException error) {
                    playerError.compareAndSet(null, error);
                    ready.countDown();
                }
            });
            player.setVolume(0f);
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();
            playerRef.set(player);
        });

        SabrSessionStore.Holder holder = null;
        try {
            assertTrue("Anonymous audio item did not become ready: index=" + index
                            + " video=" + info.getId(),
                    ready.await(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Anonymous audio item failed during startup: index=" + index
                    + " video=" + info.getId(), playerError.get());
            holder = getHolder(info.getId());
            holder.session.setTraceEnabled(true);
            final long durationMs = durationOf(playerRef.get());
            assertTrue("Anonymous probe item is too short to cross 60s: index=" + index
                    + " video=" + info.getId() + " durationMs=" + durationMs,
                    durationMs > 61_000);
            final long targetMs = Math.min(playbackMs, durationMs - 1_000);
            waitForPositionWithSabrProgress(playerRef.get(), info.getId(), targetMs,
                    TimeUnit.MILLISECONDS.toSeconds(targetMs) + PLAYBACK_TIMEOUT_SECONDS,
                    playerError);
            final String trace = holder.session.getDiagnosticTrace();
            assertNull("Anonymous audio item failed during playback: index=" + index
                            + " video=" + info.getId() + " trace=" + trace,
                    playerError.get());
            assertTrue("Anonymous audio item did not reach target: index=" + index
                            + " video=" + info.getId() + " targetMs=" + targetMs
                            + " positionMs=" + positionOf(playerRef.get()) + " trace=" + trace,
                    positionOf(playerRef.get()) >= targetMs);
            final int maxProtectionStatus = holder.session.getMaxStreamProtectionStatus();
            assertTrue("Anonymous audio item received unexpected protection status: index="
                            + index + " video=" + info.getId() + " maxStatus="
                            + maxProtectionStatus + " trace=" + trace,
                    maxProtectionStatus <= 1);
            System.out.println("SABR_ANONYMOUS_SEQUENCE index=" + index
                    + " video=" + info.getId()
                    + " positionMs=" + positionOf(playerRef.get())
                    + " maxProtectionStatus=" + maxProtectionStatus
                    + " trace=" + trace);
        } catch (final Exception | AssertionError failure) {
            final String trace = holder == null ? "<no SABR session>"
                    : holder.session.getDiagnosticTrace();
            System.out.println("SABR_ANONYMOUS_SEQUENCE_FAILURE index=" + index
                    + " video=" + info.getId()
                    + " positionMs=" + (playerRef.get() == null ? -1
                    : positionOf(playerRef.get()))
                    + " trace=" + trace);
            throw failure;
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (playerRef.get() != null) {
                    playerRef.get().release();
                }
            });
            SabrSessionStore.evict(info.getId());
        }
    }

    private static SabrSessionStore.Holder getHolder(final String videoId) throws Exception {
        final SabrSessionStore.Holder holder = findHolder(videoId);
        assertNotNull("SABR session was not created", holder);
        return holder;
    }

    private static SabrSessionStore.Holder findHolder(final String videoId) throws Exception {
        final Field sessionsField = SabrSessionStore.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Map<Object, SabrSessionStore.Holder> sessions =
                (Map<Object, SabrSessionStore.Holder>) sessionsField.get(null);
        SabrSessionStore.Holder holder = null;
        for (final SabrSessionStore.Holder candidate : sessions.values()) {
            if (videoId.equals(candidate.videoId)) {
                holder = candidate;
                break;
            }
        }
        return holder;
    }

    private static StatusBarNotification awaitBackoffNotification(
            final Context context, final boolean expected) {
        for (int attempt = 0; attempt < 100; attempt++) {
            final StatusBarNotification notification = findBackoffNotification(context);
            if ((notification != null) == expected) {
                return notification;
            }
            SystemClock.sleep(20L);
        }
        return findBackoffNotification(context);
    }

    private static StatusBarNotification findBackoffNotification(final Context context) {
        final NotificationManager manager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        for (final StatusBarNotification notification : manager.getActiveNotifications()) {
            if (notification.getId() == SabrBackoffCoordinator.NOTIFICATION_ID) {
                return notification;
            }
        }
        return null;
    }

    private static SabrSessionStore.Lease acquireSourceLease(
            final Context context, final MediaSource mediaSource) throws Exception {
        final SabrDashMediaSource sabrSource = findSabrSource(mediaSource);
        assertNotNull("Expected a SABR child in " + mediaSource.getClass(), sabrSource);
        final Field specField = SabrDashMediaSource.class.getDeclaredField("spec");
        specField.setAccessible(true);
        final SabrSourceSpec spec = (SabrSourceSpec) specField.get(sabrSource);
        final Method acquire = SabrSessionStore.class.getDeclaredMethod(
                "acquire", Context.class, SabrSourceSpec.class);
        acquire.setAccessible(true);
        return (SabrSessionStore.Lease) acquire.invoke(null, context, spec);
    }

    private static SabrDashMediaSource findSabrSource(final MediaSource mediaSource)
            throws Exception {
        if (mediaSource instanceof SabrDashMediaSource) {
            return (SabrDashMediaSource) mediaSource;
        }
        if (!"androidx.media3.exoplayer.source.MergingMediaSource"
                .equals(mediaSource.getClass().getName())) {
            return null;
        }
        final Field childrenField = mediaSource.getClass().getDeclaredField("mediaSources");
        childrenField.setAccessible(true);
        for (final MediaSource child : (MediaSource[]) childrenField.get(mediaSource)) {
            final SabrDashMediaSource result = findSabrSource(child);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static SabrSessionStore.Holder holderOf(final SabrSessionStore.Lease lease)
            throws Exception {
        final Field holderField = SabrSessionStore.Lease.class.getDeclaredField("holder");
        holderField.setAccessible(true);
        return (SabrSessionStore.Holder) holderField.get(lease);
    }

    private static void verifyStalledReaderReadAhead(
            final SabrSessionStore.Holder holder) throws Exception {
        final Object readerOwner = new Object();
        final Method setActiveTracks = SabrSessionStore.Holder.class.getDeclaredMethod(
                "setActiveTracks", Object.class, boolean.class, boolean.class);
        final Method releaseTracks = SabrSessionStore.Holder.class.getDeclaredMethod(
                "releaseTracks", Object.class);
        final Method getPump = SabrSessionStore.Holder.class.getDeclaredMethod(
                "getPump", Localization.class);
        setActiveTracks.setAccessible(true);
        releaseTracks.setAccessible(true);
        getPump.setAccessible(true);
        setActiveTracks.invoke(holder, readerOwner, true, true);
        try {
            assertTrue("The test must begin with an unstarted active reader",
                    holder.hasUnstartedActiveReader());
            assertEquals("Reader head must remain at startup", 0, holder.getReaderHeadMs());
            assertEquals("Reader tail must remain at startup", 0, holder.getReaderTailMs());

            final Object pump = getPump.invoke(holder, new Localization("en", "US"));
            final Method ensureStarted = pump.getClass().getDeclaredMethod("ensureStarted");
            ensureStarted.setAccessible(true);
            ensureStarted.invoke(pump);
            final long deadlineNs = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(PREPARE_TIMEOUT_SECONDS);
            String trace = holder.session.getDiagnosticTrace();
            while (!trace.contains("pump_throttled ") && System.nanoTime() < deadlineNs) {
                Thread.sleep(250);
                trace = holder.session.getDiagnosticTrace();
            }
            assertTrue("Pump did not apply the startup read-ahead bound: " + trace,
                    trace.contains("pump_throttled ")
                            && trace.contains("unstartedReader=true"));

            final int requestNumber = holder.session.getRequestNumber();
            final long edgeMs = holder.session.getStreamState().getMinBufferedEndMs();
            final long cachedBytes = holder.session.getCachedBytes();
            Thread.sleep(1_500);
            assertEquals("Pump continued making SABR requests while the reader was stalled",
                    requestNumber, holder.session.getRequestNumber());
            assertEquals("Buffered edge advanced while the reader was stalled",
                    edgeMs, holder.session.getStreamState().getMinBufferedEndMs());
            assertEquals("Cache grew while the reader was stalled",
                    cachedBytes, holder.session.getCachedBytes());
            assertEquals("Reader head unexpectedly advanced", 0, holder.getReaderHeadMs());
            assertEquals("Reader tail unexpectedly advanced", 0, holder.getReaderTailMs());
        } finally {
            releaseTracks.invoke(holder, readerOwner);
        }
    }

    private static void discardCachedWindow(final SabrSessionStore.Holder holder,
                                            final YoutubeSabrFormat format,
                                            final long positionMs) {
        final int centerSequence = holder.session.getStreamState()
                .getSegmentNumberAtOrAfterTimeMs(format, positionMs);
        for (int sequence = Math.max(1, centerSequence - 1);
             sequence <= centerSequence + 2; sequence++) {
            holder.session.discardCachedSegment(SabrSegmentRequest.media(format, sequence));
        }
        assertNull("Fault injection did not evict target segment for itag=" + format.getItag(),
                holder.session.getCachedSegment(SabrSegmentRequest.media(format, centerSequence)));
    }

    private static void verifyInitializationRecovery(final SabrSessionStore.Holder holder) {
        final String trace = holder.session.getDiagnosticTrace();
        assertTrue("Audio bootstrap initialization was not restored: " + trace,
                trace.contains("bootstrap_init_restore itag=" + holder.audioFormat.getItag()));
        assertTrue("Video bootstrap initialization was not restored: " + trace,
                trace.contains("bootstrap_init_restore itag=" + holder.videoFormat.getItag()));
    }

    private static void verifyRewindResetsSabrState(
            final SabrSessionStore.Holder holder) throws Exception {
        final Localization localization = new Localization("en", "US");
        final YoutubeSabrFormat format = holder.videoFormat;
        final SabrSegmentRequest target = SabrSegmentRequest.media(format, 2);
        // A newly split playback session may legitimately receive policy-only responses before a
        // reader asks for media. Establish deterministic forward media state through the same
        // demand path used by production, then verify that rewind shrinks it and clears the cookie.
        for (int attempt = 0; attempt < 4
                && holder.session.getCachedSegment(target) == null; attempt++) {
            holder.session.prepareForForwardJump(target);
            holder.session.pumpOnceStreamingForDemand(localization, target);
            final long backoffMs = holder.session.getDemandBackoffRemainingMs();
            if (backoffMs > 0 && holder.session.getCachedSegment(target) == null) {
                Thread.sleep(backoffMs + 10);
            }
        }
        assertNotNull("Targeted SABR demand did not return media",
                holder.session.getCachedSegment(target));
        final int maxSegmentBefore = holder.session.getStreamState().getMaxSegment(format);
        assertTrue("Targeted SABR demand did not advance media state", maxSegmentBefore > 1);

        final Field playbackCookie = holder.session.getStreamState().getClass()
                .getDeclaredField("playbackCookie");
        playbackCookie.setAccessible(true);
        playbackCookie.set(holder.session.getStreamState(), new byte[]{1, 2, 3, 4});
        assertNotNull("Fault injection did not install a stale playback cookie",
                holder.session.getStreamState().getPlaybackCookie());

        holder.session.prepareForRewind(SabrSegmentRequest.media(format, 1));
        assertEquals("Rewind did not move the buffered range before the target", 0,
                holder.session.getStreamState().getMaxSegment(format));
        assertNull("Rewind retained the stale SABR playback cookie",
                holder.session.getStreamState().getPlaybackCookie());
    }

    private static void verifySponsorBlockSkipToEnd(
            final ExoPlayer player,
            final SmokeCase smokeCase,
            final long tailStartPositionMs,
            final AtomicReference<CountDownLatch> seekProcessed,
            final AtomicReference<Long> seekPositionReported,
            final AtomicReference<PlaybackException> playerError,
            final CountDownLatch ended) throws Exception {
        final long durationMs = durationOf(player);
        assertTrue("Cannot run SponsorBlock tail test when duration is unset",
                durationMs != C.TIME_UNSET);
        assertTrue("Video is too short for the SponsorBlock tail test: " + durationMs,
                durationMs > 30_000);
        assertTrue("Extractor and player durations disagree: extracted tail start="
                        + tailStartPositionMs + " player duration=" + durationMs,
                Math.abs(tailStartPositionMs - (durationMs - 30_000)) <= 1_000);
        final long sponsorStartMs = durationMs - 20_000;

        assertTrue("Playback did not start near duration - 30s: requested="
                        + tailStartPositionMs + " current=" + positionOf(player),
                positionOf(player) >= tailStartPositionMs - 1_000
                        && positionOf(player) < sponsorStartMs);
        if (smokeCase.kind == SmokeCase.Kind.SPONSOR_BLOCK_PLAYBACK) {
            waitForPosition(player, sponsorStartMs, PLAYBACK_TIMEOUT_SECONDS);
        } else {
            waitForPosition(player, tailStartPositionMs + 5_000, PLAYBACK_TIMEOUT_SECONDS);
            seekAndAssertPosition(player, sponsorStartMs, "SponsorBlock start",
                    seekProcessed, seekPositionReported);
        }

        seekAndAssertPosition(player, durationMs, "SponsorBlock end",
                seekProcessed, seekPositionReported);
        assertTrue("Player did not reach ENDED after SponsorBlock skipped to duration",
                ended.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull("Player failed after SponsorBlock skipped to duration", playerError.get());
    }

    private static void seekAndAssertPosition(
            final ExoPlayer player,
            final long positionMs,
            final String description,
            final AtomicReference<CountDownLatch> seekProcessed,
            final AtomicReference<Long> seekPositionReported) throws Exception {
        seekProcessed.set(new CountDownLatch(1));
        seekPositionReported.set(null);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> player.seekTo(positionMs));
        assertTrue("Player did not report processing the " + description + " seek",
                seekProcessed.get().await(10, TimeUnit.SECONDS));
        assertNotNull(description + " seek did not report a new position",
                seekPositionReported.get());
        assertTrue(description + " seek landed outside the expected position: requested="
                        + positionMs
                        + " reported=" + seekPositionReported.get(),
                Math.abs(seekPositionReported.get() - positionMs) <= 1_000);
    }

    private static SabrSessionStore.Holder discardSabrInitialization(
            final SabrSessionStore.Holder holder) throws Exception {
        final SabrSegmentRequest audioInit =
                SabrSegmentRequest.initialization(holder.audioFormat);
        final SabrSegmentRequest videoInit =
                SabrSegmentRequest.initialization(holder.videoFormat);
        // Native bootstrap owns the authoritative init bytes. The independent playback session is
        // allowed to begin with media or a policy-only response, so do not require it to duplicate
        // both init segments before fault-injecting the active holder cache.
        holder.session.discardCachedSegment(audioInit);
        holder.session.discardCachedSegment(videoInit);
        clearStoredInitializationData(holder);
        holder.session.prepareForInitialization(holder.audioFormat);
        holder.session.prepareForInitialization(holder.videoFormat);
        assertNull(holder.session.getCachedSegment(audioInit));
        assertNull(holder.session.getCachedSegment(videoInit));
        return holder;
    }

    private static void clearStoredInitializationData(
            final SabrSessionStore.Holder holder) throws Exception {
        final Field initializationData =
                SabrSessionStore.Holder.class.getDeclaredField("initializationData");
        initializationData.setAccessible(true);
        @SuppressWarnings("unchecked") final Map<Integer, byte[]> values =
                (Map<Integer, byte[]>) initializationData.get(holder);
        values.remove(holder.audioFormat.getItag());
        values.remove(holder.videoFormat.getItag());
    }

    private static long positionOf(final ExoPlayer player) {
        final AtomicReference<Long> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> result.set(player.getCurrentPosition()));
        return result.get();
    }

    private static long durationOf(final ExoPlayer player) {
        final AtomicReference<Long> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> result.set(player.getDuration()));
        return result.get();
    }

    private static long seekPositionMs(final Bundle arguments, final long durationMs,
                                       final long requiredTailMs) {
        assertTrue("Cannot use fixed seek position when duration is unset",
                durationMs != C.TIME_UNSET);
        final long seekPositionMs = Long.parseLong(arguments.getString("seekPositionMs",
                String.valueOf(DEFAULT_SEEK_POSITION_MS)));
        assertTrue("seekPositionMs must be positive: " + seekPositionMs, seekPositionMs > 0);
        assertTrue("Video is too short for seek target: duration=" + durationMs
                        + " target=" + seekPositionMs + " requiredTail=" + requiredTailMs,
                durationMs > seekPositionMs + requiredTailMs);
        return seekPositionMs;
    }

    private static void waitForPosition(final ExoPlayer player, final long targetMs,
                                        final long timeoutSeconds) throws Exception {
        final long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadlineNs) {
            if (positionOf(player) >= targetMs) {
                return;
            }
            Thread.sleep(250);
        }
        assertEquals("Playback position did not reach target", targetMs, positionOf(player));
    }

    private static void waitForPositionWithSabrProgress(final ExoPlayer player,
                                                        final String videoId,
                                                        final long targetMs,
                                                        final long timeoutSeconds,
                                                        final AtomicReference<PlaybackException>
                                                                playerError) throws Exception {
        final long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadlineNs) {
            if (playerError.get() != null) {
                return;
            }
            final long positionMs = positionOf(player);
            SabrSessionStore.updatePlayerTime(videoId, positionMs);
            if (positionMs >= targetMs) {
                return;
            }
            Thread.sleep(250);
        }
        assertEquals("Playback position did not reach target", targetMs, positionOf(player));
    }

    private static void verifyDemandIntegrityRetry(final String expectedIssue,
                                                   final UmpFixture brokenResponse)
            throws Exception {
        final String expectedTrace = expectedIssue.startsWith("length-mismatch:")
                ? "SABR media length mismatch: headerId="
                        + expectedIssue.substring("length-mismatch:".length())
                : expectedIssue.startsWith("missing-media:")
                ? "SABR media length mismatch: headerId="
                        + expectedIssue.substring("missing-media:".length())
                : expectedIssue;
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(brokenResponse.bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(3, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.openMediaSegment(request, 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Integrity issue was not exercised: expected=" + expectedIssue
                    + " trace=" + trace, trace.contains(expectedTrace));
            assertNotNull("Demand retry did not fetch target after " + expectedIssue
                            + ": " + trace,
                    harness.holder.session.getCachedSegment(request));
        }
    }

    private static void verifyDemandIntegrityFailure(final String expectedTrace,
                                                     final UmpFixture brokenResponse)
            throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(brokenResponse.bytes());

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.openMediaSegmentExpectFailure(request, 5_000);

            final String trace = waitForTrace(harness, expectedTrace, 2_000);
            assertTrue("Terminal integrity issue was not exercised: expected=" + expectedTrace
                    + " trace=" + trace, trace.contains(expectedTrace));
        }
    }

    private static String waitForTrace(final SabrSmokeHarness harness,
                                       final String expected,
                                       final long timeoutMs) throws Exception {
        final long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        String trace = harness.holder.session.getDiagnosticTrace();
        while (!trace.contains(expected) && System.nanoTime() < deadlineNs) {
            Thread.sleep(25);
            trace = harness.holder.session.getDiagnosticTrace();
        }
        return trace;
    }

    private static void verifyCompletionOnly(final SabrSmokeHarness harness,
                                             final SabrSegmentRequest request,
                                             final GatedMediaResponse response,
                                             final byte[] expectedBytes,
                                             final String description) throws Exception {
        harness.downloader.enqueue(response);
        final AsyncSegmentReader reader = new AsyncSegmentReader(
                harness.holder, harness.readerOwner, request, 1);
        reader.start();
        try {
            assertTrue(description + " producer did not reach the MEDIA payload gate",
                    response.awaitGate(2_000));
            assertTrue(description + " became readable before completion",
                    !reader.awaitOpened(300));
        } finally {
            response.release();
        }
        assertTrue(description + " did not finish after completion",
                reader.awaitDone(2_000));
        assertNull(description + " read failed", reader.getFailure());
        assertTrue(description + " did not reach EOF", reader.isEofObserved());
        assertTrue(description + " returned unexpected bytes",
                Arrays.equals(expectedBytes, reader.bytesSnapshot()));
    }

    private static void verifyInitializationCompletionOnly(
            final SabrSmokeHarness harness,
            final SabrSegmentRequest request,
            final GatedMediaResponse response,
            final byte[] expectedBytes,
            final String description) throws Exception {
        harness.downloader.enqueue(response);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final Thread pump = new Thread(() -> {
            try {
                harness.holder.session.pumpOnceStreaming(new Localization("en", "US"));
            } catch (final Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        }, "SabrSmokeInitializationCompletion");
        pump.setDaemon(true);
        pump.start();
        try {
            assertTrue(description + " producer did not reach the MEDIA payload gate",
                    response.awaitGate(2_000));
            assertNull(description + " became readable before completion",
                    harness.holder.session.getReadableSegment(request));
        } finally {
            response.release();
        }
        assertTrue(description + " pump did not finish after completion",
                done.await(2_000, TimeUnit.MILLISECONDS));
        assertNull(description + " pump failed", failure.get());
        final SabrMediaSegment segment = harness.holder.session.getCachedSegment(request);
        assertNotNull(description + " was not cached after completion", segment);
        assertTrue(description + " returned unexpected bytes",
                Arrays.equals(expectedBytes, segment.getData()));
    }

    private static long usedHeapBytes() {
        final Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String messageChain(final Throwable throwable) {
        final StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(current.getClass().getSimpleName())
                    .append(':')
                    .append(current.getMessage());
            current = current.getCause();
        }
        return builder.toString();
    }

    private static YoutubeSabrFormat smokeFormat(final int itag, final boolean audio)
            throws Exception {
        return smokeFormat(itag, audio, null, -1, -1);
    }

    private static YoutubeSabrFormat smokeFormat(final int itag,
                                                 final boolean audio,
                                                 final String initializationUrl,
                                                 final long initRangeStart,
                                                 final long initRangeEnd)
            throws Exception {
        final Constructor<YoutubeSabrFormat> constructor =
                YoutubeSabrFormat.class.getDeclaredConstructor(int.class, long.class,
                        String.class, String.class, String.class, String.class, boolean.class,
                        String.class, String.class, boolean.class, int.class, int.class,
                        int.class, long.class, long.class, String.class, long.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                itag,
                123456L,
                audio ? "audio-xtags" : "video-xtags",
                audio ? "audio/mp4" : "video/mp4",
                audio ? "audio-track" : null,
                audio ? "English original" : null,
                audio,
                audio ? null : "1080p",
                audio ? "AUDIO_QUALITY_MEDIUM" : null,
                false,
                audio ? -1 : 1920,
                audio ? -1 : 1080,
                audio ? 128_000 : 2_000_000,
                100_000L,
                300_000L,
                initializationUrl,
                initRangeStart,
                initRangeEnd);
    }

    private static byte[] mp4Sidx(final int... durationsMs) {
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(32 + durationsMs.length * 12)
                .order(java.nio.ByteOrder.BIG_ENDIAN);
        buffer.putInt(buffer.capacity());
        buffer.put(new byte[]{'s', 'i', 'd', 'x'});
        buffer.putInt(0); // version + flags
        buffer.putInt(1); // reference ID
        buffer.putInt(1_000); // timescale
        buffer.putInt(0); // earliest presentation time
        buffer.putInt(0); // first offset
        buffer.putShort((short) 0);
        buffer.putShort((short) durationsMs.length);
        for (final int durationMs : durationsMs) {
            buffer.putInt(1); // referenced size
            buffer.putInt(durationMs);
            buffer.putInt(0); // SAP flags
        }
        return buffer.array();
    }

    private static YoutubeSabrInfo smokeInfo(final YoutubeSabrFormat audioFormat,
                                             final YoutubeSabrFormat videoFormat)
            throws Exception {
        final Constructor<YoutubeSabrInfo> constructor =
                YoutubeSabrInfo.class.getDeclaredConstructor(YoutubeSabrClientProfile.class,
                        String.class, String.class, String.class, String.class, String.class,
                        String.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(YoutubeSabrClientProfile.MWEB, "smoke-video", "cpn",
                "2.20250122.04.00", "visitor", "https://sabr.test",
                base64(new byte[]{1, 2, 3, 4}), Arrays.asList(audioFormat, videoFormat));
    }

    private static byte[] nextRequestPolicy(final int backoffMs) {
        return nextRequestPolicy(backoffMs, null, null);
    }

    private static byte[] nextRequestPolicy(final int backoffMs,
                                            final byte[] playbackCookie,
                                            final String videoId) {
        final Proto proto = proto()
                .u64(1, 3_000)
                .u64(2, 4_000)
                .u64(3, 1_000)
                .u64(4, backoffMs)
                .u64(5, 500)
                .u64(6, 600);
        if (playbackCookie != null) {
            proto.message(7, playbackCookie);
        }
        if (videoId != null) {
            proto.string(8, videoId);
        }
        return proto.bytes();
    }

    private static byte[] streamProtection(final int status, final int maxRetries) {
        return proto()
                .u64(1, status)
                .u64(2, maxRetries)
                .bytes();
    }

    private static byte[] playbackCookie() {
        return proto()
                .u64(1, 1080)
                .u64(2, 1)
                .message(7, formatId(SMOKE_VIDEO_ITAG))
                .message(8, formatId(SMOKE_AUDIO_ITAG))
                .bytes();
    }

    private static byte[] formatId(final int itag) {
        return proto().u64(1, itag).u64(2, 123456).bytes();
    }

    private static byte[] liveMetadata(final long headSeq,
                                       final long headTimeMs,
                                       final boolean postLiveDvr) {
        return proto()
                .string(1, "broadcast")
                .u64(3, headSeq)
                .u64(4, headTimeMs)
                .u64(5, headTimeMs + 1000)
                .string(6, "smoke-video")
                .u64(8, postLiveDvr ? 1 : 0)
                .u64(12, 0)
                .u64(13, 1000)
                .u64(14, headTimeMs)
                .u64(15, 1000)
                .bytes();
    }

    private static byte[] initializationMetadata(final int itag,
                                                 final long endSegment,
                                                 final long endTimeMs,
                                                 final String mimeType) {
        return proto()
                .message(2, formatId(itag))
                .u64(3, endTimeMs)
                .u64(4, endSegment)
                .string(5, mimeType)
                .bytes();
    }

    private static byte[] redirect(final String url) {
        return proto().string(1, url).bytes();
    }

    private static byte[] sabrError(final String type, final int code) {
        return proto().string(1, type).u64(2, code).bytes();
    }

    private static byte[] reloadPlayerResponse(final String token) {
        return proto()
                .message(1, proto()
                        .message(1, proto()
                                .string(1, token)
                                .bytes())
                        .bytes())
                .bytes();
    }

    private static byte[] sabrSeek(final long mediaTime,
                                   final int timescale,
                                   final int source) {
        return proto()
                .u64(1, mediaTime)
                .u64(2, timescale)
                .u64(3, source)
                .bytes();
    }

    private static byte[] playbackStartPolicy() {
        return proto()
                .message(1, proto().u64(1, 100_000).u64(2, 1_500).bytes())
                .message(2, proto().u64(1, 120_000).u64(2, 2_500).bytes())
                .u64(5, 9)
                .bytes();
    }

    private static byte[] formatSelectionConfig() {
        return proto()
                .packedU64(2, SMOKE_VIDEO_ITAG, SMOKE_AUDIO_ITAG)
                .string(3, "smoke-video")
                .u64(4, 1080)
                .bytes();
    }

    private static byte[] selectableFormats() {
        return proto()
                .message(1, formatIdWithXtags(SMOKE_VIDEO_ITAG, "vxtags"))
                .message(2, formatIdWithXtags(SMOKE_AUDIO_ITAG, "axtags"))
                .message(4, proto().message(1, formatIdWithXtags(399, "wv")).bytes())
                .message(5, proto().message(1, formatIdWithXtags(251, "wa")).bytes())
                .u64(9, 1)
                .bytes();
    }

    private static byte[] onesieHeader(final int type,
                                       final long sequence,
                                       final boolean encrypted) {
        final Proto crypto = proto().u64(6, 0);
        if (encrypted) {
            crypto.message(4, new byte[]{1, 2, 3});
            crypto.message(5, new byte[]{4, 5});
        }
        return proto()
                .u64(1, type)
                .string(2, "smoke-video")
                .string(3, String.valueOf(SMOKE_VIDEO_ITAG))
                .message(4, crypto.bytes())
                .u64(5, 123456)
                .u64(7, 11)
                .message(11, proto().u64(1, SMOKE_VIDEO_ITAG).bytes())
                .string(15, "onesie-xtags")
                .u64(18, sequence)
                .message(23, proto().string(2, "smoke-video").bytes())
                .message(34, proto().u64(1, SMOKE_AUDIO_ITAG).bytes())
                .bytes();
    }

    private static byte[] onesieInnertubeResponse() {
        return proto()
                .u64(1, 1)
                .u64(2, 200)
                .message(3, proto().string(1, "x-smoke").string(2, "ok").bytes())
                .message(4, new byte[]{1, 2, 3, 4})
                .bytes();
    }

    private static byte[] requestIdentifier(final String token) {
        return proto().string(1, token).bytes();
    }

    private static byte[] snackbar(final int id) {
        return proto().u64(1, id).bytes();
    }

    private static byte[] cancellationPolicy() {
        return proto()
                .u64(1, 1)
                .message(2, proto().u64(1, 2).u64(2, 3).u64(3, 1500).bytes())
                .u64(3, 4)
                .bytes();
    }

    private static byte[] prewarmConnection() {
        return proto()
                .message(1, proto().string(1, "cdn").u64(2, 1).bytes())
                .bytes();
    }

    private static byte[] contextUpdate(final int type,
                                        final byte[] value,
                                        final boolean sendByDefault,
                                        final int writePolicy) {
        return proto()
                .u64(1, type)
                .u64(2, 1)
                .message(3, value)
                .u64(4, sendByDefault ? 1 : 0)
                .u64(5, writePolicy)
                .bytes();
    }

    private static byte[] contextPolicy(final int[] start,
                                        final int[] stop,
                                        final int[] discard) {
        final Proto proto = proto();
        for (final int value : start) {
            proto.u64(1, value);
        }
        for (final int value : stop) {
            proto.u64(2, value);
        }
        for (final int value : discard) {
            proto.u64(3, value);
        }
        return proto.bytes();
    }

    private static byte[] gzip(final byte[] data) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(data);
        }
        return output.toByteArray();
    }

    private static List<Integer> activeContextTypes(final SabrSmokeHarness harness)
            throws Exception {
        final Method getActiveSabrContexts = harness.holder.session.getStreamState().getClass()
                .getDeclaredMethod("getActiveSabrContexts");
        getActiveSabrContexts.setAccessible(true);
        @SuppressWarnings("unchecked") final Collection<Object> contexts =
                (Collection<Object>) getActiveSabrContexts.invoke(
                        harness.holder.session.getStreamState());
        final List<Integer> types = new ArrayList<>();
        for (final Object context : contexts) {
            final Method getType = context.getClass().getDeclaredMethod("getType");
            getType.setAccessible(true);
            types.add((Integer) getType.invoke(context));
        }
        return types;
    }

    private static List<Integer> unsentContextTypes(final SabrSmokeHarness harness)
            throws Exception {
        final Method getUnsentSabrContextTypes =
                harness.holder.session.getStreamState().getClass()
                        .getDeclaredMethod("getUnsentSabrContextTypes");
        getUnsentSabrContextTypes.setAccessible(true);
        @SuppressWarnings("unchecked") final Collection<Integer> types =
                (Collection<Integer>) getUnsentSabrContextTypes.invoke(
                        harness.holder.session.getStreamState());
        return new ArrayList<>(types);
    }

    private static Proto proto() {
        return new Proto();
    }

    private static String base64(final byte[] bytes) {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    private static byte[] formatIdWithXtags(final int itag, final String xtags) {
        return proto()
                .u64(1, itag)
                .u64(2, 123456)
                .string(3, xtags)
                .bytes();
    }

    private static final class SmokeCase {
        private enum Kind {
            PLAYBACK,
            MISSING_INITIALIZATION,
            EVICTED_REWIND,
            STALLED_READER,
            REWIND_STATE,
            SPONSOR_BLOCK_PLAYBACK,
            SPONSOR_BLOCK_SEEK
        }

        private final Kind kind;

        private SmokeCase(final Kind kind) {
            this.kind = kind;
        }

        private static SmokeCase playback() {
            return new SmokeCase(Kind.PLAYBACK);
        }

        private static SmokeCase missingInitialization() {
            return new SmokeCase(Kind.MISSING_INITIALIZATION);
        }

        private static SmokeCase evictedRewind() {
            return new SmokeCase(Kind.EVICTED_REWIND);
        }

        private static SmokeCase stalledReader() {
            return new SmokeCase(Kind.STALLED_READER);
        }

        private static SmokeCase rewindState() {
            return new SmokeCase(Kind.REWIND_STATE);
        }

        private static SmokeCase sponsorBlockPlayback() {
            return new SmokeCase(Kind.SPONSOR_BLOCK_PLAYBACK);
        }

        private static SmokeCase sponsorBlockSeek() {
            return new SmokeCase(Kind.SPONSOR_BLOCK_SEEK);
        }

        private boolean isSponsorBlockCase() {
            return kind == Kind.SPONSOR_BLOCK_PLAYBACK || kind == Kind.SPONSOR_BLOCK_SEEK;
        }
    }

    private static final class SabrSmokeHarness implements AutoCloseable {
        private final Downloader previousDownloader;
        private final Localization previousLocalization;
        private final ContentCountry previousContentCountry;
        private final FakeSabrDownloader downloader;
        private final SabrSessionStore.Holder holder;
        private final YoutubeSabrFormat videoFormat;
        private final Object readerOwner;

        private SabrSmokeHarness(final Downloader previousDownloader,
                                 final Localization previousLocalization,
                                 final ContentCountry previousContentCountry,
                                 final FakeSabrDownloader downloader,
                                 final SabrSessionStore.Holder holder,
                                 final YoutubeSabrFormat videoFormat,
                                 final Object readerOwner) {
            this.previousDownloader = previousDownloader;
            this.previousLocalization = previousLocalization;
            this.previousContentCountry = previousContentCountry;
            this.downloader = downloader;
            this.holder = holder;
            this.videoFormat = videoFormat;
            this.readerOwner = readerOwner;
        }

        private static SabrSmokeHarness create() throws Exception {
            return create(smokeFormat(SMOKE_AUDIO_ITAG, true),
                    smokeFormat(SMOKE_VIDEO_ITAG, false));
        }

        private static SabrSmokeHarness create(final YoutubeSabrFormat audioFormat,
                                               final YoutubeSabrFormat videoFormat)
                throws Exception {
            final Downloader previousDownloader = NewPipe.getDownloader();
            final Localization previousLocalization = NewPipe.getPreferredLocalization();
            final ContentCountry previousContentCountry = NewPipe.getPreferredContentCountry();
            final FakeSabrDownloader downloader = new FakeSabrDownloader();
            NewPipe.init(downloader, Localization.DEFAULT, ContentCountry.DEFAULT);
            final YoutubeSabrInfo info = smokeInfo(audioFormat, videoFormat);
            final File spoolDirectory = new File(
                    InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                    "sabr-smoke-" + System.nanoTime());
            final YoutubeSabrSession session =
                    new YoutubeSabrSession(info, audioFormat, videoFormat, null, spoolDirectory);
            session.getStreamState().setVideoOnlyRequestMode();
            final Constructor<SabrSessionStore.Holder> constructor =
                    SabrSessionStore.Holder.class.getDeclaredConstructor(Context.class,
                            String.class, YoutubeSabrInfo.class, YoutubeSabrSession.class,
                            YoutubeSabrFormat.class, YoutubeSabrFormat.class);
            constructor.setAccessible(true);
            final SabrSessionStore.Holder holder = constructor.newInstance(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    "smoke-video", info, session, audioFormat, videoFormat);
            final Object readerOwner = new Object();
            final Method setActiveTracks = SabrSessionStore.Holder.class.getDeclaredMethod(
                    "setActiveTracks", Object.class, boolean.class, boolean.class);
            setActiveTracks.setAccessible(true);
            setActiveTracks.invoke(holder, readerOwner, true, false);
            return new SabrSmokeHarness(previousDownloader, previousLocalization,
                    previousContentCountry, downloader, holder, videoFormat, readerOwner);
        }

        private void setPlayerTimeMs(final long playerTimeMs) throws Exception {
            final Method setPlayerTimeMs = SabrSessionStore.Holder.class.getDeclaredMethod(
                    "setPlayerTimeMs", long.class);
            setPlayerTimeMs.setAccessible(true);
            setPlayerTimeMs.invoke(holder, playerTimeMs);
        }

        private void advanceReaderGeneration() throws Exception {
            final Method advanceReaderGeneration = SabrSessionStore.Holder.class
                    .getDeclaredMethod("advanceReaderGeneration", Object.class);
            advanceReaderGeneration.setAccessible(true);
            advanceReaderGeneration.invoke(holder, readerOwner);
        }

        private long openMediaSegment(final SabrSegmentRequest request,
                                      final long timeoutMs) throws Exception {
            return openSegment(request, timeoutMs);
        }

        private long openSegment(final SabrSegmentRequest request,
                                 final long timeoutMs) throws Exception {
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final long startMs = System.currentTimeMillis();
            final Thread thread = new Thread(() -> {
                final SabrSegmentDataSource dataSource = new SabrSegmentDataSource(
                        holder, readerOwner, request.getFormat(), new Localization("en", "US"),
                        false);
                try {
                    dataSource.open(new DataSpec(segmentUri(request)));
                    final byte[] buffer = new byte[8_192];
                    while (dataSource.read(buffer, 0, buffer.length) != C.RESULT_END_OF_INPUT) {
                        // Drain the DataSource: growing segments may return from open at the header.
                    }
                } catch (final Throwable e) {
                    failure.set(e);
                } finally {
                    dataSource.close();
                    done.countDown();
                }
            }, "SabrSmokeDemandOpen");
            thread.start();
            assertTrue("SABR smoke demand open timed out, trace="
                            + holder.session.getDiagnosticTrace(),
                    done.await(timeoutMs, TimeUnit.MILLISECONDS));
            if (failure.get() != null) {
                throw new AssertionError("SABR smoke demand open failed, trace="
                        + holder.session.getDiagnosticTrace(), failure.get());
            }
            return System.currentTimeMillis() - startMs;
        }

        private Uri segmentUri(final SabrSegmentRequest request) {
            return Uri.parse("sabr://" + request.getFormat().getItag() + '/'
                    + (request.isInitializationSegment()
                    ? "init" : String.valueOf(request.getSequenceNumber())));
        }

        private void openMediaSegmentExpectFailure(final SabrSegmentRequest request,
                                                   final long timeoutMs) throws Exception {
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final Thread thread = new Thread(() -> {
                final SabrSegmentDataSource dataSource = new SabrSegmentDataSource(
                        holder, readerOwner, request.getFormat(), new Localization("en", "US"),
                        false);
                try {
                    dataSource.open(new DataSpec(Uri.parse("sabr://"
                            + request.getFormat().getItag() + '/'
                            + request.getSequenceNumber())));
                } catch (final Throwable e) {
                    failure.set(e);
                } finally {
                    dataSource.close();
                    done.countDown();
                }
            }, "SabrSmokeDemandOpenFailure");
            thread.start();
            assertTrue("SABR smoke demand failure did not complete, trace="
                            + holder.session.getDiagnosticTrace(),
                    done.await(timeoutMs, TimeUnit.MILLISECONDS));
            assertNotNull("SABR smoke demand unexpectedly succeeded, trace="
                    + holder.session.getDiagnosticTrace(), failure.get());
        }

        @Override
        public void close() throws Exception {
            final Method stop = SabrSessionStore.Holder.class.getDeclaredMethod(
                    "stop", String.class);
            stop.setAccessible(true);
            stop.invoke(holder, "smoke_harness_close");
            NewPipe.init(previousDownloader, previousLocalization, previousContentCountry);
        }
    }

    private static final class FakeSabrDownloader extends Downloader {
        private final LinkedBlockingQueue<QueuedStreamingBody> responses =
                new LinkedBlockingQueue<>();
        private final List<String> requestedUrls = new ArrayList<>();
        private final List<byte[]> requestBodies = new ArrayList<>();
        private final List<Long> requestTimesMs = Collections.synchronizedList(new ArrayList<>());
        private final List<Long> streamingTimeoutsMs =
                Collections.synchronizedList(new ArrayList<>());
        private final Map<String, byte[]> streamingGetBodies = new ConcurrentHashMap<>();
        private final Map<String, Integer> streamingGetCodes = new ConcurrentHashMap<>();

        private void enqueue(final byte[] body) {
            responses.add(() -> new ByteArrayInputStream(body));
        }

        private void enqueue(final QueuedStreamingBody body) {
            responses.add(body);
        }

        private void enqueueGet(final String url, final int responseCode, final byte[] body) {
            streamingGetCodes.put(url, responseCode);
            streamingGetBodies.put(url, body.clone());
        }

        private List<Long> requestTimesSnapshot() {
            synchronized (requestTimesMs) {
                return new ArrayList<>(requestTimesMs);
            }
        }

        @Override
        public Response execute(final Request request) throws IOException {
            requestedUrls.add(request.url());
            throw new IOException("Unexpected buffered request in SABR smoke: "
                    + request.httpMethod() + " " + request.url());
        }

        @Override
        public StreamingResponse getStreaming(final String url,
                                              final Map<String, List<String>> headers,
                                              final Localization localization,
                                              final long timeoutMs)
                throws IOException, ReCaptchaException {
            streamingTimeoutsMs.add(timeoutMs);
            requestedUrls.add(url);
            final byte[] body = streamingGetBodies.get(url);
            final Integer responseCode = streamingGetCodes.get(url);
            if (body == null || responseCode == null) {
                throw new IOException("No queued SABR smoke GET response for " + url);
            }
            return new StreamingResponse(responseCode, Collections.emptyMap(),
                    new ByteArrayInputStream(body));
        }

        @Override
        public StreamingResponse postStreaming(final String url,
                                               final Map<String, List<String>> headers,
                                               final byte[] dataToSend,
                                               final Localization localization)
                throws IOException {
            requestedUrls.add(url);
            requestBodies.add(dataToSend.clone());
            requestTimesMs.add(System.currentTimeMillis());
            final QueuedStreamingBody body = responses.poll();
            if (body == null) {
                throw new IOException("No queued SABR smoke response for " + url);
            }
            final Map<String, List<String>> responseHeaders = new HashMap<>();
            responseHeaders.put("Content-Type",
                    Collections.singletonList("application/vnd.yt-ump"));
            return new StreamingResponse(200, responseHeaders, body.open());
        }

        @Override
        public CancellableCall executeAsync(final Request request,
                                            final AsyncCallback callback)
                throws IOException, ReCaptchaException {
            throw new IOException("Unexpected async request in SABR smoke: "
                    + request.httpMethod() + " " + request.url());
        }
    }

    @FunctionalInterface
    private interface QueuedStreamingBody {
        InputStream open() throws IOException;
    }

    private static final class GatedMediaResponse implements QueuedStreamingBody {
        private final byte[] prefix;
        private final byte[] suffix;
        private final IOException failureAfterGate;
        private final CountDownLatch gateReached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private GatedMediaResponse(final int headerId,
                                   final int itag,
                                   final int sequence,
                                   final long startMs,
                                   final long durationMs,
                                   final byte[] firstMediaBytes,
                                   final byte[] remainingMediaBytes,
                                   final int compressionAlgorithm,
                                   final boolean initialization,
                                   final IOException failureAfterGate) {
            final byte[] mediaHeader = new UmpFixture()
                    .mediaHeader(headerId, itag, sequence, startMs, durationMs,
                            firstMediaBytes.length + remainingMediaBytes.length,
                            compressionAlgorithm, initialization)
                    .bytes();
            this.prefix = concatBytes(mediaHeader,
                    umpPartPrefix(SabrResponseDecoder.MEDIA,
                            firstMediaBytes.length + remainingMediaBytes.length + 1),
                    new byte[]{(byte) headerId}, firstMediaBytes);
            this.suffix = concatBytes(remainingMediaBytes,
                    new UmpFixture().mediaEnd(headerId).bytes());
            this.failureAfterGate = failureAfterGate;
        }

        private boolean awaitGate(final long timeoutMs) throws InterruptedException {
            return gateReached.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        private void release() {
            released.countDown();
        }

        @Override
        public InputStream open() {
            return new InputStream() {
                private int prefixOffset;
                private int suffixOffset;

                @Override
                public int read() throws IOException {
                    final byte[] one = new byte[1];
                    final int read = read(one, 0, 1);
                    return read < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(final byte[] buffer, final int offset, final int length)
                        throws IOException {
                    if (length == 0) {
                        return 0;
                    }
                    if (prefixOffset < prefix.length) {
                        final int count = Math.min(length, prefix.length - prefixOffset);
                        System.arraycopy(prefix, prefixOffset, buffer, offset, count);
                        prefixOffset += count;
                        if (prefixOffset == prefix.length) {
                            gateReached.countDown();
                        }
                        // Return the available prefix now instead of blocking this read for release.
                        return count;
                    }
                    gateReached.countDown();
                    awaitRelease();
                    if (failureAfterGate != null) {
                        throw failureAfterGate;
                    }
                    if (suffixOffset >= suffix.length) {
                        return -1;
                    }
                    final int count = Math.min(length, suffix.length - suffixOffset);
                    System.arraycopy(suffix, suffixOffset, buffer, offset, count);
                    suffixOffset += count;
                    return count;
                }

                private void awaitRelease() throws InterruptedIOException {
                    try {
                        released.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException(
                                "Interrupted awaiting gated SABR media release");
                    }
                }
            };
        }
    }

    private static final class AsyncSegmentReader {
        private final SabrSessionStore.Holder holder;
        private final Object readerOwner;
        private final SabrSegmentRequest request;
        private final int firstBytesTarget;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<SabrSegmentDataSource> dataSource = new AtomicReference<>();
        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch firstBytesRead = new CountDownLatch(1);
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicBoolean eofObserved = new AtomicBoolean();
        private Thread thread;

        private AsyncSegmentReader(final SabrSessionStore.Holder holder,
                                   final Object readerOwner,
                                   final SabrSegmentRequest request,
                                   final int firstBytesTarget) {
            this.holder = holder;
            this.readerOwner = readerOwner;
            this.request = request;
            this.firstBytesTarget = firstBytesTarget;
        }

        private void start() {
            thread = new Thread(() -> {
                final SabrSegmentDataSource currentDataSource = new SabrSegmentDataSource(
                        holder, readerOwner, request.getFormat(),
                        new Localization("en", "US"), false);
                dataSource.set(currentDataSource);
                try {
                    currentDataSource.open(new DataSpec(Uri.parse("sabr://"
                            + request.getFormat().getItag() + '/'
                            + (request.isInitializationSegment()
                            ? "init" : String.valueOf(request.getSequenceNumber())))));
                    opened.countDown();
                    final byte[] buffer = new byte[64];
                    while (true) {
                        final int read = currentDataSource.read(buffer, 0, buffer.length);
                        if (read == C.RESULT_END_OF_INPUT) {
                            eofObserved.set(true);
                            break;
                        }
                        if (read > 0) {
                            synchronized (output) {
                                output.write(buffer, 0, read);
                                if (output.size() >= firstBytesTarget) {
                                    firstBytesRead.countDown();
                                }
                            }
                        }
                    }
                } catch (final Throwable e) {
                    failure.set(e);
                } finally {
                    currentDataSource.close();
                    dataSource.compareAndSet(currentDataSource, null);
                    done.countDown();
                }
            }, "SabrSmokeAsyncSegmentRead");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean awaitOpened(final long timeoutMs) throws InterruptedException {
            return opened.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        private boolean awaitFirstBytes(final long timeoutMs) throws InterruptedException {
            return firstBytesRead.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        private boolean awaitDone(final long timeoutMs) throws InterruptedException {
            return done.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        private void closeDataSource() {
            final SabrSegmentDataSource current = dataSource.get();
            if (current != null) {
                current.close();
            }
        }

        private byte[] bytesSnapshot() {
            synchronized (output) {
                return output.toByteArray();
            }
        }

        private Throwable getFailure() {
            return failure.get();
        }

        private boolean isEofObserved() {
            return eofObserved.get();
        }
    }

    private static final class GeneratedLargeMediaResponse implements QueuedStreamingBody {
        private final int headerId;
        private final int itag;
        private final int sequence;
        private final long startMs;
        private final long durationMs;
        private final int mediaBytes;

        private GeneratedLargeMediaResponse(final int headerId,
                                            final int itag,
                                            final int sequence,
                                            final long startMs,
                                            final long durationMs,
                                            final int mediaBytes) {
            this.headerId = headerId;
            this.itag = itag;
            this.sequence = sequence;
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.mediaBytes = mediaBytes;
        }

        @Override
        public InputStream open() {
            final byte[] mediaHeader = proto()
                    .u64(1, headerId)
                    .u64(3, itag)
                    .u64(4, 123456)
                    .u64(7, 0)
                    .u64(8, 0)
                    .u64(9, sequence)
                    .u64(11, Math.max(0, startMs))
                    .u64(12, Math.max(0, durationMs))
                    .u64(14, Math.max(0, mediaBytes))
                    .bytes();
            final byte[] headerPartPrefix = umpPartPrefix(
                    SabrResponseDecoder.MEDIA_HEADER, mediaHeader.length);
            final byte[] mediaPartPrefix = umpPartPrefix(
                    SabrResponseDecoder.MEDIA, mediaBytes + 1);
            final byte[] mediaEndPart = new UmpFixture().mediaEnd(headerId).bytes();
            return new GeneratedLargeMediaInputStream(
                    headerPartPrefix, mediaHeader, mediaPartPrefix,
                    (byte) headerId, mediaBytes, mediaEndPart);
        }
    }

    private static final class GeneratedLargeMediaInputStream extends InputStream {
        private final byte[] headerPartPrefix;
        private final byte[] mediaHeader;
        private final byte[] mediaPartPrefix;
        private final byte headerId;
        private final int mediaBytes;
        private final byte[] mediaEndPart;
        private int phase;
        private int offset;
        private int generatedMediaBytes;
        private boolean mediaHeaderIdSent;

        private GeneratedLargeMediaInputStream(final byte[] headerPartPrefix,
                                               final byte[] mediaHeader,
                                               final byte[] mediaPartPrefix,
                                               final byte headerId,
                                               final int mediaBytes,
                                               final byte[] mediaEndPart) {
            this.headerPartPrefix = headerPartPrefix;
            this.mediaHeader = mediaHeader;
            this.mediaPartPrefix = mediaPartPrefix;
            this.headerId = headerId;
            this.mediaBytes = mediaBytes;
            this.mediaEndPart = mediaEndPart;
        }

        @Override
        public int read() {
            final byte[] one = new byte[1];
            final int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(final byte[] buffer, final int off, final int len) {
            if (len <= 0) {
                return 0;
            }
            int written = 0;
            while (written < len) {
                final int value = nextByte();
                if (value < 0) {
                    return written == 0 ? -1 : written;
                }
                buffer[off + written] = (byte) value;
                written++;
            }
            return written;
        }

        private int nextByte() {
            while (true) {
                switch (phase) {
                    case 0:
                        return byteFrom(headerPartPrefix);
                    case 1:
                        return byteFrom(mediaHeader);
                    case 2:
                        return byteFrom(mediaPartPrefix);
                    case 3:
                        if (!mediaHeaderIdSent) {
                            mediaHeaderIdSent = true;
                            return headerId & 0xff;
                        }
                        if (generatedMediaBytes < mediaBytes) {
                            generatedMediaBytes++;
                            return 0;
                        }
                        phase++;
                        offset = 0;
                        break;
                    case 4:
                        return byteFrom(mediaEndPart);
                    default:
                        return -1;
                }
            }
        }

        private int byteFrom(final byte[] bytes) {
            if (offset < bytes.length) {
                return bytes[offset++] & 0xff;
            }
            phase++;
            offset = 0;
            return nextByte();
        }
    }

    private static final class UmpFixture {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private UmpFixture segment(final int headerId, final int itag, final int sequence) {
            return mediaHeader(headerId, itag, sequence).media(headerId).mediaEnd(headerId);
        }

        private UmpFixture initSegment(final int headerId, final int itag) {
            return mediaHeader(headerId, itag, 0, 0, 0, 4, 0, true)
                    .media(headerId)
                    .mediaEnd(headerId);
        }

        private UmpFixture initSegment(final int headerId,
                                       final int itag,
                                       final byte[] payload) {
            return mediaHeader(headerId, itag, 0, 0, 0, payload.length, 0, true)
                    .media(headerId, payload)
                    .mediaEnd(headerId);
        }

        private UmpFixture segment(final int headerId,
                                   final int itag,
                                   final int sequence,
                                   final long startMs,
                                   final long durationMs) {
            return mediaHeader(headerId, itag, sequence, startMs, durationMs)
                    .media(headerId)
                    .mediaEnd(headerId);
        }

        private UmpFixture mediaHeader(final int headerId, final int itag, final int sequence) {
            return mediaHeader(headerId, itag, sequence, (sequence - 1) * 5_000L, 5_000L);
        }

        private UmpFixture mediaHeader(final int headerId,
                                       final int itag,
                                       final int sequence,
                                       final long startMs,
                                       final long durationMs) {
            return mediaHeader(headerId, itag, sequence, startMs, durationMs, 4);
        }

        private UmpFixture mediaHeader(final int headerId,
                                       final int itag,
                                       final int sequence,
                                       final long startMs,
                                       final long durationMs,
                                       final long contentLength) {
            return mediaHeader(headerId, itag, sequence, startMs, durationMs, contentLength, 0);
        }

        private UmpFixture mediaHeader(final int headerId,
                                       final int itag,
                                       final int sequence,
                                       final long startMs,
                                       final long durationMs,
                                       final long contentLength,
                                       final int compressionAlgorithm) {
            return mediaHeader(headerId, itag, sequence, startMs, durationMs, contentLength,
                    compressionAlgorithm, false);
        }

        private UmpFixture mediaHeader(final int headerId,
                                       final int itag,
                                       final int sequence,
                                       final long startMs,
                                       final long durationMs,
                                       final long contentLength,
                                       final int compressionAlgorithm,
                                       final boolean initialization) {
            final Proto header = proto()
                    .u64(1, headerId)
                    .u64(3, itag)
                    .u64(4, 123456)
                    .u64(7, compressionAlgorithm)
                    .u64(8, initialization ? 1 : 0)
                    .u64(9, sequence)
                    .u64(11, Math.max(0, startMs))
                    .u64(12, Math.max(0, durationMs))
                    .u64(14, Math.max(0, contentLength));
            return part(SabrResponseDecoder.MEDIA_HEADER, header.bytes());
        }

        private UmpFixture media(final int headerId) {
            return media(headerId, new byte[]{10, 11, 12, 13});
        }

        private UmpFixture media(final int headerId, final byte[] payload) {
            final byte[] part = new byte[payload.length + 1];
            part[0] = (byte) headerId;
            System.arraycopy(payload, 0, part, 1, payload.length);
            return part(SabrResponseDecoder.MEDIA, part);
        }

        private UmpFixture mediaEnd(final int headerId) {
            return part(SabrResponseDecoder.MEDIA_END, new byte[]{(byte) headerId});
        }

        private UmpFixture part(final int type, final byte[] payload) {
            writeVarint(output, type);
            writeVarint(output, payload.length);
            output.write(payload, 0, payload.length);
            return this;
        }

        private byte[] bytes() {
            return output.toByteArray();
        }
    }

    private static final class Proto {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private Proto u64(final int field, final long value) {
            writeVarint(output, ((long) field << 3) | PROTO_WIRE_VARINT);
            writeVarint(output, value);
            return this;
        }

        private Proto string(final int field, final String value) {
            return message(field, value.getBytes(StandardCharsets.UTF_8));
        }

        private Proto message(final int field, final byte[] value) {
            writeVarint(output, ((long) field << 3) | PROTO_WIRE_LENGTH_DELIMITED);
            writeVarint(output, value.length);
            output.write(value, 0, value.length);
            return this;
        }

        private Proto packedU64(final int field, final long... values) {
            final ByteArrayOutputStream packed = new ByteArrayOutputStream();
            for (final long value : values) {
                writeVarint(packed, value);
            }
            return message(field, packed.toByteArray());
        }

        private byte[] bytes() {
            return output.toByteArray();
        }
    }

    private static void writeVarint(final ByteArrayOutputStream output, final long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            output.write((int) ((remaining & 0x7f) | 0x80));
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }

    private static byte[] umpPartPrefix(final int type, final int size) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeUmpInt(output, type);
        writeUmpInt(output, size);
        return output.toByteArray();
    }

    private static byte[] concatBytes(final byte[]... values) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (final byte[] value : values) {
            output.write(value, 0, value.length);
        }
        return output.toByteArray();
    }

    private static byte[] filledBytes(final int length, final int seed) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }

    private static void writeUmpInt(final ByteArrayOutputStream output, final int value) {
        if (value < 0) {
            throw new IllegalArgumentException("UMP integer must be non-negative");
        }
        if (value < 128) {
            output.write(value);
            return;
        }
        output.write(240);
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static final class BoundedQualityResolver implements QualityResolver {
        private final int maxHeight;
        private final String targetCodec;

        private BoundedQualityResolver(final int maxHeight) {
            this(maxHeight, "");
        }

        private BoundedQualityResolver(final int maxHeight, final String targetCodec) {
            this.maxHeight = maxHeight;
            this.targetCodec = targetCodec == null ? "" : targetCodec.toLowerCase(Locale.ROOT);
        }

        @Override
        public int getDefaultResolutionIndex(final List<VideoStream> sortedVideos) {
            int lowestIndex = -1;
            int lowestHeight = Integer.MAX_VALUE;
            int preferredIndex = -1;
            int preferredHeight = -1;
            for (int i = 0; i < sortedVideos.size(); i++) {
                final VideoStream stream = sortedVideos.get(i);
                if (!isSabr(stream)) {
                    continue;
                }
                final String codec = stream.getCodec() == null
                        ? "" : stream.getCodec().toLowerCase(Locale.ROOT);
                if (!targetCodec.isEmpty() && !codec.isEmpty() && !codec.contains(targetCodec)) {
                    continue;
                }
                final int height = stream.getHeight();
                if (height > 0 && height < lowestHeight) {
                    lowestHeight = height;
                    lowestIndex = i;
                }
                if (height > preferredHeight && height <= maxHeight) {
                    preferredHeight = height;
                    preferredIndex = i;
                }
            }
            if (lowestIndex < 0) {
                throw new AssertionError("Resolver has no selectable SABR video stream");
            }
            return preferredIndex >= 0 ? preferredIndex : lowestIndex;
        }

        @Override
        public int getOverrideResolutionIndex(final List<VideoStream> sortedVideos,
                                              final int selectedIndex) {
            return selectedIndex;
        }

        @Override
        public int getCurrentAudioQualityIndex(final List<AudioStream> audioStreams) {
            return 0;
        }
    }
}
