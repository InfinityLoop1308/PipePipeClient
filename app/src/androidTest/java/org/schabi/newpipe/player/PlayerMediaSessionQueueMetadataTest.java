package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.exoplayer2.ExoPlayer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.mediasession.PlayerServiceInterface;
import org.schabi.newpipe.player.playback.PlayerMediaSession;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class PlayerMediaSessionQueueMetadataTest {
    @Test
    public void optionalFieldsDoNotBreakQueueMetadata() {
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
            final ExoPlayer engine = new ExoPlayer.Builder(context).build();
            try {
                final StreamInfoItem missing = item("missing");
                final StreamInfoItem empty = item("empty");
                empty.setThumbnailUrl("");
                final StreamInfoItem present = item("present");
                present.setUploaderName("Known uploader");
                present.setThumbnailUrl("https://example.invalid/thumbnail.jpg");
                final SinglePlayQueue queue = new SinglePlayQueue(
                        Arrays.asList(missing, empty, present), 0);
                queue.init();
                try {
                    final Field field = Player.class.getDeclaredField("playQueue");
                    field.setAccessible(true);
                    field.set(player, queue);
                } catch (final ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
                final PlayerMediaSession session = new PlayerMediaSession(player, engine);
                assertEquals("", queue.getItem(0).getUploader());
                assertEquals("", queue.getItem(1).getUploader());
                assertEquals("Known uploader", queue.getItem(2).getUploader());
                assertNull(session.getQueueMetadata(0).getIconUri());
                assertNull(session.getQueueMetadata(1).getIconUri());
                assertEquals(present.getThumbnailUrl(),
                        session.getQueueMetadata(2).getIconUri().toString());
            } finally {
                try {
                    player.destroy();
                } finally {
                    engine.release();
                    player.getTrackSelector().release();
                }
            }
        });
    }

    private static StreamInfoItem item(final String name) {
        final StreamInfoItem item = new StreamInfoItem(0, "https://example.invalid/" + name,
                name, StreamType.AUDIO_STREAM);
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
