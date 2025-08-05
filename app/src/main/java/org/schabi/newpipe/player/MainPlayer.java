/*
 * Copyright 2017 Mauricio Colli <mauriciocolli@outlook.com>
 * Part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe.player;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import androidx.media.MediaBrowserServiceCompat;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import org.schabi.newpipe.App;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.player.mediabrowser.MediaBrowserImpl;
import org.schabi.newpipe.player.mediabrowser.MediaBrowserPlaybackPreparer;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.List;
import java.util.function.Consumer;

import static org.schabi.newpipe.util.Localization.assureCorrectAppLanguage;


/**
 * One service for all players.
 *
 * @author mauriciocolli
 */
public final class MainPlayer extends MediaBrowserServiceCompat {
    private static final String TAG = "MainPlayer";
    private static final boolean DEBUG = Player.DEBUG;

    public static final String SHOULD_START_FOREGROUND_EXTRA = "should_start_foreground_extra";
    public static final String BIND_PLAYER_HOLDER_ACTION = "bind_player_holder_action";

    // These objects are used to cleanly separate the Service implementation (in this file) and the
    // media browser and playback preparer implementations. At the moment the playback preparer is
    // only used in conjunction with the media browser.
    private MediaBrowserImpl mediaBrowserImpl;
    private MediaBrowserPlaybackPreparer mediaBrowserPlaybackPreparer;

    // these are instantiated in onCreate() as per
    // https://developer.android.com/training/cars/media#browser_workflow
    private MediaSessionCompat mediaSession;
    private MediaSessionConnector sessionConnector;

    private Player player;
    private WindowManager windowManager;

    private final IBinder mBinder = new MainPlayer.LocalBinder();
    /**
     * The parameter taken by this {@link Consumer} can be null to indicate the player is being
     * stopped.
     */
    @Nullable
    private Consumer<Player> onPlayerStartedOrStopped = null;
    public enum PlayerType {
        VIDEO,
        AUDIO,
        POPUP
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Notification
    //////////////////////////////////////////////////////////////////////////*/

    public static final String ACTION_CLOSE
            = App.PACKAGE_NAME + ".player.MainPlayer.CLOSE";
    public static final String ACTION_PLAY_PAUSE
            = App.PACKAGE_NAME + ".player.MainPlayer.PLAY_PAUSE";
    static final String ACTION_REPEAT
            = App.PACKAGE_NAME + ".player.MainPlayer.REPEAT";
    static final String ACTION_PLAY_NEXT
            = App.PACKAGE_NAME + ".player.MainPlayer.ACTION_PLAY_NEXT";
    static final String ACTION_PLAY_PREVIOUS
            = App.PACKAGE_NAME + ".player.MainPlayer.ACTION_PLAY_PREVIOUS";
    static final String ACTION_FAST_REWIND
            = App.PACKAGE_NAME + ".player.MainPlayer.ACTION_FAST_REWIND";
    static final String ACTION_FAST_FORWARD
            = App.PACKAGE_NAME + ".player.MainPlayer.ACTION_FAST_FORWARD";
    public static final String ACTION_SHUFFLE
            = App.PACKAGE_NAME + ".player.MainPlayer.ACTION_SHUFFLE";
    public static final String ACTION_CHANGE_PLAY_MODE
            = App.PACKAGE_NAME + ".player.MainPlayer.ACTION_CHANGE_PLAY_MODE";
    public static final String ACTION_RECREATE_NOTIFICATION
            = App.PACKAGE_NAME + ".player.MainPlayer.ACTION_RECREATE_NOTIFICATION";

