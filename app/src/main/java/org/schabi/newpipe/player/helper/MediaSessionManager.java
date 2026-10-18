package org.schabi.newpipe.player.helper;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.session.MediaButtonReceiver;

import com.google.android.exoplayer2.ForwardingPlayer;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.PlaybackPreparer;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.player.NotificationUtil;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.mediasession.MediaSessionCallback;
import org.schabi.newpipe.player.mediasession.PlayQueueNavigator;
import org.schabi.newpipe.player.playback.PlayerMediaSession;
import org.schabi.newpipe.util.StreamTypeUtil;

import java.util.Optional;

import static org.schabi.newpipe.player.PlayerService.*;

public class MediaSessionManager {
    private static final String TAG = MediaSessionManager.class.getSimpleName();
    public static final boolean DEBUG = MainActivity.DEBUG;

    @NonNull
    private final MediaSessionCompat mediaSession;
    @NonNull
    private final MediaSessionConnector sessionConnector;
    private final boolean isExternalSession;

    private int lastTitleHashCode;
    private int lastArtistHashCode;
    private long lastDuration;
    private int lastAlbumArtHashCode;

    private org.schabi.newpipe.player.Player player;

    public MediaSessionManager(@NonNull final Context context,
                               @NonNull final Player player,
                               @NonNull final MediaSessionCallback callback) {
        this(context, player, callback, null);
    }

    public MediaSessionManager(@NonNull final Context context,
                               @NonNull final Player player,
                               @NonNull final MediaSessionCallback callback,
                               @Nullable final MediaSessionCompat existingSession) {
        this(context, player, callback, existingSession, null);
    }

    public MediaSessionManager(@NonNull final Context context,
                               @NonNull final Player player,
                               @NonNull final MediaSessionCallback callback,
                               @Nullable final MediaSessionCompat existingSession,
                               @Nullable final PlaybackPreparer playbackPreparer) {
        if (DEBUG) {
            Log.d(TAG, "MediaSessionManager called");
        }
        mediaSession = existingSession != null ? existingSession : new MediaSessionCompat(context, TAG);
        isExternalSession = existingSession != null;
        mediaSession.setActive(true);
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, -1, 1)
                .setActions(PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE // was play and pause now play/pause
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SET_REPEAT_MODE
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                .build());

