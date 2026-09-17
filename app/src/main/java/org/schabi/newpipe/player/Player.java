package org.schabi.newpipe.player;

import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_INTERNAL;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_REMOVE;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SKIP;
import static com.google.android.exoplayer2.Player.DiscontinuityReason;
import static org.schabi.newpipe.extractor.ServiceList.YouTube;
import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;
import static org.schabi.newpipe.ktx.ViewUtils.animate;
import static org.schabi.newpipe.ktx.ViewUtils.animateRotation;
import static org.schabi.newpipe.player.PlayerService.*;
import static org.schabi.newpipe.player.helper.PlayerHelper.*;
import static org.schabi.newpipe.player.helper.PlayerHelper.MinimizeMode.MINIMIZE_ON_EXIT_MODE_BACKGROUND;
import static org.schabi.newpipe.player.helper.PlayerHelper.MinimizeMode.MINIMIZE_ON_EXIT_MODE_NONE;
import static org.schabi.newpipe.player.helper.PlayerHelper.MinimizeMode.MINIMIZE_ON_EXIT_MODE_POPUP;

import android.annotation.SuppressLint;
import android.content.*;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.net.Uri;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.Player.PositionInfo;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoSize;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.*;
import org.schabi.newpipe.extractor.stream.*;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.info_list.StreamSegmentAdapter;
import org.schabi.newpipe.ktx.AnimationType;
import org.schabi.newpipe.local.dialog.PlaylistDialog;
import org.schabi.newpipe.player.PlayerService.PlayerType;
import org.schabi.newpipe.player.event.PlayerEventListener;
import org.schabi.newpipe.player.event.PlayerServiceEventListener;
import org.schabi.newpipe.player.helper.AudioReactor;
import org.schabi.newpipe.player.helper.CustomRenderersFactory;
import org.schabi.newpipe.player.helper.LoadController;
import org.schabi.newpipe.player.helper.MediaSessionManager;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.mediaitem.ExoMediaItems;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import org.schabi.newpipe.player.mediasession.PlayerServiceInterface;
import org.schabi.newpipe.player.playback.MediaSourceManager;
import org.schabi.newpipe.player.playback.PlayerMediaSession;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueAdapter;
import org.schabi.newpipe.player.resolver.PlayerQualityResolver;
import org.schabi.newpipe.player.resolver.SourceResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver.SourceType;
import org.schabi.newpipe.sleep.SleepTimerService;
import org.schabi.newpipe.util.*;
import org.schabi.newpipe.util.external_communication.KoreUtils;
import org.schabi.newpipe.util.external_communication.ShareUtils;
import org.schabi.newpipe.views.ExpandableSurfaceView;
import android.widget.TextView;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public final class Player implements
        SeekBar.OnSeekBarChangeListener,
        View.OnClickListener,
        View.OnLongClickListener {
    public static final boolean DEBUG = MainActivity.DEBUG;
    public static final String TAG = Player.class.getSimpleName();

    /*//////////////////////////////////////////////////////////////////////////
    // States
    //////////////////////////////////////////////////////////////////////////*/

    // Playback states live in PlayerPlaybackState.

    /*//////////////////////////////////////////////////////////////////////////
    // Time constants
    //////////////////////////////////////////////////////////////////////////*/

    public static final int PLAY_PREV_ACTIVATION_LIMIT_MILLIS = 5000; // 5 seconds
    public static final int PROGRESS_LOOP_INTERVAL_MILLIS = 1000; // 1 second
    public static final int DEFAULT_CONTROLS_DURATION = 300; // 300 millis
    public static final int DEFAULT_CONTROLS_HIDE_TIME = 2000;  // 2 Seconds
    public static final int DPAD_CONTROLS_HIDE_TIME = 7000;  // 7 Seconds
    public static final int SEEK_OVERLAY_DURATION = 450; // 450 millis

    /*//////////////////////////////////////////////////////////////////////////
    // Other constants
    //////////////////////////////////////////////////////////////////////////*/

    static final int RENDERER_UNAVAILABLE = -1;
    private static final int MAX_RETRY_COUNT = 2;

    /*//////////////////////////////////////////////////////////////////////////
    // Playback
    //////////////////////////////////////////////////////////////////////////*/

    // play queue might be null e.g. while player is starting
    @Nullable private PlayQueue playQueue;

    @Nullable private MediaSourceManager playQueueManager;

    @Nullable private PlayerMediaItem currentItem;
    @Nullable private PlayerMediaItem currentMetadata;

    /*//////////////////////////////////////////////////////////////////////////
    // Player
    //////////////////////////////////////////////////////////////////////////*/

    ExoPlayer simpleExoPlayer;
    private ExoPlayerEventAdapter exoPlayerEventAdapter;
    private AudioReactor audioReactor;
    @Nullable private MediaSessionManager mediaSessionManager;
    private PlayerMediaSession playerMediaSession;

    @NonNull private final DefaultTrackSelector trackSelector;
    @NonNull private final LoadController loadController;
    @NonNull private final DefaultRenderersFactory renderFactory;

    @NonNull private final SourceResolver sourceResolver;
    @NonNull private final PlayerErrorHandler playerErrorHandler;
    @NonNull private final RepeatShuffleController repeatShuffleController;
    @NonNull private final PlayerTracksController tracksController;
    @NonNull private final PlayerProgressController progressController;
    @NonNull private final PlayerThumbnailController thumbnailController;
    @NonNull private final PlayerSurfaceController surfaceController;
    @NonNull private final PlayerSabrBackoffCountdown sabrBackoffCountdown;
    @NonNull private final PlayerBroadcastReceiver broadcastReceiverController;
    @NonNull private final PlayerListeners listeners;
    @NonNull private final PlayerPlaybackStateController playbackStateController;
    @NonNull private final PlayerQueueController queueController;
    @NonNull private final PlayerGestureController gestureController;
    @NonNull private final PlayerControlsVisibilityController controlsVisibilityController;
    @NonNull private final AutoQueueController autoQueueController;
    @NonNull private final PlayerStartController startController;
    @NonNull private final PlayerTransportController transportController;
    @NonNull private final PlayerHistoryController historyController;

    public final PlayerServiceInterface service; //TODO try to remove and replace everything with context

    /*//////////////////////////////////////////////////////////////////////////
    // Player states
    //////////////////////////////////////////////////////////////////////////*/

    private PlayerType playerType = PlayerType.VIDEO;

    private PlayerPlaybackState currentState = PlayerPlaybackState.PREFLIGHT;
    @NonNull private final PlaybackListenerAdapter playbackListenerAdapter =
            new PlaybackListenerAdapter(this);

    // audio only mode does not mean that player type is background, but that the player was
    // minimized to background but will resume automatically to the original player type
    private boolean isAudioOnly = false;
    private boolean isPrepared = false;
    private boolean isFullscreen = false;
    private boolean isVerticalVideo = false;
    private long startupTraceId;

    private List<VideoStream> availableStreams;
    private int selectedStreamIndex;

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    private PlayerBinding binding;


    // fullscreen player

    /*//////////////////////////////////////////////////////////////////////////
    // Popup menus ("popup" means that they pop up, not that they belong to the popup player)
    //////////////////////////////////////////////////////////////////////////*/

    private PlayerMenuController menuController;

    /*//////////////////////////////////////////////////////////////////////////
    // Popup player
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull private final PopupWindowController popupWindowController;

    /*//////////////////////////////////////////////////////////////////////////
    // Popup player window manager
    //////////////////////////////////////////////////////////////////////////*/

    public static final int IDLE_WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
    public static final int ONGOING_PLAYBACK_WINDOW_FLAGS = IDLE_WINDOW_FLAGS
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

    // The popup window state (layout params, window manager, screen size and the
    // drag-to-close overlay) lives in PopupWindowController.

    /*//////////////////////////////////////////////////////////////////////////
    // Gestures
    //////////////////////////////////////////////////////////////////////////*/

    // The gesture detector, the gesture listener, the swipe overlay state and the pinch
    // zoom state live in PlayerGestureController.

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull private final Context context;
    @NonNull private final SharedPreferences prefs;


    /*//////////////////////////////////////////////////////////////////////////
    // Constructor
    //////////////////////////////////////////////////////////////////////////*/
    //region Constructor

    /*//////////////////////////////////////////////////////////////////////////
    // SponsorBlock
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull private final SponsorBlockController sponsorBlockController;

    /*//////////////////////////////////////////////////////////////////////////
    // Bullet comments
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull private final BulletCommentsController bulletCommentsController;

    private PlayerDataSource dataSource;



    public Player(@NonNull final PlayerServiceInterface service) {
        this.service = service;
        context = service.getInstance();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);

        sponsorBlockController = new SponsorBlockController(this);
        bulletCommentsController = new BulletCommentsController(this);
        listeners = new PlayerListeners(this);
        playbackStateController = new PlayerPlaybackStateController(this);
        queueController = new PlayerQueueController(this);
        progressController = new PlayerProgressController(this);
        thumbnailController = new PlayerThumbnailController(this);
        surfaceController = new PlayerSurfaceController(this);
        sabrBackoffCountdown = new PlayerSabrBackoffCountdown(this);
        broadcastReceiverController = new PlayerBroadcastReceiver(this);

        broadcastReceiverController.setup();

        trackSelector = createTrackSelector();
        dataSource = new PlayerDataSource(context, DownloaderImpl.USER_AGENT,
                new DefaultBandwidthMeter.Builder(context).build());
        loadController = new LoadController();

        renderFactory = prefs.getBoolean(
                context.getString(
                        R.string.always_use_exoplayer_set_output_surface_workaround_key), false)
                ? new CustomRenderersFactory(context) : new DefaultRenderersFactory(context);

        if (prefs.getBoolean(context.getString(
                R.string.disable_exoplayer_media_codec_async_queueing_key), false)) {
            renderFactory.forceDisableMediaCodecAsynchronousQueueing();
        }
        renderFactory.setEnableDecoderFallback(true);

        sourceResolver = new SourceResolver(context, dataSource,
                new PlayerQualityResolver(context, this::videoPlayerSelected));
        playerErrorHandler = new PlayerErrorHandler(this);
        repeatShuffleController = new RepeatShuffleController(this);
        tracksController = new PlayerTracksController(this);

        popupWindowController = new PopupWindowController(this);
        gestureController = new PlayerGestureController(this);
        controlsVisibilityController = new PlayerControlsVisibilityController(this);
        autoQueueController = new AutoQueueController(this);
        startController = new PlayerStartController(this);
        transportController = new PlayerTransportController(this);
        historyController = new PlayerHistoryController(this);
    }

    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Setup and initialization
    //////////////////////////////////////////////////////////////////////////*/
    //region Setup and initialization

    public void setupFromView(@NonNull final PlayerBinding playerBinding) {
        initViews(playerBinding);
        if (exoPlayerIsNull()) {
            initPlayer(true);
        }
        initListeners();

        gestureController.setupSeekOverlay();
    }

    private void initViews(@NonNull final PlayerBinding playerBinding) {
        binding = playerBinding;
        tracksController.setupSubtitleView();

        binding.playbackSeekBar.getThumb()
                .setColorFilter(new PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN));
        binding.playbackSeekBar.getProgressDrawable()
                .setColorFilter(new PorterDuffColorFilter(Color.RED, PorterDuff.Mode.MULTIPLY));

        menuController = new PlayerMenuController(this);
        menuController.updateDisplayModeButtonText();

        binding.progressBarLoadingPanel.getIndeterminateDrawable()
                .setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.MULTIPLY));

        binding.titleTextView.setSelected(true);
        binding.channelTextView.setSelected(true);

        // Prevent hiding of bottom sheet via swipe inside queue
        binding.itemsList.setNestedScrollingEnabled(false);
    }

    void initPlayer(final boolean playOnReady) {
        if (DEBUG) {
            Log.d(TAG, "initPlayer() called with: playOnReady = [" + playOnReady + "]");
        }

        simpleExoPlayer = new ExoPlayer.Builder(context, renderFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadController)
                .build();
        exoPlayerEventAdapter = new ExoPlayerEventAdapter(this);
        simpleExoPlayer.addListener(exoPlayerEventAdapter);
        simpleExoPlayer.setPlayWhenReady(playOnReady);
        simpleExoPlayer.setSeekParameters(PlayerHelper.getSeekParameters(context));
        simpleExoPlayer.setWakeMode(C.WAKE_MODE_NETWORK);
        simpleExoPlayer.setHandleAudioBecomingNoisy(true);


        audioReactor = new AudioReactor(context, simpleExoPlayer);
        setupMediaSession();

        broadcastReceiverController.register();

        // Setup video view
        surfaceController.setupVideoSurface();

        // enable media tunneling
        if (PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.disable_media_tunneling_key), false)) {
            Log.d(TAG, "[" + Util.DEVICE_DEBUG_INFO + "] "
                    + "media tunneling disabled by user preference");
        } else if (DeviceUtils.shouldSupportMediaTunneling()) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setTunnelingEnabled(true));
        } else {
            Log.d(TAG, "[" + Util.DEVICE_DEBUG_INFO + "] does not support media tunneling");
        }
    }

    private DefaultTrackSelector createTrackSelector() {
        return new DefaultTrackSelector(context, PlayerHelper.getQualitySelector());
    }

    void setupMediaSession() {
        playerMediaSession = new PlayerMediaSession(this, simpleExoPlayer);
        mediaSessionManager = new MediaSessionManager(context, simpleExoPlayer,
                playerMediaSession, service.getMediaSession(),
                service.getMediaBrowserPlaybackPreparer());
    }

    private void initListeners() {
        binding.qualityTextView.setOnClickListener(v -> menuController.onQualityClicked(v));
        binding.playbackSpeed.setOnClickListener(
                v -> menuController.onPlaybackSpeedClicked(v));

        binding.playbackSeekBar.setOnSeekBarChangeListener(this);
        binding.captionTextView.setOnClickListener(this);
        binding.audioTrackTextView.setOnClickListener(this);
        binding.resizeTextView.setOnClickListener(this);
        binding.playbackLiveSync.setOnClickListener(this);

        gestureController.setup();

        binding.queueButton.setOnClickListener(v -> queueController.onQueueClicked());
        binding.segmentsButton.setOnClickListener(v -> queueController.onSegmentsClicked());
        binding.repeatButton.setOnClickListener(v -> onRepeatClicked());
        binding.shuffleButton.setOnClickListener(v -> onShuffleClicked());
        binding.addToPlaylistButton.setOnClickListener(v -> {
            if (getParentActivity() != null) {
                onAddToPlaylistClicked(getParentActivity().getSupportFragmentManager());
            }
        });

        binding.playPauseButton.setOnClickListener(this);
        binding.playPreviousButton.setOnClickListener(this);
        binding.playNextButton.setOnClickListener(this);

        binding.moreOptionsButton.setOnClickListener(this);
        binding.moreOptionsButton.setOnLongClickListener(this);
        binding.share.setOnClickListener(this);
        binding.share.setOnLongClickListener(this);
        binding.fullScreenButton.setOnClickListener(this);
        binding.screenRotationButton.setOnClickListener(this);
        binding.switchCommentsVisibility.setOnClickListener(this);
        binding.playWithKodi.setOnClickListener(this);
        binding.openInBrowser.setOnClickListener(this);
        binding.playerCloseButton.setOnClickListener(this);
        binding.switchMute.setOnClickListener(this);
        binding.sleepTimer.setOnClickListener(this);
        binding.sleepTimer.setOnLongClickListener(this);
        binding.skipButton.setOnClickListener(this);
        binding.unskipButton.setOnClickListener(this);

        ViewCompat.setOnApplyWindowInsetsListener(binding.itemsListPanel, (view, windowInsets) -> {
            final Insets cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            if (!cutout.equals(Insets.NONE)) {
                view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom);
            }
            return windowInsets;
        });

        // PlaybackControlRoot already consumed window insets but we should pass them to
        // player_overlays and fast_seek_overlay too. Without it they will be off-centered.
        binding.playbackControlRoot.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    binding.playerOverlays.setPadding(
                            v.getPaddingLeft(),
                            v.getPaddingTop(),
                            v.getPaddingRight(),
                            v.getPaddingBottom());
                    if(v.getPaddingLeft() != 0 || v.getPaddingTop() != 0
                            || v.getPaddingRight() != 0 || v.getPaddingBottom() != 0){
                        binding.playButtons.setPadding(
                                -v.getPaddingLeft(), -v.getPaddingTop(),-v.getPaddingRight(),-v.getPaddingBottom());
                        binding.loadingPanelWrapper.setPadding(
                                -v.getPaddingLeft(), -v.getPaddingTop(),-v.getPaddingRight(),-v.getPaddingBottom());
                    } else {
                        binding.playButtons.setPadding(0, 0, 0, 0);
                        binding.loadingPanelWrapper.setPadding(0, 0, 0, 0);
                    }

                    // If we added padding to the fast seek overlay, too, it would not go under the
                    // system ui. Instead we apply negative margins equal to the window insets of
                    // the opposite side, so that the view covers all of the player (overflowing on
                    // some sides) and its center coincides with the center of other controls.
                    final RelativeLayout.LayoutParams fastSeekParams = (RelativeLayout.LayoutParams)
                            binding.fastSeekOverlay.getLayoutParams();
                    fastSeekParams.leftMargin = -v.getPaddingRight();
                    fastSeekParams.topMargin = -v.getPaddingBottom();
                    fastSeekParams.rightMargin = -v.getPaddingLeft();
                    fastSeekParams.bottomMargin = -v.getPaddingTop();
                });
    }


    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback initialization via intent
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback initialization via intent

    public void handleIntent(@NonNull final Intent intent) {
        startController.handleIntent(intent);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Destroy and recovery
    //////////////////////////////////////////////////////////////////////////*/
    //region Destroy and recovery

    void destroyPlayer() {
        if (DEBUG) {
            Log.d(TAG, "destroyPlayer() called");
        }

        stopSabrBackoffCountdown();
        surfaceController.cleanupVideoSurface();

        if (!exoPlayerIsNull()) {
            simpleExoPlayer.removeListener(exoPlayerEventAdapter);
            exoPlayerEventAdapter = null;
            simpleExoPlayer.stop();
            simpleExoPlayer.release();
        }
        if (progressController.isProgressLoopRunning()) {
            progressController.stopProgressLoop();
        }
        if (playQueue != null) {
            playQueue.dispose();
        }
        if (audioReactor != null) {
            audioReactor.dispose();
        }
        if (playQueueManager != null) {
            playQueueManager.dispose();
        }
        if (mediaSessionManager != null) {
            mediaSessionManager.dispose();
            mediaSessionManager = null;
        }

        queueController.disposeAdapters();
        bulletCommentsController.destroy();
        autoQueueController.cancelEnqueueTimer();
        dataSource.disconnectWebSocketClients();
    }

    public void destroy() {
        if (DEBUG) {
            Log.d(TAG, "destroy() called");
        }
        
        // Close popup menus before destroying to prevent crash
        closeAllPopupMenus();
        
        destroyPlayer();
        broadcastReceiverController.unregister();
        sponsorBlockController.destroy();

        historyController.clear();
        progressController.stopProgressLoop();
        PicassoHelper.cancelTag(PicassoHelper.PLAYER_THUMBNAIL_TAG); // cancel thumbnail loading

        if (binding != null) {
            binding.endScreen.setImageBitmap(null);
        }

    }

    public void setRecovery() {
        if (playQueue == null || exoPlayerIsNull()) {
            return;
        }

        final int queuePos = playQueue.getIndex();
        final long windowPos = simpleExoPlayer.getCurrentPosition();
        final long duration = simpleExoPlayer.getDuration();

        final long newPos =  Math.max(0, Math.min(windowPos, duration));
        if(newPos > 0) {
            setRecovery(queuePos, newPos);
        }
    }

    private void setRecovery(final int queuePos, final long windowPos) {
        if (playQueue.size() <= queuePos) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Setting recovery, queue: " + queuePos + ", pos: " + windowPos);
        }
        playQueue.setRecovery(queuePos, windowPos);
    }

    void reloadPlayQueueManager() {
        if (playQueueManager != null) {
            playQueueManager.dispose();
        }

        if (playQueue != null) {
            playQueueManager = new MediaSourceManager(context, playbackListenerAdapter, playQueue);
        }
    }

    void onPlaybackShutdown() {
        if (DEBUG) {
            Log.d(TAG, "onPlaybackShutdown() called");
        }
        // destroys the service, which in turn will destroy the player
        service.stopService();
    }

    public void smoothStopPlayer() {
        // Pausing would make transition from one stream to a new stream not smooth, so only stop
        simpleExoPlayer.stop();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Player type specific setup
    //////////////////////////////////////////////////////////////////////////*/
    //region Player type specific setup

    void initVideoPlayer() {
        // Pinch zoom owns video scaling while enabled; otherwise restore the regular display mode.
        setResizeMode(PlayerHelper.isPinchToZoomEnabled(context)
                ? AspectRatioFrameLayout.RESIZE_MODE_FIT
                : PlayerHelper.retrieveResizeModeFromPrefs(this));
        binding.surfaceView.resetPinchScale();
        binding.getRoot().setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    @SuppressLint("RtlHardcoded")
    void initPopup() {
        popupWindowController.initPopup();
    }

    @SuppressLint("RtlHardcoded")
    void initPopupCloseOverlay() {
        popupWindowController.initPopupCloseOverlay();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Elements visibility and size: popup and main players have different look
    //////////////////////////////////////////////////////////////////////////*/
    //region Elements visibility and size: popup and main players have different look

    /**
     * This method ensures that popup and main players have different look.
     * We use one layout for both players and need to decide what to show and what to hide.
     * Additional measuring should be done inside {@link #setupElementsSize}.
     */
    void setupElementsVisibility() {
        if (popupPlayerSelected()) {
            binding.fullScreenButton.setVisibility(View.VISIBLE);
            binding.screenRotationButton.setVisibility(View.GONE);
            binding.resizeTextView.setVisibility(View.GONE);
            binding.getRoot().findViewById(R.id.metadataView).setVisibility(View.GONE);
            binding.queueButton.setVisibility(View.GONE);
            binding.segmentsButton.setVisibility(View.GONE);
            binding.moreOptionsButton.setVisibility(View.GONE);
            binding.topControls.setOrientation(LinearLayout.HORIZONTAL);
            binding.primaryControls.getLayoutParams().width
                    = LinearLayout.LayoutParams.WRAP_CONTENT;
            binding.secondaryControls.setAlpha(1.0f);
            binding.secondaryControls.setVisibility(View.VISIBLE);
            binding.secondaryControls.setTranslationY(0);
            binding.share.setVisibility(View.GONE);
            binding.switchCommentsVisibility.setVisibility(View.GONE);
            binding.playWithKodi.setVisibility(View.GONE);
            binding.openInBrowser.setVisibility(View.GONE);
            binding.sleepTimer.setVisibility(View.GONE);
            binding.switchMute.setVisibility(View.GONE);
            binding.playerCloseButton.setVisibility(View.GONE);
            binding.topControls.bringToFront();
            binding.topControls.setClickable(false);
            binding.topControls.setFocusable(false);
            binding.bottomControls.bringToFront();
            closeItemsList();
        } else if (videoPlayerSelected()) {
            binding.fullScreenButton.setVisibility(View.GONE);
            setupScreenRotationButton();
            binding.resizeTextView.setVisibility(View.VISIBLE);
            binding.getRoot().findViewById(R.id.metadataView).setVisibility(View.VISIBLE);
            binding.moreOptionsButton.setVisibility(View.VISIBLE);
            binding.topControls.setOrientation(LinearLayout.VERTICAL);
            binding.primaryControls.getLayoutParams().width
                    = LinearLayout.LayoutParams.MATCH_PARENT;
            binding.secondaryControls.setVisibility(View.INVISIBLE);
            binding.moreOptionsButton.setImageDrawable(AppCompatResources.getDrawable(context,
                    R.drawable.ic_expand_more));
            binding.share.setVisibility(View.VISIBLE);
            binding.switchCommentsVisibility.setVisibility(View.VISIBLE);
            binding.openInBrowser.setVisibility(View.VISIBLE);
            binding.sleepTimer.setVisibility(View.VISIBLE);
            binding.switchMute.setVisibility(View.VISIBLE);
            binding.playerCloseButton.setVisibility(isFullscreen ? View.GONE : View.VISIBLE);
            // Top controls have a large minHeight which is allows to drag the player
            // down in fullscreen mode (just larger area to make easy to locate by finger)
            binding.topControls.setClickable(true);
            binding.topControls.setFocusable(true);
        }
        showHideKodiButton();

        if (isFullscreen) {
            binding.titleTextView.setVisibility(View.VISIBLE);
            binding.channelTextView.setVisibility(View.VISIBLE);
            binding.sleepTimer.setVisibility(View.VISIBLE);
        } else {
            binding.titleTextView.setVisibility(View.GONE);
            binding.channelTextView.setVisibility(View.GONE);
            binding.sleepTimer.setVisibility(View.GONE);
        }
        setMuteButton(binding.switchMute, isMuted());

        animateRotation(binding.moreOptionsButton, DEFAULT_CONTROLS_DURATION, 0);
    }

    /**
     * Changes padding, size of elements based on player selected right now.
     * Popup player has small padding in comparison with the main player
     */
    void setupElementsSize() {
        final Resources res = context.getResources();
        final int buttonsMinWidth;
        final int playerTopPad;
        final int controlsPad;
        final int buttonsPad;

        if (popupPlayerSelected()) {
            buttonsMinWidth = 0;
            playerTopPad = 0;
            controlsPad = res.getDimensionPixelSize(R.dimen.player_popup_controls_padding);
            buttonsPad = res.getDimensionPixelSize(R.dimen.player_popup_buttons_padding);
        } else if (videoPlayerSelected()) {
            buttonsMinWidth = res.getDimensionPixelSize(R.dimen.player_main_buttons_min_width);
            playerTopPad = res.getDimensionPixelSize(R.dimen.player_main_top_padding);
            controlsPad = res.getDimensionPixelSize(R.dimen.player_main_controls_padding);
            buttonsPad = res.getDimensionPixelSize(R.dimen.player_main_buttons_padding);
        } else {
            return;
        }

        binding.topControls.setPaddingRelative(controlsPad, playerTopPad, controlsPad, 0);
        binding.bottomControls.setPaddingRelative(controlsPad, 0, controlsPad, 0);
        binding.qualityTextView.setPadding(buttonsPad, buttonsPad, buttonsPad, buttonsPad);
        binding.playbackSpeed.setPadding(buttonsPad, buttonsPad, buttonsPad, buttonsPad);
        binding.playbackSpeed.setMinimumWidth(buttonsMinWidth);
        binding.captionTextView.setPadding(buttonsPad, buttonsPad, buttonsPad, buttonsPad);
    }

    private void showHideKodiButton() {
        // show kodi button if it supports the current service and it is enabled in settings
        binding.playWithKodi.setVisibility(videoPlayerSelected()
                && playQueue != null && playQueue.getItem() != null
                && KoreUtils.shouldShowPlayWithKodi(context, playQueue.getItem().getServiceId())
                ? View.VISIBLE : View.GONE);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Thumbnail loading
    //////////////////////////////////////////////////////////////////////////*/
    //region Thumbnail loading

    public void updateEndScreenThumbnail() {
        thumbnailController.updateEndScreenThumbnail();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Popup player utils
    //////////////////////////////////////////////////////////////////////////*/
    //region Popup player utils

    public void checkPopupPositionBounds() {
        popupWindowController.checkPopupPositionBounds();
    }

    public void updateScreenSize() {
        popupWindowController.updateScreenSize();
    }

    /**
     * Changes the size of the popup based on the width.
     * @param width the new width, height is calculated with
     *              {@link PlayerHelper#getMinimumVideoHeight(float)}
     */
    public void changePopupSize(final int width) {
        popupWindowController.changePopupSize(width);
    }

    void changePopupWindowFlags(final int flags) {
        popupWindowController.changePopupWindowFlags(flags);
    }

    public void closePopup() {
        popupWindowController.closePopup();
    }

    public void removePopupFromView() {
        popupWindowController.removePopupFromView();
    }

    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback parameters
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback parameters

    public float getPlaybackSpeed() {
        return getPlaybackParameters().speed;
    }

    public void setPlaybackSpeed(final float speed) {
        setPlaybackParameters(speed, getPlaybackPitch(), getPlaybackSkipSilence());
    }

    public float getPlaybackPitch() {
        return getPlaybackParameters().pitch;
    }

    public boolean getPlaybackSkipSilence() {
        return !exoPlayerIsNull() && simpleExoPlayer.getSkipSilenceEnabled();
    }

    public PlayerPlaybackParameters getPlaybackParameters() {
        if (exoPlayerIsNull()) {
            return new PlayerPlaybackParameters(1f, 1f);
        }
        final PlaybackParameters parameters = simpleExoPlayer.getPlaybackParameters();
        return new PlayerPlaybackParameters(parameters.speed, parameters.pitch);
    }

    /**
     * Sets the playback parameters of the player, and also saves them to shared preferences.
     * Speed and pitch are rounded up to 2 decimal places before being used or saved.
     *
     * @param speed       the playback speed, will be rounded to up to 2 decimal places
     * @param pitch       the playback pitch, will be rounded to up to 2 decimal places
     * @param skipSilence skip silence during playback
     */
    public void setPlaybackParameters(final float speed, final float pitch,
                                      final boolean skipSilence) {
        final float roundedSpeed = Math.round(speed * 100.0f) / 100.0f;
        final float roundedPitch = Math.round(pitch * 100.0f) / 100.0f;

        savePlaybackParametersToPrefs(this, roundedSpeed, roundedPitch, skipSilence);
        simpleExoPlayer.setPlaybackParameters(
                new PlaybackParameters(roundedSpeed, roundedPitch));
        simpleExoPlayer.setSkipSilenceEnabled(skipSilence);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Progress loop and updates
    //////////////////////////////////////////////////////////////////////////*/
    //region Progress loop and updates

    public void triggerProgressUpdate() {
        progressController.triggerProgressUpdate();
    }

    public void triggerProgressUpdate(final boolean isRewind) {
        progressController.triggerProgressUpdate(isRewind);
    }

    void triggerProgressUpdate(final boolean isRewind,
                               final boolean isGracedRewind,
                               final boolean bypassSecondaryMode,
                               final boolean isUnSkip) {
        progressController.triggerProgressUpdate(isRewind, isGracedRewind,
                bypassSecondaryMode, isUnSkip);
    }

    @Override // seekbar listener
    public void onProgressChanged(final SeekBar seekBar, final int progress,
                                  final boolean fromUser) {
        progressController.onProgressChanged(seekBar, progress, fromUser);
    }

    @Override // seekbar listener
    public void onStartTrackingTouch(final SeekBar seekBar) {
        progressController.onStartTrackingTouch(seekBar);
    }

    @Override // seekbar listener
    public void onStopTrackingTouch(final SeekBar seekBar) {
        progressController.onStopTrackingTouch(seekBar);
    }

    public void saveWasPlaying() {
        progressController.saveWasPlaying();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Controls showing / hiding
    //////////////////////////////////////////////////////////////////////////*/
    //region Controls showing / hiding

    public boolean isControlsVisible() {
        return controlsVisibilityController.isControlsVisible();
    }

    public void showControlsThenHide() {
        controlsVisibilityController.showControlsThenHide();
    }

    public void showControls(final long duration) {
        controlsVisibilityController.showControls(duration);
    }

    public void hideControls(final long duration, final long delay) {
        controlsVisibilityController.hideControls(duration, delay);
    }

    void showOrHideButtons() {
        controlsVisibilityController.showOrHideButtons();
    }

    void showSystemUIPartially() {
        controlsVisibilityController.showSystemUIPartially();
    }

    void hideSystemUIIfNeeded() {
        controlsVisibilityController.hideSystemUIIfNeeded();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback states
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback states
    void onPlayWhenReadyChanged(final boolean playWhenReady, final int reason) {
        playbackStateController.onPlayWhenReadyChanged(playWhenReady, reason);
    }

    void onPlaybackStateChanged(final int playbackState) {
        playbackStateController.onPlaybackStateChanged(playbackState);
    }

    void onIsLoadingChanged(final boolean isLoading) {
        playbackStateController.onIsLoadingChanged(isLoading);
    }

    void onPlaybackBlock() {
        playbackStateController.onPlaybackBlock();
    }

    void onPlaybackUnblock(final MediaSource mediaSource) {
        playbackStateController.onPlaybackUnblock(mediaSource);
    }

    public void changeState(final PlayerPlaybackState state) {
        playbackStateController.changeState(state);
    }

    void onBuffering() {
        playbackStateController.onBuffering();
    }

    public void startBCPlayer() {
        playbackStateController.startBCPlayer();
    }

    public void pauseBCPlayer() {
        playbackStateController.pauseBCPlayer();
    }

    void startSabrBackoffCountdown() {
        sabrBackoffCountdown.start();
    }

    void stopSabrBackoffCountdown() {
        sabrBackoffCountdown.stop();
    }

    void clearCurrentMediaItems() {
        currentItem = null;
        currentMetadata = null;
    }

    void setCurrentItem(@Nullable final PlayerMediaItem item) {
        currentItem = item;
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Repeat and shuffle
    //////////////////////////////////////////////////////////////////////////*/
    //region Repeat and shuffle

    public void onRepeatClicked() {
        repeatShuffleController.onRepeatClicked();
    }

    public void onShuffleClicked() {
        repeatShuffleController.onShuffleClicked();
    }

    public RepeatMode getRepeatMode() {
        return repeatShuffleController.getRepeatMode();
    }

    public void setRepeatMode(final RepeatMode repeatMode) {
        repeatShuffleController.setRepeatMode(repeatMode);
    }

    void onRepeatModeChanged(final int repeatMode) {
        repeatShuffleController.onRepeatModeChanged(repeatMode);
    }

    void onShuffleModeEnabledChanged(final boolean shuffleModeEnabled) {
        repeatShuffleController.onShuffleModeEnabledChanged(shuffleModeEnabled);
    }

    void onShuffleOrRepeatModeChanged() {
        if (playerMediaSession != null) {
            playerMediaSession.refresh();
        }
        notifyPlaybackUpdateToListeners();
        NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(this, false);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playlist append
    //////////////////////////////////////////////////////////////////////////*/
    //region Playlist append

    public void onAddToPlaylistClicked(@NonNull final FragmentManager fragmentManager) {
        if (DEBUG) {
            Log.d(TAG, "onAddToPlaylistClicked() called");
        }

        if (getPlayQueue() != null) {
            PlaylistDialog.createCorrespondingDialog(
                    getContext(),
                    getPlayQueue()
                            .getStreams()
                            .stream()
                            .map(StreamEntity::new)
                            .collect(Collectors.toList()),
                    dialog -> dialog.show(fragmentManager, TAG)
            );
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Mute / Unmute
    //////////////////////////////////////////////////////////////////////////*/
    //region Mute / Unmute

    public void onMuteUnmuteButtonClicked() {
        if (DEBUG) {
            Log.d(TAG, "onMuteUnmuteButtonClicked() called");
        }
        simpleExoPlayer.setVolume(isMuted() ? 1 : 0);
        notifyPlaybackUpdateToListeners();
        setMuteButton(binding.switchMute, isMuted());
    }

    boolean isMuted() {
        return !exoPlayerIsNull() && simpleExoPlayer.getVolume() == 0;
    }

    private void setMuteButton(@NonNull final ImageButton button, final boolean isMuted) {
        button.setImageDrawable(AppCompatResources.getDrawable(context, isMuted
                ? R.drawable.ic_volume_off : R.drawable.ic_volume_up));
    }
    //endregion

    /*//////////////////////////////////////////////////////////////////////////
    // ExoPlayer listeners (that didn't fit in other categories)
    //////////////////////////////////////////////////////////////////////////*/
    //region ExoPlayer listeners (that didn't fit in other categories)

    /**
     * <p>Listens for event or state changes on ExoPlayer. When any event happens, we check for
     * changes in the currently-playing metadata and update the encapsulating
     * {@link Player}. Downstream listeners are also informed.</p>
     *
     * <p>When the renewed metadata contains any error, it is reported as a notification.
     * This is done because not all source resolution errors are {@link PlaybackException}, which
     * are also captured by {@link ExoPlayer} and stops the playback.</p>
     *
     * @param player The {@link com.google.android.exoplayer2.Player} whose state changed.
     * @param events The {@link com.google.android.exoplayer2.Player.Events} that has triggered
     *               the player state changes.
     **/
    void onEvents(@NonNull final com.google.android.exoplayer2.Player player,
                  @NonNull final com.google.android.exoplayer2.Player.Events events) {
        ExoMediaItems.fromMediaItem(player.getCurrentMediaItem()).ifPresent(tag -> {
            if (tag == currentMetadata) {
                return; // we still have the same metadata, no need to do anything
            }
            final StreamInfo previousInfo = Optional.ofNullable(currentMetadata)
                    .flatMap(PlayerMediaItem::getMaybeStreamInfo).orElse(null);
            currentMetadata = tag;

            if (!currentMetadata.getErrors().isEmpty()) {
                // new errors might have been added even if previousInfo == tag.getMaybeStreamInfo()
                final ErrorInfo errorInfo = new ErrorInfo(
                        currentMetadata.getErrors(),
                        UserAction.PLAY_STREAM,
                        "Loading failed for [" + currentMetadata.getTitle()
                                + "]: " + currentMetadata.getUrl(),
                        currentMetadata.getServiceId());
                ErrorUtil.createNotification(context, errorInfo);
            }

            currentMetadata.getMaybeStreamInfo().ifPresent(info -> {
                if (DEBUG) {
                    Log.d(TAG, "ExoPlayer - onEvents() update stream info: " + info.getName());
                }
                if (previousInfo == null || !previousInfo.getUrl().equals(info.getUrl())) {
                    // only update with the new stream info if it has actually changed
                    updateMetadataWith(info);
                }
            });
        });
    }

    void onTracksChanged(@NonNull final Tracks tracks) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onTracksChanged(), "
                    + "track group size = " + tracks.getGroups().size());
        }
        autoQueueController.cancelEnqueueTimer();
        tracksController.onTextTracksChanged(tracks);
        tracksController.onAudioTracksChanged();
    }

    void onPlaybackParametersChanged(@NonNull final PlaybackParameters playbackParameters) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - playbackParameters(), speed = [" + playbackParameters.speed
                    + "], pitch = [" + playbackParameters.pitch + "]");
        }
        binding.playbackSpeed.setText(formatSpeed(playbackParameters.speed));
    }

    void onPositionDiscontinuity(@NonNull final PositionInfo oldPosition,
                                 @NonNull final PositionInfo newPosition,
                                 @DiscontinuityReason final int discontinuityReason) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onPositionDiscontinuity() called with "
                    + "oldPositionIndex = [" + oldPosition.mediaItemIndex + "], "
                    + "oldPositionMs = [" + oldPosition.positionMs + "], "
                    + "newPositionIndex = [" + newPosition.mediaItemIndex + "], "
                    + "newPositionMs = [" + newPosition.positionMs + "], "
                    + "discontinuityReason = [" + discontinuityReason + "]");
        }
        if (playQueue == null) {
            return;
        }

        // Refresh the playback if there is a transition to the next video
        final int newIndex = newPosition.mediaItemIndex;
        switch (discontinuityReason) {
            case DISCONTINUITY_REASON_AUTO_TRANSITION:
            case DISCONTINUITY_REASON_REMOVE:
                // When player is in single repeat mode and a period transition occurs,
                // we need to register a view count here since no metadata has changed
                if (getRepeatMode() == RepeatMode.ONE && newIndex == playQueue.getIndex()) {
                    registerStreamViewed();
                    break;
                }
            case DISCONTINUITY_REASON_SEEK:
                if (DEBUG) {
                    Log.d(TAG, "ExoPlayer - onSeekProcessed() called");
                }
                if (isPrepared) {
                    saveStreamProgressState();
                }
            case DISCONTINUITY_REASON_SEEK_ADJUSTMENT:
            case DISCONTINUITY_REASON_INTERNAL:
                // Player index may be invalid when playback is blocked
                if (!getCurrentState().isBlocked() && newIndex != playQueue.getIndex()) {
                    saveStreamProgressStateCompleted(); // current stream has ended
                    playQueue.setIndex(newIndex);
                }
                break;
            case DISCONTINUITY_REASON_SKIP:
                break; // only makes Android Studio linter happy, as there are no ads
        }
    }

    void onRenderedFirstFrame() {
        PlaybackStartupTrace.finish(startupTraceId);
        //TODO check if this causes black screen when switching to fullscreen
        animate(binding.surfaceForeground, false, DEFAULT_CONTROLS_DURATION);
    }

    void onCues(@NonNull final CueGroup cueGroup) {
        binding.subtitleView.setCues(cueGroup.cues);
    }

    public void onPrepare() {
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.prepare();
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Errors
    //////////////////////////////////////////////////////////////////////////*/
    //region Errors

    /**
     * Process exceptions produced by ExoPlayer. The playback error policy lives in
     * {@link PlayerErrorHandler}.
     */
    void onPlayerError(@NonNull final PlaybackException error) {
        playerErrorHandler.onPlayerError(error);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback position and seek
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback position and seek

    /**
     * Sets the current duration into the corresponding elements.
     * @param currentProgress
     */
    void updatePlayBackElementsCurrentDuration(final int currentProgress) {
        transportController.updatePlayBackElementsCurrentDuration(currentProgress);
    }

    boolean isApproachingPlaybackEdge(final long timeToEndMillis) {
        return transportController.isApproachingPlaybackEdge(timeToEndMillis);
    }

    /**
     * Checks if the current playback is a livestream AND is playing at or beyond the live edge.
     *
     * @return whether the livestream is playing at or beyond the edge
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isLiveEdge() {
        return transportController.isLiveEdge();
    }

    void onPlaybackSynchronize(@NonNull final PlayerMediaItem item, final boolean wasBlocked) {
        transportController.onPlaybackSynchronize(item, wasBlocked);
    }

    public boolean shouldSeek() {
        return !prefs.getBoolean(context.getString(R.string.always_start_from_beginning_key), false);
    }

    boolean isCurrentStreamSabr() {
        return getCurrentStreamInfo().map(info -> {
            for (final VideoStream s : info.getVideoOnlyStreams()) {
                if (s.getDeliveryMethod() == DeliveryMethod.SABR) {
                    return true;
                }
            }
            for (final VideoStream s : info.getVideoStreams()) {
                if (s.getDeliveryMethod() == DeliveryMethod.SABR) {
                    return true;
                }
            }
            for (final AudioStream s : info.getAudioStreams()) {
                if (s.getDeliveryMethod() == DeliveryMethod.SABR) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }

    public void seekTo(final long positionMillis) {
        transportController.seekTo(positionMillis);
    }

    public void seekToDefault() {
        transportController.seekToDefault();
    }

    /**
     * Sets the video duration time into all control components (e.g. seekbar).
     *
     * @param duration
     */
    void setVideoDurationToControls(final int duration) {
        transportController.setVideoDurationToControls(duration);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Player actions (play, pause, previous, fast-forward, ...)
    //////////////////////////////////////////////////////////////////////////*/
    //region Player actions (play, pause, previous, fast-forward, ...)

    public void play() {
        transportController.play();
    }

    public void pause() {
        transportController.pause();
    }

    public void playPause() {
        transportController.playPause();
    }

    public void playPrevious() {
        transportController.playPrevious();
    }

    public void playNext() {
        transportController.playNext();
    }

    public void fastForward() {
        transportController.fastForward();
    }

    public void fastRewind() {
        transportController.fastRewind();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // StreamInfo history: views and progress
    //////////////////////////////////////////////////////////////////////////*/
    //region StreamInfo history: views and progress

    private void registerStreamViewed() {
        historyController.registerStreamViewed();
    }

    public void saveStreamProgressState() {
        historyController.saveStreamProgressState();
    }

    public void saveStreamProgressStateCompleted() {
        historyController.saveStreamProgressStateCompleted();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Metadata
    //////////////////////////////////////////////////////////////////////////*/
    //region Metadata

    private void onMetadataChanged(@NonNull final StreamInfo info) {
        if (DEBUG) {
            Log.d(TAG, "Playback - onMetadataChanged() called, playing: " + info.getName());
        }

        // Zoom belongs to the current video, matching the transient behavior of the official app.
        gestureController.resetPinchZoom();
        menuController.resetDisplayModeForNewVideo();

        thumbnailController.initThumbnail(info.getThumbnailUrl());
        registerStreamViewed();
        updateStreamRelatedViews();
        showHideKodiButton();
        // TODO: bullet comments may be reset unexpectedly for round play streams
        bulletCommentsController.init();
        bulletCommentsController.start();

        binding.titleTextView.setText(info.getName());
        binding.channelTextView.setText(info.getUploaderName());

        progressController.resetPreviewThumbnails(info.getPreviewFrames());

        NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(this, false);

        mediaSessionManager.setPlayer(this);

        notifyMetadataUpdateToListeners();

        tracksController.onAudioTracksChanged();

        queueController.onMetadataChanged(info);

        onMarkSeekbarRequested(info);
    }

    private void updateMetadataWith(@NonNull final StreamInfo streamInfo) {
        if (exoPlayerIsNull()) {
            return;
        }

        autoQueueController.maybeAutoQueueNextStream(streamInfo, false);
        onMetadataChanged(streamInfo);
        NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(this, true);
    }

    @NonNull
    private String getVideoUrl() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getUrl();
    }

    @NonNull
    private String getVideoUrlAtCurrentTime() {
        final int timeSeconds = binding.playbackSeekBar.getProgress() / 1000;
        String videoUrl = getVideoUrl();
        if (!isLive() && timeSeconds >= 0 && currentMetadata != null
                && currentMetadata.getServiceId() == YouTube.getServiceId()) {
            // Timestamp doesn't make sense in a live stream so drop it
            videoUrl += ("&t=" + timeSeconds);
        }
        return videoUrl;
    }

    @NonNull
    public String getVideoTitle() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getTitle();
    }

    @NonNull
    public String getUploaderName() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getUploaderName();
    }

    @Nullable
    public Bitmap getThumbnail() {
        return thumbnailController.getThumbnail();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Play queue, segments and streams
    //////////////////////////////////////////////////////////////////////////*/
    //region Play queue, segments and streams

    public void selectQueueItem(final PlayerMediaItem item) {
        if (playQueue == null || exoPlayerIsNull()) {
            return;
        }

        final int index = playQueue.indexOf(item);
        if (index == -1) {
            return;
        }

        if (playQueue.getIndex() == index && simpleExoPlayer.getCurrentMediaItemIndex() == index) {
            seekToDefault();
        } else {
            saveStreamProgressState();
        }
        playQueue.setIndex(index);
    }

    void onPlayQueueEdited() {
        notifyPlaybackUpdateToListeners();
        showOrHideButtons();
        NotificationUtil.getInstance().createNotificationIfNeededAndUpdate(this, false);
    }

    public void closeItemsList() {
        queueController.closeItemsList();
    }

    int getNearestStreamSegmentPosition(final long playbackPosition) {
        return queueController.getNearestStreamSegmentPosition(playbackPosition);
    }

    @Nullable
    MediaSource sourceOf(final PlayerMediaItem item, final StreamInfo info) {
        final long recoveryPosition = playQueue == null
                ? PlayQueue.RECOVERY_UNSET : playQueue.getRecoveryPosition(item);
        final long initialPositionMs = shouldSeek()
                && recoveryPosition != PlayQueue.RECOVERY_UNSET
                ? recoveryPosition : 0;
        return sourceResolver.resolve(playerType, isAudioOnly, info, initialPositionMs,
                startupTraceId);
    }

    public void disablePreloadingOfCurrentTrack() {
        loadController.disablePreloadingOfCurrentTrack();
    }

    @Nullable
    public VideoStream getSelectedVideoStream() {
        return (selectedStreamIndex >= 0 && availableStreams != null
                && availableStreams.size() > selectedStreamIndex)
                ? availableStreams.get(selectedStreamIndex) : null;
    }

    void updateStreamRelatedViews() {
        if (!getCurrentStreamInfo().isPresent()) {
            return;
        }
        final StreamInfo info = getCurrentStreamInfo().get();

        binding.qualityTextView.setVisibility(View.GONE);
        binding.playbackSpeed.setVisibility(View.GONE);

        binding.playbackEndTime.setVisibility(View.GONE);
        binding.playbackLiveSync.setVisibility(View.GONE);

        switch (info.getStreamType()) {
            case AUDIO_STREAM:
                binding.surfaceView.setVisibility(View.GONE);
                binding.endScreen.setVisibility(View.VISIBLE);
                binding.playbackEndTime.setVisibility(View.VISIBLE);
                break;

            case AUDIO_LIVE_STREAM:
                binding.surfaceView.setVisibility(View.GONE);
                binding.endScreen.setVisibility(View.VISIBLE);
                binding.playbackLiveSync.setVisibility(View.VISIBLE);
                break;

            case LIVE_STREAM:
                binding.surfaceView.setVisibility(View.VISIBLE);
                binding.endScreen.setVisibility(View.GONE);
                binding.playbackLiveSync.setVisibility(View.VISIBLE);
                break;

            case VIDEO_STREAM:
            case POST_LIVE_STREAM:
                if (currentMetadata == null
                        || !currentMetadata.getMaybeQuality().isPresent()
                        || (info.getVideoStreams().isEmpty()
                        && info.getVideoOnlyStreams().isEmpty())) {
                    break;
                }

                availableStreams = currentMetadata.getMaybeQuality().get().getSortedVideoStreams();
                selectedStreamIndex =
                        currentMetadata.getMaybeQuality().get().getSelectedVideoStreamIndex();
                menuController.buildQualityMenu();

                binding.qualityTextView.setVisibility(View.VISIBLE);
                binding.surfaceView.setVisibility(View.VISIBLE);
            default:
                binding.endScreen.setVisibility(View.GONE);
                binding.playbackEndTime.setVisibility(View.VISIBLE);
                break;
        }

        menuController.buildPlaybackSpeedMenu();
        binding.playbackSpeed.setVisibility(View.VISIBLE);
    }

    void updateQueueTime(final int currentTime) {
        queueController.updateQueueTime(currentTime);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Popup menus
    //////////////////////////////////////////////////////////////////////////*/
    //region Popup menus

    void setSelectedStream(@NonNull final VideoStream stream) {
        sourceResolver.setSelectedStream(stream);
    }

    void closeAllPopupMenus() {
        if (menuController != null) {
            menuController.closeAllPopupMenus();
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Captions (text tracks)
    //////////////////////////////////////////////////////////////////////////*/
    //region Captions (text tracks)

    void setAudioTrack(@Nullable final String audioTrackId) {
        tracksController.setAudioTrack(audioTrackId);
    }

    int getCaptionRendererIndex() {
        return tracksController.getCaptionRendererIndex();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Click listeners
    //////////////////////////////////////////////////////////////////////////*/
    //region Click listeners

    @Override
    public void onClick(final View v) {
        if (DEBUG) {
            Log.d(TAG, "onClick() called with: v = [" + v + "]");
        }
        if (v.getId() == binding.resizeTextView.getId()) {
            menuController.onDisplayModeClicked();
        } else if (v.getId() == binding.captionTextView.getId()) {
            menuController.onCaptionClicked();
        } else if (v.getId() == binding.audioTrackTextView.getId()) {
            menuController.onAudioTrackClicked();
        } else if (v.getId() == binding.playbackLiveSync.getId()) {
            seekToDefault();
        } else if (v.getId() == binding.playPauseButton.getId()) {
            playPause();
        } else if (v.getId() == binding.playPreviousButton.getId()) {
            playPrevious();
        } else if (v.getId() == binding.playNextButton.getId()) {
            playNext();
        } else if (v.getId() == binding.moreOptionsButton.getId()) {
            onMoreOptionsClicked();
        } else if (v.getId() == binding.share.getId()) {
            ShareUtils.shareText(context, getVideoTitle(), getVideoUrlAtCurrentTime(),
                    currentItem.getThumbnailUrl());
        } else if (v.getId() == binding.switchCommentsVisibility.getId()) {
            bulletCommentsController.toggleVisibility();
        } else if (v.getId() == binding.playWithKodi.getId()) {
            onPlayWithKodiClicked();
        } else if (v.getId() == binding.openInBrowser.getId()) {
            onOpenInBrowserClicked();
        } else if (v.getId() == binding.sleepTimer.getId()) {
            onSleepTimerClicked();
        } else if (v.getId() == binding.fullScreenButton.getId()) {
            setRecovery();
            if (popupPlayerSelected()) {
                // Clean up popup properly before switching to main player
                service.stopService();
            }
            NavigationHelper.playOnMainPlayer(context, playQueue, true);
            return;
        } else if (v.getId() == binding.screenRotationButton.getId()) {
            PlayerUiModeHelper.setFullscreen(this, !isFullscreen);
        } else if (v.getId() == binding.switchMute.getId()) {
            onMuteUnmuteButtonClicked();
        } else if (v.getId() == binding.playerCloseButton.getId()) {
            context.sendBroadcast(new Intent(VideoDetailFragment.ACTION_HIDE_MAIN_PLAYER));
            service.stopService();
        } else if (v.getId() == binding.skipButton.getId()) {
            sponsorBlockController.onSkipClicked();
        } else if (v.getId() == binding.unskipButton.getId()) {
            sponsorBlockController.onUnskipClicked();
        }

        manageControlsAfterOnClick(v);
    }

    /**
     * Manages the controls after a click occurred on the player UI.
     * @param v - The view that was clicked
     */
    public void manageControlsAfterOnClick(@NonNull final View v) {
        controlsVisibilityController.manageControlsAfterOnClick(v);
    }

    @Override
    public boolean onLongClick(final View v) {
        if (v.getId() == binding.moreOptionsButton.getId() && isFullscreen) {
            listeners.onMoreOptionsLongClicked();
            hideControls(0, 0);
            hideSystemUIIfNeeded();
        } else if (v.getId() == binding.share.getId()) {
            ShareUtils.copyToClipboard(context, getVideoUrlAtCurrentTime());
        } else if (v.getId() == binding.sleepTimer.getId()) {
            onSleepTimerLongClicked();
        }
        return true;
    }

    public boolean onKeyDown(final int keyCode) {
        return gestureController.onKeyDown(keyCode);
    }

    private void onMoreOptionsClicked() {
        if (DEBUG) {
            Log.d(TAG, "onMoreOptionsClicked() called");
        }

        final boolean isMoreControlsVisible =
                binding.secondaryControls.getVisibility() == View.VISIBLE;

        animateRotation(binding.moreOptionsButton, DEFAULT_CONTROLS_DURATION,
                isMoreControlsVisible ? 0 : 180);
        animate(binding.secondaryControls, !isMoreControlsVisible, DEFAULT_CONTROLS_DURATION,
                AnimationType.SLIDE_AND_ALPHA, 0, () -> {
                    // Fix for a ripple effect on background drawable.
                    // When view returns from GONE state it takes more milliseconds than returning
                    // from INVISIBLE state. And the delay makes ripple background end to fast
                    if (isMoreControlsVisible) {
                        binding.secondaryControls.setVisibility(View.INVISIBLE);
                    }
                });
        showControls(DEFAULT_CONTROLS_DURATION);
    }

    private void onPlayWithKodiClicked() {
        if (currentMetadata != null) {
            pause();
            try {
                NavigationHelper.playWithKore(context, Uri.parse(getVideoUrl()));
            } catch (final Exception e) {
                if (DEBUG) {
                    Log.i(TAG, "Failed to start kore", e);
                }
                KoreUtils.showInstallKoreDialog(getParentActivity());
            }
        }
    }

    private void onOpenInBrowserClicked() {
        getCurrentStreamInfo()
                .map(Info::getOriginalUrl)
                .ifPresent(originalUrl -> ShareUtils.openUrlInBrowser(
                        Objects.requireNonNull(getParentActivity()), originalUrl));
    }

    private void onSleepTimerClicked() {
        AppCompatActivity activity = getParentActivity();
        assert activity != null;
        Intent serviceIntent = new Intent(activity, SleepTimerService.class);
        serviceIntent.setAction(SleepTimerService.ACTION_START_TIMER);
        // get time from shared preferences
        int time = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(activity).getString(
                activity.getString(R.string.sleep_timer_length_key), String.valueOf(15)
        ));
        serviceIntent.putExtra("timeInMillis", time * 60000); // 60 seconds
        activity.startService(serviceIntent);
        binding.sleepTimer.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_timer));
    }

    private void onSleepTimerLongClicked() {
        AppCompatActivity activity = getParentActivity();
        assert activity != null;
        Intent serviceIntent = new Intent(activity, SleepTimerService.class);
        serviceIntent.setAction(SleepTimerService.ACTION_STOP_TIMER);
        activity.startService(serviceIntent);
        binding.sleepTimer.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_timer_off));
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Video size, resize, orientation, fullscreen
    //////////////////////////////////////////////////////////////////////////*/
    //region Video size, resize, orientation, fullscreen

    void setFullscreen(final boolean fullscreen) {
        if (DEBUG) {
            Log.d(TAG, "setFullscreen() called with: fullscreen = [" + fullscreen + "]");
        }
        if (isFullscreen == fullscreen
                || popupPlayerSelected()
                || exoPlayerIsNull()
                || !listeners.hasFragmentListener()) {
            return;
        }

        isFullscreen = fullscreen;
        // Pinch zoom is fullscreen-only and never survives either direction of the transition.
        gestureController.resetPinchZoom();
        if (!isFullscreen) {
            // Apply window insets because Android will not do it when orientation changes
            // from landscape to portrait (open vertical video to reproduce)
            binding.playbackControlRoot.setPadding(0, 0, 0, 0);
        } else {
            // Hide the controls while Android calculates the new window insets.
            hideControls(0, 0);
        }
        listeners.onFullscreenStateChanged(isFullscreen);

        if (isFullscreen) {
            binding.titleTextView.setVisibility(View.VISIBLE);
            binding.channelTextView.setVisibility(View.VISIBLE);
            binding.playerCloseButton.setVisibility(View.GONE);
            binding.sleepTimer.setVisibility(View.VISIBLE);
        } else {
            binding.titleTextView.setVisibility(View.GONE);
            binding.channelTextView.setVisibility(View.GONE);
            binding.playerCloseButton.setVisibility(
                    videoPlayerSelected() ? View.VISIBLE : View.GONE);
            binding.sleepTimer.setVisibility(View.GONE);
        }
        setupScreenRotationButton();
    }

    private void setupScreenRotationButton() {
        binding.screenRotationButton.setVisibility(
                videoPlayerSelected() ? View.VISIBLE : View.GONE);
        binding.screenRotationButton.setImageDrawable(AppCompatResources.getDrawable(context,
                isFullscreen ? R.drawable.ic_fullscreen_exit
                : R.drawable.ic_fullscreen));
    }

    void setResizeMode(@AspectRatioFrameLayout.ResizeMode final int resizeMode) {
        menuController.setResizeMode(resizeMode);
    }

    void onVideoSizeChanged(@NonNull final VideoSize videoSize) {
        if (DEBUG) {
            Log.d(TAG, "onVideoSizeChanged() called with: "
                    + "width / height = [" + videoSize.width + " / " + videoSize.height
                    + " = " + (((float) videoSize.width) / videoSize.height) + "], "
                    + "unappliedRotationDegrees = [" + videoSize.unappliedRotationDegrees + "], "
                    + "pixelWidthHeightRatio = [" + videoSize.pixelWidthHeightRatio + "]");
        }

        menuController.onVideoSizeChanged(videoSize.width, videoSize.height);
        isVerticalVideo = videoSize.width < videoSize.height;

        if (isFullscreen) {
            PlayerUiModeHelper.applyVideoOrientation(this);
        }

        setupScreenRotationButton();
    }

    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Gestures
    //////////////////////////////////////////////////////////////////////////*/
    //region Gestures

    public boolean isInsideClosingRadius(@NonNull final MotionEvent popupMotionEvent) {
        return popupWindowController.isInsideClosingRadius(popupMotionEvent);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Activity / fragment binding
    //////////////////////////////////////////////////////////////////////////*/
    //region Activity / fragment binding

    public void setFragmentListener(final PlayerServiceEventListener listener) {
        listeners.setFragmentListener(listener);
    }

    public void removeFragmentListener(final PlayerServiceEventListener listener) {
        listeners.removeFragmentListener(listener);
    }

    void setActivityListener(final PlayerEventListener listener) {
        listeners.setActivityListener(listener);
    }

    void removeActivityListener(final PlayerEventListener listener) {
        listeners.removeActivityListener(listener);
    }

    void stopActivityBinding() {
        listeners.stopActivityBinding();
    }

    /**
     * This will be called when a user goes to another app/activity, turns off a screen.
     * We don't want to interrupt playback and don't want to see notification so
     * next lines of code will enable audio-only playback only if needed
     */
    void onFragmentStopped() {
        if (videoPlayerSelected() && (isPlaying() || isLoading())) {
            switch (getMinimizeOnExitAction(context)) {
                case MINIMIZE_ON_EXIT_MODE_BACKGROUND:
                    useVideoSource(false);
                    break;
                case MINIMIZE_ON_EXIT_MODE_POPUP:
                    setRecovery();
                    NavigationHelper.playOnPopupPlayer(context, playQueue, true);
                    break;
                case MINIMIZE_ON_EXIT_MODE_NONE: default:
                    pause();
                    break;
            }
        }
    }

    void notifyQueueUpdateToListeners() {
        listeners.notifyQueueUpdateToListeners();
    }

    void notifyMetadataUpdateToListeners() {
        listeners.notifyMetadataUpdateToListeners();
    }

    void notifyPlaybackUpdateToListeners() {
        listeners.notifyPlaybackUpdateToListeners();
    }

    void notifyProgressUpdateToListeners(final int currentProgress,
                                         final int duration,
                                         final int bufferPercent) {
        listeners.notifyProgressUpdateToListeners(currentProgress, duration, bufferPercent);
    }

    @Nullable
    public AppCompatActivity getParentActivity() {
        // ! instanceof ViewGroup means that view was added via windowManager for Popup
        if (binding == null || !(binding.getRoot().getParent() instanceof ViewGroup)) {
            return null;
        }

        return (AppCompatActivity) ((ViewGroup) binding.getRoot().getParent()).getContext();
    }

    void useVideoSource(final boolean videoEnabled) {
        if (playQueue == null || isAudioOnly == !videoEnabled || audioPlayerSelected()) {
            return;
        }

        isAudioOnly = !videoEnabled;
        // When a user returns from background, controls could be hidden but SystemUI will be shown
        // 100%. Hide it.
        if (!isAudioOnly && !isControlsVisible()) {
            hideSystemUIIfNeeded();
        }

        // The current metadata may be null sometimes (for e.g. when using an unstable connection
        // in livestreams) so we will be not able to execute the block below.
        // Reload the play queue manager in this case, which is the behavior when we don't know the
        // index of the video renderer or playQueueManagerReloadingNeeded returns true.
        final Optional<StreamInfo> optCurrentStreamInfo = getCurrentStreamInfo();
        if (!optCurrentStreamInfo.isPresent()) {
            reloadPlayQueueManager();
            setRecovery();
            return;
        }

        final StreamInfo info = optCurrentStreamInfo.get();

        // In the case we don't know the source type, fallback to the one with video with audio or
        // audio-only source.
        final SourceType sourceType = sourceResolver.getStreamSourceType().orElse(
                SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY);

        // A SABR source already exposes both audio and video, so background / foreground video
        // toggles only need to update Media3 track selection instead of rebuilding the source.
        if (!isCurrentStreamSabr()
                && playQueueManagerReloadingNeeded(sourceType, info, getVideoRendererIndex())) {
            reloadPlayQueueManager();
        } else {
            final StreamType streamType = info.getStreamType();
            if (streamType == StreamType.AUDIO_STREAM
                    || streamType == StreamType.AUDIO_LIVE_STREAM) {
                // Nothing to do more than setting the recovery position
                setRecovery();
                return;
            }

            final DefaultTrackSelector.Parameters.Builder parametersBuilder =
                    trackSelector.buildUponParameters();

            // Enable/disable the video track and the ability to select subtitles
            parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !videoEnabled);
            parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoEnabled);

            trackSelector.setParameters(parametersBuilder);
        }

        setRecovery();
    }

    /**
     * Return whether the play queue manager needs to be reloaded when switching player type.
     *
     * <p>
     * The play queue manager needs to be reloaded if the video renderer index is not known and if
     * the content is not an audio content, but also if none of the following cases is met:
     *
     * <ul>
     *     <li>the content is an {@link StreamType#AUDIO_STREAM audio stream} or an
     *     {@link StreamType#AUDIO_LIVE_STREAM audio live stream};</li>
     *     <li>the content is a {@link StreamType#LIVE_STREAM live stream} and the source type is a
     *     {@link SourceType#LIVE_STREAM live source};</li>
     *     <li>the content's source is {@link SourceType#VIDEO_WITH_SEPARATED_AUDIO a video stream
     *     with a separated audio source} or has no audio-only streams available <b>and</b> is a
     *     {@link StreamType#LIVE_STREAM live stream} or a
     *     {@link StreamType#LIVE_STREAM live stream}.
     *     </li>
     * </ul>
     * </p>
     *
     * @param sourceType         the {@link SourceType} of the stream
     * @param streamInfo         the {@link StreamInfo} of the stream
     * @param videoRendererIndex the video renderer index of the video source, if that's a video
     *                           source (or {@link #RENDERER_UNAVAILABLE})
     * @return whether the play queue manager needs to be reloaded
     */
    private boolean playQueueManagerReloadingNeeded(final SourceType sourceType,
                                                    @NonNull final StreamInfo streamInfo,
                                                    final int videoRendererIndex) {
        final StreamType streamType = streamInfo.getStreamType();

        if (videoRendererIndex == RENDERER_UNAVAILABLE && streamType != StreamType.AUDIO_STREAM
                && streamType != StreamType.AUDIO_LIVE_STREAM) {
            return true;
        }

        // The content is an audio stream, an audio live stream, or a live stream with a live
        // source: it's not needed to reload the play queue manager because the stream source will
        // be the same
        if ((streamType == StreamType.AUDIO_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM)
                || (streamType == StreamType.LIVE_STREAM
                && sourceType == SourceType.LIVE_STREAM)) {
            return false;
        }

        // The content's source is a video with separated audio or a video with audio -> the video
        // and its fetch may be disabled
        // The content's source is a video with embedded audio and the content has no separated
        // audio stream available: it's probably not needed to reload the play queue manager
        // because the stream source will be probably the same as the current played
        if (sourceType == SourceType.VIDEO_WITH_SEPARATED_AUDIO
                || (sourceType == SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
                    && isNullOrEmpty(streamInfo.getAudioStreams()))) {
            // It's not needed to reload the play queue manager only if the content's stream type
            // is a video stream or a live stream
            return streamType != StreamType.VIDEO_STREAM && streamType != StreamType.LIVE_STREAM;
        }

        // Other cases: the play queue manager reload is needed
        return true;
    }
    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Getters
    //////////////////////////////////////////////////////////////////////////*/
    //region Getters

    public Optional<StreamInfo> getCurrentStreamInfo() {
        return Optional.ofNullable(currentMetadata).flatMap(PlayerMediaItem::getMaybeStreamInfo);
    }

    public PlayerPlaybackState getCurrentState() {
        return currentState;
    }

    void setCurrentState(final PlayerPlaybackState state) {
        currentState = state;
    }

    public boolean exoPlayerIsNull() {
        return simpleExoPlayer == null;
    }

    public boolean isStopped() {
        return exoPlayerIsNull() || simpleExoPlayer.getPlaybackState() == ExoPlayer.STATE_IDLE;
    }

    public boolean isPlaying() {
        return !exoPlayerIsNull() && simpleExoPlayer.isPlaying();
    }

    public boolean getPlayWhenReady() {
        return !exoPlayerIsNull() && simpleExoPlayer.getPlayWhenReady();
    }

    boolean isLoading() {
        return !exoPlayerIsNull() && simpleExoPlayer.isLoading();
    }

    boolean isLive() {
        try {
            return !exoPlayerIsNull() && simpleExoPlayer.isCurrentMediaItemDynamic();
        } catch (final IndexOutOfBoundsException e) {
            // Why would this even happen =(... but lets log it anyway, better safe than sorry
            if (DEBUG) {
                Log.d(TAG, "player.isCurrentWindowDynamic() failed: ", e);
            }
            return false;
        }
    }


    @NonNull
    public Context getContext() {
        return context;
    }

    @NonNull
    public SharedPreferences getPrefs() {
        return prefs;
    }

    @Nullable
    public MediaSessionManager getMediaSessionManager() {
        return mediaSessionManager;
    }


    public PlayerType getPlayerType() {
        return playerType;
    }

    public boolean audioPlayerSelected() {
        return playerType == PlayerType.AUDIO;
    }

    public boolean videoPlayerSelected() {
        return playerType == PlayerType.VIDEO;
    }

    public boolean popupPlayerSelected() {
        return playerType == PlayerType.POPUP;
    }


    @Nullable
    public PlayQueue getPlayQueue() {
        return playQueue;
    }

    public AudioReactor getAudioReactor() {
        return audioReactor;
    }

    public boolean isFullscreen() {
        return isFullscreen;
    }

    public boolean isVerticalVideo() {
        return isVerticalVideo;
    }

    public boolean isPopupClosing() {
        return popupWindowController.isPopupClosing();
    }


    public boolean isSomePopupMenuVisible() {
        return menuController.isSomePopupMenuVisible();
    }

    public ImageButton getPlayPauseButton() {
        return binding.playPauseButton;
    }

    public View getClosingOverlayView() {
        return binding.closingOverlay;
    }

    public FloatingActionButton getCloseOverlayButton() {
        return popupWindowController.getCloseOverlayButton();
    }

    public View getLoadingPanel() {
        return binding.loadingPanel;
    }

    public TextView getCurrentDisplaySeek() {
        return binding.currentDisplaySeek;
    }

    @Nullable
    public WindowManager.LayoutParams getPopupLayoutParams() {
        return popupWindowController.getPopupLayoutParams();
    }

    @Nullable
    public WindowManager getWindowManager() {
        return popupWindowController.getWindowManager();
    }

    public float getScreenWidth() {
        return popupWindowController.getScreenWidth();
    }

    public float getScreenHeight() {
        return popupWindowController.getScreenHeight();
    }

    public View getRootView() {
        return binding.getRoot();
    }

    public ExpandableSurfaceView getSurfaceView() {
        return binding.surfaceView;
    }

    public PlayQueueAdapter getPlayQueueAdapter() {
        return queueController.getPlayQueueAdapter();
    }

    public PlayerBinding getBinding() {
        return binding;
    }

    @Nullable
    List<VideoStream> getAvailableStreams() {
        return availableStreams;
    }

    int getSelectedStreamIndex() {
        return selectedStreamIndex;
    }

    @NonNull
    DefaultTrackSelector getTrackSelector() {
        return trackSelector;
    }

    @NonNull
    PlayerMenuController getMenuController() {
        return menuController;
    }

    @NonNull
    public PlayerGestureController getGestureController() {
        return gestureController;
    }

    @NonNull
    PlayerControlsVisibilityController getControlsVisibilityController() {
        return controlsVisibilityController;
    }

    @NonNull
    AutoQueueController getAutoQueueController() {
        return autoQueueController;
    }

    @NonNull
    PlayerQueueController getQueueController() {
        return queueController;
    }

    @NonNull
    RepeatShuffleController getRepeatShuffleController() {
        return repeatShuffleController;
    }

    @NonNull
    PlayerHistoryController getHistoryController() {
        return historyController;
    }

    void setPlayerType(final PlayerType type) {
        playerType = type;
    }

    boolean isAudioOnly() {
        return isAudioOnly;
    }

    void setAudioOnly(final boolean audioOnly) {
        isAudioOnly = audioOnly;
    }

    void setStartupTraceId(final long id) {
        startupTraceId = id;
    }

    void setPlayQueue(@Nullable final PlayQueue queue) {
        playQueue = queue;
    }

    @NonNull
    SourceResolver getSourceResolver() {
        return sourceResolver;
    }

    @NonNull
    PlayerListeners getListeners() {
        return listeners;
    }

    @NonNull
    PlayerProgressController getProgressController() {
        return progressController;
    }

    @NonNull
    BulletCommentsController getBulletCommentsController() {
        return bulletCommentsController;
    }

    long getStartupTraceId() {
        return startupTraceId;
    }

    @NonNull
    SponsorBlockController getSponsorBlockController() {
        return sponsorBlockController;
    }

    @NonNull
    StreamSegmentAdapter getSegmentAdapter() {
        return queueController.getSegmentAdapter();
    }

    boolean isQueueVisible() {
        return queueController.isQueueVisible();
    }

    boolean areSegmentsVisible() {
        return queueController.areSegmentsVisible();
    }

    boolean isPrepared() {
        return isPrepared;
    }

    void setPrepared(final boolean prepared) {
        isPrepared = prepared;
    }

    @Nullable
    PlayerMediaItem getCurrentItem() {
        return currentItem;
    }

    @Nullable
    PlayerMediaItem getCurrentMetadata() {
        return currentMetadata;
    }

    public long getCurrentPosition() {
        return exoPlayerIsNull() ? 0 : simpleExoPlayer.getCurrentPosition();
    }

    public long getDuration() {
        return exoPlayerIsNull() ? 0 : simpleExoPlayer.getDuration();
    }

    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // SponsorBlock
    //////////////////////////////////////////////////////////////////////////*/
    //region

    public SponsorBlockMode getSponsorBlockMode() {
        return sponsorBlockController.getMode();
    }

    public void setSponsorBlockMode(final SponsorBlockMode mode) {
        sponsorBlockController.setMode(mode);
    }

    public void onMarkSeekbarRequested(@NonNull final StreamInfo streamInfo) {
        sponsorBlockController.markSeekbarSegments(streamInfo);
    }
    //endregion

    /**
     * Get the video renderer index of the current playing stream.
     *
     * This method returns the video renderer index of the current
     * {@link MappingTrackSelector.MappedTrackInfo} or {@link #RENDERER_UNAVAILABLE} if the current
     * {@link MappingTrackSelector.MappedTrackInfo} is null or if there is no video renderer index.
     *
     * @return the video renderer index or {@link #RENDERER_UNAVAILABLE} if it cannot be get
     */
    private int getVideoRendererIndex() {
        final MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector
                .getCurrentMappedTrackInfo();

        if (mappedTrackInfo == null) {
            return RENDERER_UNAVAILABLE;
        }

        // Check every renderer
        return IntStream.range(0, mappedTrackInfo.getRendererCount())
                // Check the renderer is a video renderer and has at least one track
                .filter(i -> !mappedTrackInfo.getTrackGroups(i).isEmpty()
                        && simpleExoPlayer.getRendererType(i) == C.TRACK_TYPE_VIDEO)
                // Return the first index found (there is at most one renderer per renderer type)
                .findFirst()
                // No video renderer index with at least one track found: return unavailable index
                .orElse(RENDERER_UNAVAILABLE);
    }

    public void onBufferingFailed() {
        pause();
        bulletCommentsController.pause();
        setCurrentState(PlayerPlaybackState.PAUSED);
        notifyPlaybackUpdateToListeners();
        dataSource.disconnectWebSocketClients();
    }
}