    /*//////////////////////////////////////////////////////////////////////////
    // Service's LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate() {
        super.onCreate();
        if (DEBUG) {
            Log.d(TAG, "onCreate() called");
        }
        assureCorrectAppLanguage(this);
        windowManager = ContextCompat.getSystemService(this, WindowManager.class);

        ThemeHelper.setTheme(this);
        createView();
        mediaBrowserImpl = new MediaBrowserImpl(this, this::notifyChildrenChanged);

        // see https://developer.android.com/training/cars/media#browser_workflow
        mediaSession = new MediaSessionCompat(this, "MediaSessionPlayerServ");
        setSessionToken(mediaSession.getSessionToken());
        sessionConnector = new MediaSessionConnector(mediaSession);
        sessionConnector.setMetadataDeduplicationEnabled(true);

        mediaBrowserPlaybackPreparer = new MediaBrowserPlaybackPreparer(
                this,
                sessionConnector::setCustomErrorMessage,
                () -> sessionConnector.setCustomErrorMessage(null),
                (playWhenReady) -> {
                    if (player != null) {
                        player.onPrepare();
                    }
                }
        );
        sessionConnector.setPlaybackPreparer(mediaBrowserPlaybackPreparer);

        // Note: you might be tempted to create the player instance and call startForeground here,
        // but be aware that the Android system might start the service just to perform media
        // queries. In those cases creating a player instance is a waste of resources, and calling
        // startForeground means creating a useless empty notification. In case it's really needed
        // the player instance can be created here, but startForeground() should definitely not be
        // called here unless the service is actually starting in the foreground, to avoid the
        // useless notification.
    }

    private void createView() {
        final PlayerBinding binding = PlayerBinding.inflate(LayoutInflater.from(this));

        player = new Player(this);
        player.setupFromView(binding);

        NotificationUtil.getInstance().createNotificationAndStartForeground(player, this);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (DEBUG) {
            Log.d(TAG, "onStartCommand() called with: intent = [" + intent
                    + "], flags = [" + flags + "], startId = [" + startId + "]");
        }
        if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())
                && player.getPlayQueue() == null) {
            // Player is not working, no need to process media button's action
            return START_NOT_STICKY;
        }
        // null check
        if (player == null) {
            final PlayerBinding binding = PlayerBinding.inflate(LayoutInflater.from(this));

            player = new Player(this);
            player.setupFromView(binding);
        }

        if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())
                || intent.getStringExtra(Player.PLAY_QUEUE_KEY) != null) {
            NotificationUtil.getInstance().createNotificationAndStartForeground(player, this);
        }
        player.handleIntent(intent);
        if (player.getMediaSessionManager() != null) {
            player.getMediaSessionManager().handleMediaButtonIntent(intent);
        }
        return START_NOT_STICKY;
    }

    public void stopForImmediateReusing() {
        if (DEBUG) {
            Log.d(TAG, "stopForImmediateReusing() called");
        }

        if (!player.exoPlayerIsNull()) {
            player.saveWasPlaying();

            // Releases wifi & cpu, disables keepScreenOn, etc.
            // We can't just pause the player here because it will make transition
            // from one stream to a new stream not smooth
            player.smoothStopPlayer();
            player.setRecovery();

            // Android TV will handle back button in case controls will be visible
            // (one more additional unneeded click while the player is hidden)
            player.hideControls(0, 0);
            player.closeItemsList();

            // Notification shows information about old stream but if a user selects
            // a stream from backStack it's not actual anymore
            // So we should hide the notification at all.
            // When autoplay enabled such notification flashing is annoying so skip this case
        }
    }

    @Override
    public void onTaskRemoved(final Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!player.videoPlayerSelected()) {
            return;
        }
        onDestroy();
        // Unload from memory completely
        Runtime.getRuntime().halt(0);
    }

    @Override
    public void onDestroy() {
        if (DEBUG) {
            Log.d(TAG, "destroy() called");
        }
        super.onDestroy();

        cleanup();

        mediaBrowserPlaybackPreparer.dispose();
        mediaSession.release();
        mediaBrowserImpl.dispose();
    }

    private void cleanup() {
        if (player != null) {
            // Exit from fullscreen when user closes the player via notification
            if (player.isFullscreen()) {
                player.toggleFullscreen();
            }
            removeViewFromParent();

            player.saveStreamProgressState();
            player.setRecovery();
            player.stopActivityBinding();
            player.removePopupFromView();
            player.destroy();

            player = null;
            mediaSession.setActive(false);

            // Should already be handled by NotificationUtil.cancelNotificationAndStopForeground() in
            // NotificationPlayerUi, but let's make sure that the foreground service is stopped.
//            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        }
    }

    public void stopService() {
        NotificationUtil.getInstance().cancelNotificationAndStopForeground(this);
        cleanup();
        destroyPlayerAndStopService();
    }

    public void destroyPlayerAndStopService() {
        if (DEBUG) {
            Log.d(TAG, "destroyPlayerAndStopService() called");
        }

        cleanup();

        // This only really stops the service if there are no other service connections (see docs):
        // for example the (Android Auto) media browser binder will block stopService().
        // This is why we also stopForeground() above, to make sure the notification is removed.
        // If we were to call stopSelf(), then the service would be surely stopped (regardless of
        // other service connections), but this would be a waste of resources since the service
        // would be immediately restarted by those same connections to perform the queries.
        stopService(new Intent(this, MainPlayer.class));
    }
    @Override
    protected void attachBaseContext(final Context base) {
        super.attachBaseContext(AudioServiceLeakFix.preventLeakOf(base));
    }

    @Override
    public IBinder onBind(final Intent intent) {
        if (DeviceUtils.isAutomotiveDevice(this)) {
            // MediaBrowserService also uses its own binder, so for actions related to the media
            // browser service, pass the onBind to the superclass.
            return super.onBind(intent);
        } else {
            if (DEBUG) {
                Log.d(TAG, "MediaBrowser connection rejected - not from Android Auto");
            }
            return mBinder;
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    boolean isLandscape() {
        // DisplayMetrics from activity context knows about MultiWindow feature
        // while DisplayMetrics from app context doesn't
        return DeviceUtils.isLandscape(player != null && player.getParentActivity() != null
                ? player.getParentActivity() : this);
    }

    @Nullable
    public View getView() {
        if (player == null) {
            return null;
        }

        return player.getRootView();
    }

    public void removeViewFromParent() {
        if (getView() != null && getView().getParent() != null) {
            if (player.getParentActivity() != null) {
                // This means view was added to fragment
                final ViewGroup parent = (ViewGroup) getView().getParent();
                parent.removeView(getView());
            } else {
                // This means view was added by windowManager for popup player
                windowManager.removeViewImmediate(getView());
            }
        }
    }

    /**
     * @return the current active player instance. May be null, since the player service can outlive
     * the player e.g. to respond to Android Auto media browser queries.
     */
    @Nullable
    public Player getPlayer() {
        return player;
    }