        sessionConnector = new MediaSessionConnector(mediaSession);
        final PlayQueueNavigator queueNavigator = new PlayQueueNavigator(mediaSession, callback);
        queueNavigator.setSeekInsteadOfSkip(() -> PlayerHelper.getSeekInsteadOfSkip(context));
        sessionConnector.setQueueNavigator(queueNavigator);
        // previous/next are no longer advertised when seeking instead, so handle the keys here
        sessionConnector.setMediaButtonEventHandler((p, event) -> {
            if (!PlayerHelper.getSeekInsteadOfSkip(context)) {
                return false;
            }
            final KeyEvent key = event.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (key == null) {
                return false;
            }
            final int code = key.getKeyCode();
            if (code != KeyEvent.KEYCODE_MEDIA_NEXT && code != KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                return false;
            }
            if (key.getAction() == KeyEvent.ACTION_DOWN && key.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_MEDIA_NEXT) {
                    callback.fastForward();
                } else {
                    callback.fastRewind();
                }
            }
            return true;
        });
        sessionConnector.setPlayer(new ForwardingPlayer(player) {
            @Override
            public void seekBack() {
                callback.fastRewind();
            }

            @Override
            public void seekForward() {
                callback.fastForward();
            }

            @Override
            public void play() {
                callback.play();
            }

            @Override
            public void pause() {
                callback.pause();
            }
        });
        MediaSessionConnector.CustomActionProvider[] providers = new MediaSessionConnector.CustomActionProvider[4];
        // rewind/fast-forward go first so Android 13+ puts them in the freed previous/next slots
        providers[2] = seekActionProvider(context, callback, ACTION_FAST_REWIND, "Rewind",
                R.drawable.exo_controls_rewind);
        providers[3] = seekActionProvider(context, callback, ACTION_FAST_FORWARD, "Fast-forward",
                R.drawable.exo_controls_fastforward);
        providers[0] = new MediaSessionConnector.CustomActionProvider() {
            @Override
            public void onCustomAction(@NonNull Player player, @NonNull String action, @Nullable Bundle extras) {
                if (action.equals(ACTION_CHANGE_PLAY_MODE)) {
                    callback.changePlayMode();
                }
            }

            @Nullable
            @Override
            public PlaybackStateCompat.CustomAction getCustomAction(Player player) {
                switch (((PlayerMediaSession)callback).mode){
                    case 0:
                    default:
                        return new android.support.v4.media.session.PlaybackStateCompat.CustomAction.Builder(
                                ACTION_CHANGE_PLAY_MODE, "Shuffle", R.drawable.shuffle_disabled).build();
                    case 1:
                        return new android.support.v4.media.session.PlaybackStateCompat.CustomAction.Builder(
                                ACTION_CHANGE_PLAY_MODE, "Repeat all", R.drawable.exo_controls_shuffle_on).build();
                    case 2:
                        return new android.support.v4.media.session.PlaybackStateCompat.CustomAction.Builder(
                                ACTION_CHANGE_PLAY_MODE, "Repeat none", R.drawable.exo_controls_repeat_one).build();
                    case 3:
                        return new android.support.v4.media.session.PlaybackStateCompat.CustomAction.Builder(
                                ACTION_CHANGE_PLAY_MODE, "Repeat one", R.drawable.exo_controls_repeat_all).build();
                }
            }
        };
        providers[1] = new MediaSessionConnector.CustomActionProvider() {
            @Override
            public void onCustomAction(@NonNull Player player, @NonNull String action, @Nullable Bundle extras) {
                if (action.equals(ACTION_CLOSE)) {
                    callback.close();
                }
            }

            @Nullable
            @Override
            public PlaybackStateCompat.CustomAction getCustomAction(Player player) {
                // Close
                return new android.support.v4.media.session.PlaybackStateCompat.CustomAction.Builder(
                        ACTION_CLOSE, "Close", R.drawable.ic_close).build();
            }
        };
        sessionConnector.setCustomActionProviders(
                providers[2], providers[3], providers[0], providers[1]);
        sessionConnector.setMetadataDeduplicationEnabled(true);
        sessionConnector.setMediaMetadataProvider(exoPlayer -> buildMediaMetadata());

        // Set PlaybackPreparer if provided (for Android Auto support)
        if (playbackPreparer != null) {
            sessionConnector.setPlaybackPreparer(playbackPreparer);
        }
    }

    private static MediaSessionConnector.CustomActionProvider seekActionProvider(
            @NonNull final Context context, @NonNull final MediaSessionCallback callback,
            @NonNull final String actionName, @NonNull final String label, final int icon) {
        return new MediaSessionConnector.CustomActionProvider() {
            @Override
            public void onCustomAction(@NonNull final Player player, @NonNull final String action,
                                       @Nullable final Bundle extras) {
                if (action.equals(actionName)) {
                    if (actionName.equals(ACTION_FAST_FORWARD)) {
                        callback.fastForward();
                    } else {
                        callback.fastRewind();
                    }
                }
            }

            @Nullable
            @Override
            public PlaybackStateCompat.CustomAction getCustomAction(final Player player) {
                if (!PlayerHelper.getSeekInsteadOfSkip(context)) {
                    return null;
                }
                return new PlaybackStateCompat.CustomAction.Builder(actionName, label, icon)
                        .build();
            }
        };
    }

    @Nullable
    @SuppressWarnings("UnusedReturnValue")
    public KeyEvent handleMediaButtonIntent(final Intent intent) {
        return MediaButtonReceiver.handleIntent(mediaSession, intent);
    }

    public MediaSessionCompat.Token getSessionToken() {
        return mediaSession.getSessionToken();
    }

    public void setPlayer(org.schabi.newpipe.player.Player player) {
        this.player = player;
    }

    private MediaMetadataCompat buildMediaMetadata() {
        if (DEBUG) {
            Log.d(TAG, "buildMediaMetadata called");
        }

        if(player == null) {
            return new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0)
                    .build();
        }

        // set title and artist
        final MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, player.getVideoTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, player.getUploaderName());

        // set duration (-1 for livestreams or if unknown, see the METADATA_KEY_DURATION docs)
        final long duration = player.getCurrentStreamInfo()
                .filter(info -> !StreamTypeUtil.isLiveStream(info.getStreamType()))
                .map(info -> info.getDuration() * 1000L)
                .orElse(-1L);
        builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);

        // set album art, unless the user asked not to, or there is no thumbnail available
        final boolean showThumbnail = player.getPrefs().getBoolean(
                player.getContext().getString(R.string.show_thumbnail_key), true);
        Optional.ofNullable(player.getThumbnail())
                .filter(bitmap -> showThumbnail && !bitmap.isRecycled())
                .ifPresent(bitmap -> {
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap);
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap);
                });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            final org.schabi.newpipe.player.Player currentPlayer = player;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (currentPlayer != null
                        && currentPlayer.getMediaSessionManager() == MediaSessionManager.this) {
                    NotificationUtil.getInstance()
                            .createNotificationIfNeededAndUpdate(currentPlayer, false);
                }
            }, 100);
        }
        return builder.build();
    }

    /**
     * Should be called on player destruction to prevent leakage.
     */
    public void dispose() {
        sessionConnector.setPlayer(null);
        sessionConnector.setQueueNavigator(null);
        if (!isExternalSession) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
    }
}
