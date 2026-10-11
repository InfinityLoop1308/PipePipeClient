package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.session.MediaControllerCompat;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.helper.MediaSessionManager;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.mediasession.PlayerServiceInterface;
import org.schabi.newpipe.player.playback.PlayerMediaSession;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class PlayerMediaSessionTest {
    private Context context;
    private SharedPreferences preferences;
    private final Map<String, Object> savedPreferences = new HashMap<>();
    private String seekKey;
    private Player player;
    private ExoPlayer exoPlayer;
    private PlayQueue queue;
    private PlayerMediaSession callback;
    private MediaSessionManager manager;
    private MediaControllerCompat controller;
    private File fixture;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        seekKey = context.getString(R.string.seek_on_media_button_events_key);
        for (final int keyResource : new int[]{R.string.seek_on_media_button_events_key,
                R.string.seek_duration_key, R.string.pref_sponsorblock_mode_key,
                R.string.sponsor_block_graced_rewind_key}) {
            final String key = context.getString(keyResource);
            savedPreferences.put(key, preferences.getAll().get(key));
        }
        assertTrue(preferences.edit().remove(seekKey)
                .putString(context.getString(R.string.seek_duration_key), "15000")
                .putBoolean(context.getString(R.string.sponsor_block_graced_rewind_key), false)
                .commit());
        fixture = createSilentWav();
        final CountDownLatch ready = new CountDownLatch(1);
        onMain(() -> {
            final TestService service = new TestService(context);
            final PlayerServiceInterface serviceInterface = (PlayerServiceInterface)
                    Proxy.newProxyInstance(PlayerServiceInterface.class.getClassLoader(),
                            new Class<?>[]{PlayerServiceInterface.class},
                            (proxy, method, args) -> {
                                if ("getInstance".equals(method.getName())) {
                                    return service;
                                }
                                throw new AssertionError("Unexpected service call: " + method);
                            });
            player = new Player(serviceInterface);
            exoPlayer = new ExoPlayer.Builder(context).build();
            player.simpleExoPlayer = exoPlayer;
            queue = new SinglePlayQueue(Arrays.asList(localItem("Previous audio"),
                    localItem("Current audio"), localItem("Next audio")), 1);
            queue.init();
            // Avoid the network-backed queue resolver and UI setup; exercise the real
            // Player actions against a local ExoPlayer timeline and a real play queue.
            setField("playQueue", queue);
            callback = new PlayerMediaSession(player, exoPlayer);
            manager = new MediaSessionManager(context, exoPlayer, callback);
            controller = new MediaControllerCompat(context, manager.getSessionToken());
            exoPlayer.addListener(new androidx.media3.common.Player.Listener() {
                @Override
                public void onPlaybackStateChanged(final int state) {
                    if (state == androidx.media3.common.Player.STATE_READY) {
                        ready.countDown();
                    }
                }
            });
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)), 45000);
            exoPlayer.prepare();
        });
        assertTrue("Local audio did not become ready", ready.await(10, TimeUnit.SECONDS));
    }

    @After
    public void tearDown() {
        try {
            onMain(() -> {
                if (manager != null) {
                    manager.dispose();
                }
                if (player != null) {
                    // UI initialization normally installs the observer destroy() expects.
                    setField("settingsContentObserver",
                            new ContentObserver(new Handler(Looper.getMainLooper())) { });
                    preferences.unregisterOnSharedPreferenceChangeListener(
                            (SharedPreferences.OnSharedPreferenceChangeListener)
                                    getField("preferenceChangeListener"));
                    player.destroy();
                    ((DefaultTrackSelector) getField("trackSelector")).release();
                } else if (exoPlayer != null) {
                    exoPlayer.release();
                }
            });
        } finally {
            final SharedPreferences.Editor editor = preferences.edit();
            for (final Map.Entry<String, Object> entry : savedPreferences.entrySet()) {
                if (entry.getValue() == null) {
                    editor.remove(entry.getKey());
                } else if (entry.getValue() instanceof Boolean) {
                    editor.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                } else {
                    editor.putString(entry.getKey(), (String) entry.getValue());
                }
            }
            assertTrue(editor.commit());
            if (fixture != null) {
                assertTrue(fixture.delete());
            }
        }
    }

    @Test
    public void missingPreferenceRetainsNavigation() {
        assertFalse(PlayerHelper.getSeekInsteadOfSkip(context));
        assertNavigation(false);
    }

    @Test
    public void disabledPreferenceRetainsNavigation() {
        setSeeking(false);
        assertFalse(PlayerHelper.getSeekInsteadOfSkip(context));
        assertNavigation(false);
    }

    @Test
    public void enabledPreferenceSeeksByConfiguredDurationAndRetainsQueueItem() {
        setSeeking(true);
        assertTrue(PlayerHelper.getSeekInsteadOfSkip(context));
        onMain(callback::playPrevious);
        awaitState(30000, 1);
        onMain(callback::playNext);
        awaitState(45000, 1);
    }

    @Test
    public void controllerCommandsObserveLivePreferenceChanges() {
        // Use the same controller, session, callback and player throughout.
        assertNavigation(true);
        setSeeking(false);
        assertNavigation(true);
        setSeeking(true);
        resetPlayback(45000);
        controller.getTransportControls().skipToPrevious();
        awaitState(30000, 1);
        controller.getTransportControls().skipToNext();
        awaitState(45000, 1);
        setSeeking(false);
        assertNavigation(true);
    }

    @Test
    public void controllerSeekingClampsAtPlaybackBoundaries() {
        setSeeking(true);
        resetPlayback(5000);
        controller.getTransportControls().skipToPrevious();
        awaitState(0, 1);
        resetPlayback(85000);
        controller.getTransportControls().skipToNext();
        awaitState(90000, 1);
    }

    @Test
    public void notificationBroadcastsRetainNavigationWhenSeekingIsEnabled() {
        setSeeking(true);
        resetPlayback(1000);
        onMain(() -> receiver().onReceive(context,
                new Intent(PlayerService.ACTION_PLAY_PREVIOUS)));
        awaitState(1000, 0);
        resetPlayback(1000);
        onMain(() -> receiver().onReceive(context,
                new Intent(PlayerService.ACTION_PLAY_NEXT)));
        awaitState(1000, 2);
    }

    private void assertNavigation(final boolean viaController) {
        resetPlayback(1000);
        if (viaController) {
            controller.getTransportControls().skipToPrevious();
        } else {
            onMain(callback::playPrevious);
        }
        awaitState(1000, 0);
        resetPlayback(1000);
        if (viaController) {
            controller.getTransportControls().skipToNext();
        } else {
            onMain(callback::playNext);
        }
        awaitState(1000, 2);
        // Preserve Previous's existing restart-current-track behavior too.
        resetPlayback(45000);
        if (viaController) {
            controller.getTransportControls().skipToPrevious();
        } else {
            onMain(callback::playPrevious);
        }
        awaitState(0, 1);
    }

    private void resetPlayback(final long position) {
        onMain(() -> {
            queue.setIndex(1);
            exoPlayer.seekTo(position);
        });
        awaitState(position, 1);
    }

    private void awaitState(final long position, final int queueIndex) {
        final long deadline = SystemClock.elapsedRealtime() + 5000;
        final long[] observed = new long[2];
        do {
            onMain(() -> {
                observed[0] = exoPlayer.getCurrentPosition();
                observed[1] = queue.getIndex();
                assertFalse(exoPlayer.getPlayWhenReady());
                assertEquals(0, exoPlayer.getCurrentMediaItemIndex());
                assertSame(queue, player.getPlayQueue());
            });
            if (observed[0] == position && observed[1] == queueIndex) {
                return;
            }
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertEquals("Playback position", position, observed[0]);
        assertEquals("Queue index", queueIndex, observed[1]);
    }

    private void setSeeking(final boolean enabled) {
        assertTrue(preferences.edit().putBoolean(seekKey, enabled).commit());
    }

    private BroadcastReceiver receiver() {
        return (BroadcastReceiver) getField("broadcastReceiver");
    }

    private static void onMain(final Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private Object getField(final String name) {
        try {
            final Field field = Player.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(player);
        } catch (final ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private void setField(final String name, final Object value) {
        try {
            final Field field = Player.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(player, value);
        } catch (final ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private StreamInfoItem localItem(final String title) {
        return new StreamInfoItem(0, fixture.toURI().toString(), title, StreamType.AUDIO_STREAM);
    }

    private File createSilentWav() throws Exception {
        final File audio = File.createTempFile("media-button-seek", ".wav", context.getCacheDir());
        final int dataSize = 90 * 8000 * 2;
        final ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x46464952).putInt(36 + dataSize).putInt(0x45564157)
                .putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .putInt(0x61746164).putInt(dataSize);
        try (FileOutputStream output = new FileOutputStream(audio)) {
            output.write(header.array());
            output.write(new byte[dataSize]);
        }
        return audio;
    }

    private static final class TestService extends Service {
        TestService(final Context context) {
            attachBaseContext(context);
        }

        @Override
        public IBinder onBind(final Intent intent) {
            return null;
        }
    }
}
