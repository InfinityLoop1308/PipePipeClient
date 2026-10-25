package org.schabi.newpipe.player;

import static org.junit.Assert.assertNull;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.player.mediasession.PlayerServiceInterface;

import java.lang.reflect.Proxy;

@RunWith(AndroidJUnit4.class)
public final class ChatPanelCleanupTest {
    @Test
    public void cleanupBeforeViewsIsSafeAndIdempotent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            final Service service = new LocalService(context);
            final PlayerServiceInterface serviceInterface = (PlayerServiceInterface)
                    Proxy.newProxyInstance(PlayerServiceInterface.class.getClassLoader(),
                            new Class<?>[]{PlayerServiceInterface.class}, (proxy, method, args) -> {
                                if ("getInstance".equals(method.getName())) {
                                    return service;
                                }
                                if ("isLandscape".equals(method.getName())) {
                                    return false;
                                }
                                throw new AssertionError("Unexpected service call: " + method);
                            });
            final Player player = new Player(serviceInterface);
            try {
                assertNull(player.getBinding());
                final ChatPanelController panel = player.getBulletCommentsController().getChatPanel();
                panel.release();
                panel.release();
            } finally {
                try {
                    player.destroy();
                } finally {
                    player.getTrackSelector().release();
                }
            }
        });
    }

    private static final class LocalService extends Service {
        LocalService(final Context context) {
            attachBaseContext(context);
        }

        @Override
        public IBinder onBind(final Intent intent) {
            return null;
        }
    }
}
