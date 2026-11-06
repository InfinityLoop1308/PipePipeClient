package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.PlayerService.PlayerType;
import org.schabi.newpipe.player.mediasession.PlayerServiceInterface;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.SerializedCache;

import java.lang.reflect.Proxy;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class PlayerEnqueueModeTest {
    @Test
    public void enqueueAndEnqueueNextPreserveEveryActivePlayerMode() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (final PlayerType activeType : PlayerType.values()) {
                checkEnqueue(context, activeType, false);
                checkEnqueue(context, activeType, true);
            }
        });
    }

    private static void checkEnqueue(final Context context, final PlayerType activeType,
                                     final boolean next) {
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
        final PlayQueue queue = new SinglePlayQueue(
                Arrays.asList(item("first"), item("second"), item("third")), 0);
        queue.init();
        try {
            player.setPlayQueue(queue);
            player.setPlayerType(activeType);
            player.setAudioOnly(activeType == PlayerType.AUDIO);
            final PlayerType requested = PlayerType.values()[
                    (activeType.ordinal() + 1) % PlayerType.values().length];
            final Intent intent = new Intent()
                    .putExtra(PlayerIntentConstants.PLAY_QUEUE_KEY,
                            SerializedCache.getInstance().put(
                                    new SinglePlayQueue(item("queued")), PlayQueue.class))
                    .putExtra(PlayerIntentConstants.PLAYER_TYPE, requested.ordinal())
                    .putExtra(next ? PlayerIntentConstants.ENQUEUE_NEXT
                            : PlayerIntentConstants.ENQUEUE, true);

            new PlayerStartController(player).handleIntent(intent);

            assertSame(queue, player.getPlayQueue());
            assertEquals(activeType, player.getPlayerType());
            assertEquals(activeType == PlayerType.AUDIO, player.isAudioOnly());
            assertEquals(0, queue.getIndex());
            assertEquals(4, queue.size());
            assertEquals("https://example.invalid/queued",
                    queue.getItem(next ? 1 : 3).getUrl());
        } finally {
            try {
                player.destroy();
            } finally {
                queue.dispose();
                player.getTrackSelector().release();
            }
        }
    }

    private static StreamInfoItem item(final String name) {
        final StreamInfoItem item = new StreamInfoItem(0,
                "https://example.invalid/" + name, name, StreamType.VIDEO_STREAM);
        item.setUploaderName("Uploader");
        return item;
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
