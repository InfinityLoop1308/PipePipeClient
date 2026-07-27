package org.schabi.newpipe.player;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import org.schabi.newpipe.R;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SabrBackoffCoordinatorTest {
    private final Context context = InstrumentationRegistry.getInstrumentation()
            .getTargetContext();
    private final Object owner = new Object();

    @After
    public void tearDown() {
        SabrBackoffCoordinator.getInstance().setPlayerBuffering(context, false);
        SabrBackoffCoordinator.getInstance().clear(context, owner);
    }

    @Test
    public void remainingSecondsRoundsUpUntilDeadline() {
        assertEquals(0, SabrBackoffCoordinator.remainingSeconds(0));
        assertEquals(1, SabrBackoffCoordinator.remainingSeconds(1));
        assertEquals(1, SabrBackoffCoordinator.remainingSeconds(1_000));
        assertEquals(2, SabrBackoffCoordinator.remainingSeconds(1_001));
        assertEquals(8, SabrBackoffCoordinator.remainingSeconds(7_999));
    }

    @Test
    public void bufferingBackoffUsesStandaloneNotificationAndClearsIt() {
        final SabrBackoffCoordinator coordinator = SabrBackoffCoordinator.getInstance();
        coordinator.begin(context, owner, SystemClock.elapsedRealtime() + 5_000L);
        coordinator.setPlayerBuffering(context, true);

        final StatusBarNotification notification = awaitNotification(true);
        assertNotNull(notification);
        assertEquals(SabrBackoffCoordinator.NOTIFICATION_ID, notification.getId());
        assertEquals(context.getString(R.string.sabr_backoff_notification_channel_id),
                notification.getNotification().getChannelId());
        final CharSequence content = notification.getNotification().extras
                .getCharSequence(Notification.EXTRA_TEXT);
        assertNotNull(content);
        assertTrue(content.toString().contains("YouTube"));

        coordinator.setPlayerBuffering(context, false);
        assertNull(awaitNotification(false));
    }

    @Test
    public void playbackWaitBackoffNotifiesBeforeMedia3StartsBuffering() {
        final SabrBackoffCoordinator coordinator = SabrBackoffCoordinator.getInstance();
        coordinator.setPlayerBuffering(context, false);
        coordinator.beginPlaybackWait(
                context, owner, SystemClock.elapsedRealtime() + 5_000L);

        final StatusBarNotification notification = awaitNotification(true);
        assertNotNull(notification);
        assertEquals(SabrBackoffCoordinator.NOTIFICATION_ID, notification.getId());

        coordinator.setPlayerBuffering(context, false);
        assertNotNull("Media3 state updates must not hide an explicit playback wait",
                awaitNotification(true));

        coordinator.clear(context, owner);
        assertNull(awaitNotification(false));
    }

    @Test
    public void bufferingBackoffAppearsInThePlayerOverlay() throws Exception {
        final SabrBackoffCoordinator coordinator = SabrBackoffCoordinator.getInstance();
        coordinator.begin(context, owner, SystemClock.elapsedRealtime() + 5_000L);
        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicReference<Player> playerReference = new AtomicReference<>();
        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(final ComponentName name, final IBinder service) {
                final PlayerBinderInterface binder = (PlayerBinderInterface) service;
                playerReference.set(binder.getPlayer());
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(final ComponentName name) {
            }
        };
        final Intent intent = new Intent(context, PlayerService.class);
        context.startService(intent);
        assertTrue("PlayerService did not connect", context.bindService(intent, connection,
                Context.BIND_AUTO_CREATE));
        try {
            assertTrue("PlayerService connection timed out", connected.await(10, TimeUnit.SECONDS));
            final Player player = playerReference.get();
            assertNotNull(player);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    final Field currentState = Player.class.getDeclaredField("currentState");
                    currentState.setAccessible(true);
                    currentState.setInt(player, Player.STATE_BUFFERING);
                    final Method start = Player.class.getDeclaredMethod(
                            "startSabrBackoffCountdown");
                    start.setAccessible(true);
                    start.invoke(player);
                } catch (final Exception error) {
                    throw new AssertionError(error);
                }
            });
            assertEquals(View.VISIBLE,
                    player.getBinding().sabrBackoffCountdown.getVisibility());
            assertTrue(player.getBinding().sabrBackoffCountdown.getText().toString()
                    .contains("YouTube"));
        } finally {
            context.unbindService(connection);
            context.stopService(intent);
            coordinator.clear(context, owner);
        }
    }

    private StatusBarNotification awaitNotification(final boolean expected) {
        for (int attempt = 0; attempt < 20; attempt++) {
            final StatusBarNotification found = findBackoffNotification();
            if ((found != null) == expected) {
                return found;
            }
            SystemClock.sleep(50L);
        }
        return findBackoffNotification();
    }

    private StatusBarNotification findBackoffNotification() {
        final NotificationManager manager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        for (final StatusBarNotification notification : manager.getActiveNotifications()) {
            if (notification.getId() == SabrBackoffCoordinator.NOTIFICATION_ID) {
                return notification;
            }
        }
        return null;
    }
}
