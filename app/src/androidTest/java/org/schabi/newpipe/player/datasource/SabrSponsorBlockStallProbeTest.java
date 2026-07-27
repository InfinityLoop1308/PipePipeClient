package org.schabi.newpipe.player.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in ownership regressions for SABR sources, periods, and session leases. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class SabrSponsorBlockStallProbeTest {
    private static final int AUDIO_ITAG = 251;
    private static final int VIDEO_ITAG = 137;
    private static final byte[] AUDIO_INIT = mp4Sidx(20_001, 20_000, 19_999);
    private static final byte[] VIDEO_INIT = mp4Sidx(5_000, 5_000, 5_000, 5_000);

    @Test
    public void discardedSourcesDoNotCreateSessions() throws Exception {
        assumeProbeEnabled();
        final Context context = context();
        final String videoId = "discarded-source-probe";
        final SabrSourceSpec spec = spec(videoId);

        for (int i = 0; i < 100; i++) {
            final SabrDashMediaSource source = new SabrDashMediaSource(context,
                    mediaItem(videoId + '-' + i), spec);
            source.releaseSourceInternal();
        }

        assertEquals("Constructing and discarding lightweight sources created a session",
                0, sessionCount(videoId));
    }

    @Test
    public void discardedSourceClearsPreparedSessionWithoutCreatingPeriod() throws Exception {
        assumeProbeEnabled();
        final Context context = context();
        final String videoId = "discarded-prepared-source-probe";
        final YoutubeSabrFormat audio = format(AUDIO_ITAG, true);
        final YoutubeSabrFormat video = format(VIDEO_ITAG, false);
        final YoutubeSabrInfo info = info(videoId, audio, video);
        final YoutubeSabrSession session = session(context, videoId, info, audio, video);
        final SabrSourceSpec spec = new SabrSourceSpec(videoId, info, audio, video,
                new Localization("en", "US"), AUDIO_INIT, VIDEO_INIT, session);

        final SabrDashMediaSource source = new SabrDashMediaSource(
                context, mediaItem(videoId), spec);
        source.releaseSourceInternal();

        assertTrue("Discarding a source without a period left its prepared session open",
                sessionCacheClosed(session));
        assertEquals(0, sessionCount(videoId));
    }

    @Test
    public void failedSourceConstructionClearsPreparedSession() throws Exception {
        assumeProbeEnabled();
        final Context context = context();
        final String videoId = "failed-prepared-source-probe";
        final YoutubeSabrFormat audio = format(AUDIO_ITAG, true);
        final YoutubeSabrFormat video = format(VIDEO_ITAG, false);
        final YoutubeSabrInfo info = info(videoId, audio, video);
        final YoutubeSabrSession session = session(context, videoId, info, audio, video);
        final SabrSourceSpec spec = new SabrSourceSpec(videoId, info, audio, video,
                new Localization("en", "US"), new byte[0], new byte[0], session);

        boolean failed = false;
        try {
            new SabrDashMediaSource(context, mediaItem(videoId), spec);
        } catch (final IOException expected) {
            failed = true;
        }

        assertTrue("Invalid initialization data unexpectedly created a SABR source", failed);
        assertTrue("Failed source construction left its prepared session open",
                sessionCacheClosed(session));
    }

    @Test
    public void concurrentLoadersShareOnePeriodLease() throws Exception {
        assumeProbeEnabled();
        final Context context = context();
        final String videoId = "concurrent-loader-probe";
        final SabrSourceSpec spec = spec(videoId);
        final SabrSessionStore.Holder holder = holder(context, spec);
        final SabrSessionHandle handle = new SabrSessionHandle(context, spec);
        final AtomicInteger leaseReferences = leaseReferences(holder);
        final AtomicReference<SabrSessionStore.Holder> first = new AtomicReference<>();
        final AtomicReference<SabrSessionStore.Holder> second = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch start = new CountDownLatch(1);
        final Thread firstLoader = loader(start, handle, first, failure);
        final Thread secondLoader = loader(start, handle, second, failure);

        install(holder);
        handle.onPeriodCreated(0);
        try {
            firstLoader.start();
            secondLoader.start();
            start.countDown();
            firstLoader.join(TimeUnit.SECONDS.toMillis(2));
            secondLoader.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse("The first loader did not finish", firstLoader.isAlive());
            assertFalse("The second loader did not finish", secondLoader.isAlive());
            if (failure.get() != null) {
                throw new AssertionError("A concurrent loader failed", failure.get());
            }
            assertSame(holder, first.get());
            assertSame(holder, second.get());
            assertEquals("Concurrent loaders acquired more than one lease", 1,
                    leaseReferences.get());
        } finally {
            handle.onPeriodReleased();
            SabrSessionStore.evict(videoId);
        }
        assertEquals(0, leaseReferences.get());
        assertTrue("The last period release did not evict its session", holder.isInvalidated());
    }

    @Test
    public void releaseDuringAcquisitionClosesLateLease() throws Exception {
        assumeProbeEnabled();
        final Context context = context();
        final String videoId = "release-during-acquire-probe";
        final SabrSourceSpec spec = spec(videoId);
        final SabrSessionStore.Holder holder = holder(context, spec);
        final SabrSessionHandle handle = new SabrSessionHandle(context, spec);
        final AtomicReference<Throwable> result = new AtomicReference<>();
        final Thread loader = new Thread(() -> {
            try {
                handle.acquireHolder();
            } catch (final Throwable failure) {
                result.set(failure);
            }
        }, "SabrLateLeaseProbe");

        install(holder);
        handle.onPeriodCreated(0);
        synchronized (SabrSessionStore.class) {
            loader.start();
            final long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (loader.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < deadlineNs) {
                Thread.yield();
            }
            assertEquals("The loader did not reach Store acquisition",
                    Thread.State.BLOCKED, loader.getState());
            handle.onPeriodReleased();
        }
        loader.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse("The loader did not finish after Store acquisition resumed", loader.isAlive());
        assertTrue("A released period accepted a late lease", result.get() instanceof IOException);
        assertTrue("The late lease was not closed", holder.isInvalidated());
        assertEquals(0, leaseReferences(holder).get());
    }

    @Test
    public void releasedHandleCanAcquireFreshSessionForNextPeriod() throws Exception {
        assumeProbeEnabled();
        final Context context = context();
        final String videoId = "period-reacquire-probe";
        final SabrSourceSpec spec = spec(videoId);
        final SabrSessionHandle handle = new SabrSessionHandle(context, spec);
        final SabrSessionStore.Holder first = holder(context, spec);
        install(first);

        handle.onPeriodCreated(0);
        assertSame(first, handle.acquireHolder());
        handle.onPeriodReleased();
        assertTrue(first.isInvalidated());

        final SabrSessionStore.Holder second = holder(context, spec);
        install(second);
        handle.onPeriodCreated(30_000);
        try {
            assertSame("A new period reused the invalidated session", second,
                    handle.acquireHolder());
            assertFalse(second.isInvalidated());
        } finally {
            handle.onPeriodReleased();
            SabrSessionStore.evict(videoId);
        }
    }

    @Test
    public void oldLoaderCannotAttachLeaseToNextPeriodGeneration() throws Exception {
        assumeProbeEnabled();
        final Context context = context();
        final String videoId = "cross-generation-acquire-probe";
        final SabrSourceSpec spec = spec(videoId);
        final SabrSessionStore.Holder holder = holder(context, spec);
        final SabrSessionHandle handle = new SabrSessionHandle(context, spec);
        final AtomicReference<Throwable> oldResult = new AtomicReference<>();
        final AtomicReference<SabrSessionStore.Holder> newResult = new AtomicReference<>();
        final AtomicReference<Throwable> newFailure = new AtomicReference<>();
        final Thread oldLoader = new Thread(() -> {
            try {
                handle.acquireHolder();
            } catch (final Throwable failure) {
                oldResult.set(failure);
            }
        }, "SabrOldGenerationProbe");
        final Thread newLoader = new Thread(() -> {
            try {
                newResult.set(handle.acquireHolder());
            } catch (final Throwable failure) {
                newFailure.set(failure);
            }
        }, "SabrNewGenerationProbe");

        install(holder);
        try (SabrSessionStore.Lease guard = SabrSessionStore.acquire(context, spec)) {
            handle.onPeriodCreated(0);
            synchronized (SabrSessionStore.class) {
                oldLoader.start();
                awaitBlocked(oldLoader);
                handle.onPeriodReleased();
                handle.onPeriodCreated(30_000);
                newLoader.start();
                awaitBlocked(newLoader);
            }
            oldLoader.join(TimeUnit.SECONDS.toMillis(2));
            newLoader.join(TimeUnit.SECONDS.toMillis(2));
            assertTrue("The released loader attached to the next period",
                    oldResult.get() instanceof IOException);
            if (newFailure.get() != null) {
                throw new AssertionError("The new period loader failed", newFailure.get());
            }
            assertSame(holder, newResult.get());
        } finally {
            handle.onPeriodReleased();
            SabrSessionStore.evict(videoId);
        }
    }

    @Test
    public void duplicateSourcesOfSameVideoUseIndependentSessions() throws Exception {
        assumeProbeEnabled();
        final Context context = context();
        final String videoId = "composite-session-key-probe";
        final YoutubeSabrFormat audio = format(AUDIO_ITAG, true);
        final YoutubeSabrFormat video = format(VIDEO_ITAG, false);
        final YoutubeSabrInfo info = info(videoId, audio, video);
        final SabrSourceSpec firstSpec = spec(videoId, info, audio, video);
        final SabrSourceSpec secondSpec = spec(videoId, info, audio, video);
        final SabrSessionStore.Holder firstHolder = holder(context, firstSpec);
        final SabrSessionStore.Holder secondHolder = holder(context, secondSpec);
        final SabrSessionHandle firstHandle = new SabrSessionHandle(context, firstSpec);
        final SabrSessionHandle secondHandle = new SabrSessionHandle(context, secondSpec);

        install(firstHolder);
        install(secondHolder);
        firstHandle.onPeriodCreated(0);
        secondHandle.onPeriodCreated(0);
        try {
            assertSame(firstHolder, firstHandle.acquireHolder());
            assertSame(secondHolder, secondHandle.acquireHolder());
            assertEquals("Duplicate sources of the same video shared mutable session state",
                    2, sessionCount(videoId));
            assertFalse(firstHolder.isInvalidated());
            assertFalse(secondHolder.isInvalidated());
        } finally {
            firstHandle.onPeriodReleased();
            secondHandle.onPeriodReleased();
            SabrSessionStore.evict(videoId);
        }
    }

    private static Thread loader(final CountDownLatch start,
                                 final SabrSessionHandle handle,
                                 final AtomicReference<SabrSessionStore.Holder> result,
                                 final AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                assertTrue(start.await(2, TimeUnit.SECONDS));
                result.set(handle.acquireHolder());
            } catch (final Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        }, "SabrConcurrentLeaseProbe");
    }

    private static void awaitBlocked(final Thread thread) {
        final long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadlineNs) {
            Thread.yield();
        }
        assertEquals("The loader did not reach Store acquisition",
                Thread.State.BLOCKED, thread.getState());
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
    }

    private static MediaItem mediaItem(final String videoId) {
        return new MediaItem.Builder().setUri(Uri.parse("sabr://" + videoId)).build();
    }

    private static SabrSourceSpec spec(final String videoId) throws Exception {
        final YoutubeSabrFormat audio = format(AUDIO_ITAG, true);
        final YoutubeSabrFormat video = format(VIDEO_ITAG, false);
        return spec(videoId, info(videoId, audio, video), audio, video);
    }

    private static SabrSourceSpec spec(final String videoId,
                                       final YoutubeSabrInfo info,
                                       final YoutubeSabrFormat audio,
                                       final YoutubeSabrFormat video) {
        return new SabrSourceSpec(videoId, info, audio, video,
                new Localization("en", "US"), AUDIO_INIT, VIDEO_INIT);
    }

    private static SabrSessionStore.Holder holder(final Context context,
                                                   final SabrSourceSpec spec) {
        final YoutubeSabrSession session = session(context, spec.getVideoId(), spec.getInfo(),
                spec.getAudioFormat(), spec.getVideoFormat());
        final SabrSessionStore.Holder holder = new SabrSessionStore.Holder(context, spec, session);
        holder.setInitializationData(AUDIO_ITAG, AUDIO_INIT);
        holder.setInitializationData(VIDEO_ITAG, VIDEO_INIT);
        return holder;
    }

    private static YoutubeSabrSession session(final Context context,
                                              final String videoId,
                                              final YoutubeSabrInfo info,
                                              final YoutubeSabrFormat audio,
                                              final YoutubeSabrFormat video) {
        final File spoolDirectory = new File(context.getCacheDir(),
                "sabr-lease-probe-" + videoId + '-' + System.nanoTime());
        return new YoutubeSabrSession(info, audio, video, null, spoolDirectory);
    }

    private static boolean sessionCacheClosed(final YoutubeSabrSession session) throws Exception {
        final Field field = YoutubeSabrSession.class.getDeclaredField("cacheClosed");
        field.setAccessible(true);
        return field.getBoolean(session);
    }

    private static void install(final SabrSessionStore.Holder holder) throws Exception {
        final Field keyField = SabrSessionStore.Holder.class.getDeclaredField("key");
        final Field sessionsField = SabrSessionStore.class.getDeclaredField("SESSIONS");
        final Field orderField = SabrSessionStore.class.getDeclaredField("ORDER");
        keyField.setAccessible(true);
        sessionsField.setAccessible(true);
        orderField.setAccessible(true);
        final Object key = keyField.get(holder);
        @SuppressWarnings("unchecked") final Map<Object, SabrSessionStore.Holder> sessions =
                (Map<Object, SabrSessionStore.Holder>) sessionsField.get(null);
        @SuppressWarnings("unchecked") final Deque<Object> order =
                (ArrayDeque<Object>) orderField.get(null);
        synchronized (SabrSessionStore.class) {
            sessions.put(key, holder);
            order.remove(key);
            order.addLast(key);
        }
    }

    private static int sessionCount(final String videoId) throws Exception {
        final Field sessionsField = SabrSessionStore.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked") final Map<Object, SabrSessionStore.Holder> sessions =
                (Map<Object, SabrSessionStore.Holder>) sessionsField.get(null);
        int count = 0;
        for (final SabrSessionStore.Holder holder : sessions.values()) {
            if (videoId.equals(holder.videoId)) {
                count++;
            }
        }
        return count;
    }

    private static AtomicInteger leaseReferences(final SabrSessionStore.Holder holder)
            throws Exception {
        final Field field = SabrSessionStore.Holder.class.getDeclaredField("leaseReferences");
        field.setAccessible(true);
        return (AtomicInteger) field.get(holder);
    }

    private static YoutubeSabrFormat format(final int itag, final boolean audio)
            throws Exception {
        final Constructor<YoutubeSabrFormat> constructor =
                YoutubeSabrFormat.class.getDeclaredConstructor(int.class, long.class,
                        String.class, String.class, String.class, String.class, boolean.class,
                        String.class, String.class, boolean.class, int.class, int.class,
                        int.class, long.class, long.class, String.class, long.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(itag, 123456L, null,
                audio ? "audio/mp4" : "video/mp4",
                audio ? "audio-track" : null, audio ? "Original" : null, audio,
                audio ? null : "1080p", audio ? "AUDIO_QUALITY_MEDIUM" : null, false,
                audio ? -1 : 1920, audio ? -1 : 1080,
                audio ? 128_000 : 2_000_000, 100_000L, 300_000L,
                null, -1L, -1L);
    }

    private static byte[] mp4Sidx(final int... durationsMs) {
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(32 + durationsMs.length * 12)
                .order(java.nio.ByteOrder.BIG_ENDIAN);
        buffer.putInt(buffer.capacity());
        buffer.put(new byte[]{'s', 'i', 'd', 'x'});
        buffer.putInt(0);
        buffer.putInt(1);
        buffer.putInt(1_000);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) 0);
        buffer.putShort((short) durationsMs.length);
        for (final int durationMs : durationsMs) {
            buffer.putInt(1);
            buffer.putInt(durationMs);
            buffer.putInt(0);
        }
        return buffer.array();
    }

    private static YoutubeSabrInfo info(final String videoId,
                                        final YoutubeSabrFormat... formats) throws Exception {
        final Constructor<YoutubeSabrInfo> constructor =
                YoutubeSabrInfo.class.getDeclaredConstructor(YoutubeSabrClientProfile.class,
                        String.class, String.class, String.class, String.class, String.class,
                        String.class, java.util.List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(YoutubeSabrClientProfile.MWEB, videoId, "cpn",
                "2.20250122.04.00", "visitor", "https://sabr.test", null,
                Arrays.asList(formats));
    }

    private static void assumeProbeEnabled() {
        assumeTrue("Set runSabrStallProbe=true to run the manual SABR stall probe",
                Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                        .getString("runSabrStallProbe", "false")));
    }
}