    /**
     * @return the media session for Android Auto compatibility
     */
    @NonNull
    public MediaSessionCompat getMediaSession() {
        return mediaSession;
    }

    /**
     * @return the media browser playback preparer for Android Auto compatibility
     */
    @NonNull
    public MediaBrowserPlaybackPreparer getMediaBrowserPlaybackPreparer() {
        return mediaBrowserPlaybackPreparer;
    }

    /**
     * Sets the listener that will be called when the player is started or stopped. If a
     * {@code null} listener is passed, then the current listener will be unset. The parameter taken
     * by the {@link Consumer} can be null to indicate that the player is stopping.
     * @param listener the listener to set or unset
     */
    public void setPlayerListener(@Nullable final Consumer<Player> listener) {
        this.onPlayerStartedOrStopped = listener;
        if (listener != null) {
            // if there is no player, then `null` will be sent here, to ensure the state is synced
            listener.accept(player);
        }
    }
    //endregion

    //region Media browser
    @Override
    public BrowserRoot onGetRoot(@NonNull final String clientPackageName,
                                 final int clientUid,
                                 @Nullable final Bundle rootHints) {
        // TODO check if the accessing package has permission to view data
        return mediaBrowserImpl.onGetRoot(clientPackageName, clientUid, rootHints);
    }

    @Override
    public void onLoadChildren(@NonNull final String parentId,
                               @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
        mediaBrowserImpl.onLoadChildren(parentId, result);
    }

    @Override
    public void onSearch(@NonNull final String query,
                         final Bundle extras,
                         @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
        mediaBrowserImpl.onSearch(query, result);
    }
    //endregion


    public class LocalBinder extends Binder {

        public MainPlayer getService() {
            return MainPlayer.this;
        }

        public Player getPlayer() {
            return MainPlayer.this.player;
        }
    }
}
