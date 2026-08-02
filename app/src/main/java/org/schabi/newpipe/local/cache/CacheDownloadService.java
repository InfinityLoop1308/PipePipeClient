package org.schabi.newpipe.local.cache;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.cache.model.CachedStreamEntity;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.MissionRecoveryInfo;
import us.shandian.giga.postprocessing.Postprocessing;
import us.shandian.giga.service.DownloadManagerService;

/**
 * Downloads the media for the "cache for offline viewing" feature into app-private storage.
 *
 * <p>This deliberately reuses {@link DownloadMission} - the exact same engine the regular Download
 * feature uses - rather than doing its own HTTP GET. That is what makes the rule "if the app can
 * play it and download it, it can cache it" actually hold: SABR (YouTube's current default), HLS,
 * DASH and inline-manifest streams are all handled by the mission engine, along with
 * multi-threading, retries and ffmpeg post-processing (which muxes a video-only track and its
 * separate audio track into one playable file).</p>
 *
 * <p>The mission is driven directly here instead of through {@code DownloadManagerService}, so
 * cached videos stay out of the user's Downloads list and are owned entirely by the cache.</p>
 */
public final class CacheDownloadService extends Service {
    private static final String TAG = "CacheDownloadService";
    private static final int NOTIFICATION_ID = 0x63616368; // "cach"
    private static final int PROGRESS_POLL_MS = 700;
    private static final long STALL_WARN_MS = 30_000;
    private static final long STALL_LOG_INTERVAL_MS = 60_000;
    private static final long STALL_ABORT_MS = 180_000;
    private static final long NEVER_STARTED_ABORT_MS = 45_000;

    private static final String EXTRA_SERVICE_ID = "cache_service_id";
    private static final String EXTRA_URL = "cache_url";
    private static final String EXTRA_STREAM_ID = "cache_stream_id";
    private static final String EXTRA_TITLE = "cache_title";
    private static final String EXTRA_STREAM_TYPE = "cache_stream_type";
    private static final String EXTRA_DURATION = "cache_duration";
    private static final String EXTRA_UPLOADER_NAME = "cache_uploader_name";
    private static final String EXTRA_UPLOADER_URL = "cache_uploader_url";
    private static final String EXTRA_UPLOADER_AVATAR_URL = "cache_uploader_avatar_url";
    private static final String EXTRA_THUMBNAIL_URL = "cache_thumbnail_url";
    private static final String EXTRA_TEXTUAL_UPLOAD_DATE = "cache_textual_upload_date";
    private static final String EXTRA_VIEW_COUNT = "cache_view_count";
    private static final String EXTRA_DESCRIPTION = "cache_description";
    private static final String EXTRA_SEGMENTS = "cache_segments";

    // Mission parameters, mirroring what DownloadManagerService.startMission() accepts.
    private static final String EXTRA_MISSION_URLS = "cache_mission_urls";
    private static final String EXTRA_MISSION_KIND = "cache_mission_kind";
    private static final String EXTRA_MISSION_PS_NAME = "cache_mission_ps_name";
    private static final String EXTRA_MISSION_PS_ARGS = "cache_mission_ps_args";
    private static final String EXTRA_MISSION_RECOVERY = "cache_mission_recovery";
    private static final String EXTRA_MISSION_DELIVERY = "cache_mission_delivery";
    private static final String EXTRA_MISSION_MANIFESTS = "cache_mission_manifests";
    private static final String EXTRA_MISSION_IS_URLS = "cache_mission_is_urls";
    private static final String EXTRA_MISSION_SUFFIX = "cache_mission_suffix";
    private static final String EXTRA_VIDEO_RESOLUTION = "cache_video_resolution";
    private static final String EXTRA_MISSION_MIME = "cache_mission_mime";
    private static final String EXTRA_NEAR_LENGTH = "cache_near_length";

    private Handler handler;
    /** Missions still running, keyed by "serviceId url" so progress/finish can be attributed. */
    private final Map<DownloadMission, Intent> running = new HashMap<>();
    /** Per mission: {lastDone, lastProgressAtMs, lastStallLogAtMs}. See {@link #watchForStall}. */
    private final Map<DownloadMission, long[]> stallWatch = new HashMap<>();
    /**
     * Room forbids blocking database calls on the main thread, and both the service callbacks
     * ({@code onStartCommand}) and the mission's message handler run there.
     */
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private void runOffMainThread(@NonNull final Runnable work) {
        dbExecutor.execute(() -> {
            try {
                work.run();
            } catch (final Throwable t) {
                CacheLogger.e(this, TAG, "cache database write failed", t);
            }
        });
    }

    /**
     * Starts caching. All mission parameters are computed by
     * {@link CacheManager#startCaching(Context, org.schabi.newpipe.extractor.stream.StreamInfo,
     * org.schabi.newpipe.extractor.stream.VideoStream,
     * org.schabi.newpipe.extractor.stream.AudioStream)} so this service stays a thin runner.
     */
    static void enqueue(@NonNull final Context context,
                        @NonNull final CacheManager.CacheRequest request) {
        final Intent intent = new Intent(context, CacheDownloadService.class);
        intent.putExtra(EXTRA_SERVICE_ID, request.serviceId);
        intent.putExtra(EXTRA_URL, request.url);
        intent.putExtra(EXTRA_STREAM_ID, request.streamId);
        intent.putExtra(EXTRA_TITLE, request.title);
        intent.putExtra(EXTRA_STREAM_TYPE, request.streamType);
        intent.putExtra(EXTRA_DURATION, request.duration);
        intent.putExtra(EXTRA_UPLOADER_NAME, request.uploaderName);
        intent.putExtra(EXTRA_UPLOADER_URL, request.uploaderUrl);
        intent.putExtra(EXTRA_UPLOADER_AVATAR_URL, request.uploaderAvatarUrl);
        intent.putExtra(EXTRA_THUMBNAIL_URL, request.thumbnailUrl);
        intent.putExtra(EXTRA_TEXTUAL_UPLOAD_DATE, request.textualUploadDate);
        intent.putExtra(EXTRA_VIEW_COUNT, request.viewCount);
        intent.putExtra(EXTRA_DESCRIPTION, truncateDescription(request.description));
        intent.putExtra(EXTRA_SEGMENTS, request.sponsorBlockSegments);

        intent.putExtra(EXTRA_MISSION_URLS, request.urls);
        intent.putExtra(EXTRA_MISSION_KIND, request.kind);
        intent.putExtra(EXTRA_MISSION_PS_NAME, request.psName);
        intent.putExtra(EXTRA_MISSION_PS_ARGS, request.psArgs);
        intent.putExtra(EXTRA_MISSION_RECOVERY, request.recoveryInfo);
        intent.putExtra(EXTRA_MISSION_DELIVERY, request.resourceDeliveryMethods);
        intent.putExtra(EXTRA_MISSION_MANIFESTS, request.resourceManifestUrls);
        intent.putExtra(EXTRA_MISSION_IS_URLS, request.resourceIsUrls);
        intent.putExtra(EXTRA_MISSION_SUFFIX, request.fileSuffix);
        intent.putExtra(EXTRA_VIDEO_RESOLUTION, request.videoResolution);
        intent.putExtra(EXTRA_MISSION_MIME, request.mimeType);
        intent.putExtra(EXTRA_NEAR_LENGTH, request.nearLength);

        CacheLogger.d(context, TAG, "enqueue() url=" + request.url
                + " kind=" + request.kind + " urls=" + request.urls.length
                + " postProcessing=" + request.psName
                + " delivery=" + java.util.Arrays.toString(request.resourceDeliveryMethods));
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (final Exception e) {
            CacheLogger.e(context, TAG, "Failed to start CacheDownloadService for url="
                    + request.url, e);
            CacheManager.reportProgress(request.serviceId, request.url,
                    CacheManager.PROGRESS_FAILED);
        }
    }

    /**
     * Intent extras cross a Binder transaction with a hard ~1 MB budget shared by the whole
     * process, and some descriptions are enormous. Cap it: the description is a nice-to-have for
     * the offline page, not worth risking a TransactionTooLargeException over.
     */
    @Nullable
    private static String truncateDescription(@Nullable final String description) {
        final int maxChars = 20_000;
        if (description == null || description.length() <= maxChars) {
            return description;
        }
        return description.substring(0, maxChars) + "…";
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        final String url = intent.getStringExtra(EXTRA_URL);
        final String title = intent.getStringExtra(EXTRA_TITLE);
        final int serviceId = intent.getIntExtra(EXTRA_SERVICE_ID, 0);
        CacheLogger.d(this, TAG, "onStartCommand() url=" + url);

        startForeground(NOTIFICATION_ID, buildNotification(title, 0));

        try {
            startMission(intent);
        } catch (final Throwable t) {
            // Nothing may escape: an unhandled failure here used to leave the user with a
            // "caching started" toast and no further sign of anything at all.
            CacheLogger.e(this, TAG, "Failed to start cache mission for url=" + url, t);
            updateNotification(title, -1);
            if (url != null) {
                CacheManager.reportProgress(serviceId, url, CacheManager.PROGRESS_FAILED);
            }
            stopIfIdle();
        }
        return START_NOT_STICKY;
    }

    private void startMission(@NonNull final Intent intent) throws Exception {
        final String url = intent.getStringExtra(EXTRA_URL);
        final String streamId = intent.getStringExtra(EXTRA_STREAM_ID);
        final String[] urls = intent.getStringArrayExtra(EXTRA_MISSION_URLS);
        if (url == null || streamId == null || urls == null || urls.length == 0) {
            throw new IllegalStateException("cache mission intent is missing required extras");
        }

        final int serviceId = intent.getIntExtra(EXTRA_SERVICE_ID, 0);
        final char kind = intent.getCharExtra(EXTRA_MISSION_KIND, 'v');
        final String suffix = intent.getStringExtra(EXTRA_MISSION_SUFFIX);
        final String mime = intent.getStringExtra(EXTRA_MISSION_MIME);

        final File dir = CacheManager.getCacheDirFor(this, serviceId, streamId);
        final File target = new File(dir, "media." + (suffix != null ? suffix : "mp4"));
        // DownloadMission opens the file itself, but StoredFileHelper wants something that
        // already exists when resolving a file:// uri.
        if (!target.exists() && !target.createNewFile()) {
            throw new IllegalStateException("could not create " + target.getAbsolutePath());
        }

        final StoredFileHelper storage = new StoredFileHelper(
                this, Uri.fromFile(dir), Uri.fromFile(target), mime);

        final String psName = intent.getStringExtra(EXTRA_MISSION_PS_NAME);
        Postprocessing ps = null;
        if (psName != null) {
            ps = Postprocessing.getAlgorithm(psName,
                    intent.getStringArrayExtra(EXTRA_MISSION_PS_ARGS));
            // Muxing needs scratch space; the app-private cache dir is always writable and is
            // cleaned up by the system under storage pressure.
            ps.setTemporalDir(getCacheDir());
        }

        final DownloadMission mission =
                new DownloadMission(urls, storage, kind, ps, getApplicationContext());
        mission.source = url;
        mission.threadCount = 3;
        mission.nearLength = intent.getLongExtra(EXTRA_NEAR_LENGTH, 0);
        mission.recoveryInfo = toRecoveryInfo(
                intent.getParcelableArrayExtra(EXTRA_MISSION_RECOVERY), urls.length);
        mission.resourceDeliveryMethods = intent.getStringArrayExtra(EXTRA_MISSION_DELIVERY);
        mission.resourceManifestUrls = intent.getStringArrayExtra(EXTRA_MISSION_MANIFESTS);
        mission.resourceIsUrls = intent.getBooleanArrayExtra(EXTRA_MISSION_IS_URLS);
        mission.enqueued = false;
        mission.timestamp = System.currentTimeMillis();
        // DownloadManager normally gives every mission a metadata file and DownloadMission
        // assumes one exists: notifyFinished() -> deleteThisFromFile() dereferences it without a
        // null check, so a standalone mission would throw NPE on the download thread *before*
        // sending MESSAGE_FINISHED - the download would sit at 99% forever with nothing running.
        mission.metadata = new File(dir, ".mission");
        if (!mission.metadata.exists() && !mission.metadata.createNewFile()) {
            throw new IllegalStateException(
                    "could not create " + mission.metadata.getAbsolutePath());
        }
        mission.mHandler = new Handler(Looper.getMainLooper(), msg -> {
            onMissionMessage(msg.what, (DownloadMission) msg.obj);
            return true;
        });

        running.put(mission, intent);
        // Record the entry up front (is_complete = false) so it shows in "Cached videos" while
        // it downloads, rather than the screen staying empty until the moment it finishes.
        // Offline playback deliberately ignores incomplete rows - see findCompleteCachedStream.
        final CachedStreamEntity placeholder = buildEntity(intent, target, false);
        runOffMainThread(() -> {
            // Drop any leftover row from an attempt that never finished, so a retry starts from
            // a clean slate rather than inheriting the previous attempt's state.
            NewPipeDatabase.getInstance(getApplicationContext()).cachedStreamDAO()
                    .deleteByUrl(serviceId, url);
            NewPipeDatabase.getInstance(getApplicationContext()).cachedStreamDAO()
                    .insert(placeholder);
        });
        CacheManager.registerRunningMission(serviceId, url, mission);
        CacheManager.cacheChanges.onNext(new CacheManager.CacheChangeEvent(serviceId, url, false));

        CacheLogger.d(this, TAG, "starting mission -> " + target.getAbsolutePath());
        mission.start();
        scheduleProgressPoll();
    }

    @NonNull
    private static MissionRecoveryInfo[] toRecoveryInfo(@Nullable final Parcelable[] parcels,
                                                        final int urlCount) {
        if (parcels == null) {
            return new MissionRecoveryInfo[urlCount];
        }
        final MissionRecoveryInfo[] recovery = new MissionRecoveryInfo[parcels.length];
        for (int i = 0; i < parcels.length; i++) {
            recovery[i] = (MissionRecoveryInfo) parcels[i];
        }
        return recovery;
    }

    @NonNull
    private static String describeMessage(final int what) {
        switch (what) {
            case DownloadManagerService.MESSAGE_RUNNING: return "RUNNING";
            case DownloadManagerService.MESSAGE_PAUSED: return "PAUSED";
            case DownloadManagerService.MESSAGE_FINISHED: return "FINISHED";
            case DownloadManagerService.MESSAGE_ERROR: return "ERROR";
            case DownloadManagerService.MESSAGE_DELETED: return "DELETED";
            default: return "UNKNOWN(" + what + ")";
        }
    }

    private void onMissionMessage(final int what, @Nullable final DownloadMission mission) {
        if (mission == null) {
            return;
        }
        final Intent intent = running.get(mission);
        if (intent == null) {
            CacheLogger.w(this, TAG, "mission message " + describeMessage(what)
                    + " for an unknown mission - ignoring");
            return;
        }
        final String url = intent.getStringExtra(EXTRA_URL);
        final String title = intent.getStringExtra(EXTRA_TITLE);
        final int serviceId = intent.getIntExtra(EXTRA_SERVICE_ID, 0);
        // Every mission message is logged: when a download appears to hang, knowing which of
        // these did (or didn't) arrive is the difference between guessing and knowing.
        CacheLogger.d(this, TAG, "mission message " + describeMessage(what)
                + " done=" + mission.done + " length=" + mission.getLength()
                + " psState=" + mission.psState + " url=" + url);
        stallWatch.remove(mission);

        switch (what) {
            case DownloadManagerService.MESSAGE_FINISHED:
                running.remove(mission);
                onMissionFinished(mission, intent, serviceId, url, title);
                stopIfIdle();
                break;
            case DownloadManagerService.MESSAGE_ERROR:
                running.remove(mission);
                CacheLogger.e(this, TAG, "cache mission failed for url=" + url
                        + " errCode=" + mission.errCode
                        + " errObject=" + mission.errObject, mission.errObject);
                deleteQuietly(mission.storage);
                // Drop the placeholder row so a failed attempt doesn't linger in "Cached videos"
                // as a download that never progresses.
                runOffMainThread(() -> NewPipeDatabase.getInstance(getApplicationContext())
                        .cachedStreamDAO().deleteByUrl(serviceId, url));
                CacheManager.unregisterRunningMission(serviceId, url);
                updateNotification(title, -1);
                CacheManager.reportProgress(serviceId, url, CacheManager.PROGRESS_FAILED);
                CacheManager.cacheChanges.onNext(
                        new CacheManager.CacheChangeEvent(serviceId, url, false));
                stopIfIdle();
                break;
            default:
                break;
        }
    }

    private void onMissionFinished(@NonNull final DownloadMission mission,
                                   @NonNull final Intent intent,
                                   final int serviceId,
                                   @NonNull final String url,
                                   @Nullable final String title) {
        final char kind = intent.getCharExtra(EXTRA_MISSION_KIND, 'v');
        final String suffix = intent.getStringExtra(EXTRA_MISSION_SUFFIX);
        final File file = new File(
                CacheManager.getCacheDirFor(this, serviceId,
                        intent.getStringExtra(EXTRA_STREAM_ID)),
                "media." + (suffix != null ? suffix : "mp4"));

        if (!file.exists() || file.length() == 0) {
            CacheLogger.e(this, TAG, "mission reported success but " + file.getAbsolutePath()
                    + " is missing or empty - refusing to record an unplayable cache entry", null);
            updateNotification(title, -1);
            CacheManager.reportProgress(serviceId, url, CacheManager.PROGRESS_FAILED);
            return;
        }

        CacheLogger.d(this, TAG, "cached " + file.getAbsolutePath()
                + " (" + file.length() + " bytes) for url=" + url);
        // Replaces the placeholder row inserted when the mission started (the table has a unique
        // index on service_id + url and the DAO inserts with OnConflictStrategy.REPLACE).
        final CachedStreamEntity finished = buildEntity(intent, file, true);
        runOffMainThread(() -> NewPipeDatabase.getInstance(getApplicationContext())
                .cachedStreamDAO().insert(finished));
        CacheManager.unregisterRunningMission(serviceId, url);
        updateNotification(title, 100);
        CacheManager.reportProgress(serviceId, url, CacheManager.PROGRESS_DONE);
        CacheManager.cacheChanges.onNext(new CacheManager.CacheChangeEvent(serviceId, url, true));
    }

    /**
     * Builds the database row for a cache entry. Post-processing muxes video+audio into one
     * file, so an entry is either a single video file (already carrying its audio) or a single
     * audio file.
     *
     * @param complete {@code false} for the placeholder written when the download starts
     */
    @NonNull
    private CachedStreamEntity buildEntity(@NonNull final Intent intent,
                                           @NonNull final File file,
                                           final boolean complete) {
        final boolean audioOnly = intent.getCharExtra(EXTRA_MISSION_KIND, 'v') == 'a';
        final String suffix = intent.getStringExtra(EXTRA_MISSION_SUFFIX);
        final String url = intent.getStringExtra(EXTRA_URL);
        final String title = intent.getStringExtra(EXTRA_TITLE);
        return new CachedStreamEntity(
                0,
                intent.getIntExtra(EXTRA_SERVICE_ID, 0),
                url,
                intent.getStringExtra(EXTRA_STREAM_ID),
                title == null ? String.valueOf(url) : title,
                intent.getStringExtra(EXTRA_STREAM_TYPE),
                intent.getLongExtra(EXTRA_DURATION, 0),
                intent.getStringExtra(EXTRA_UPLOADER_NAME),
                intent.getStringExtra(EXTRA_UPLOADER_URL),
                intent.getStringExtra(EXTRA_UPLOADER_AVATAR_URL),
                intent.getStringExtra(EXTRA_THUMBNAIL_URL),
                intent.getStringExtra(EXTRA_TEXTUAL_UPLOAD_DATE),
                intent.getLongExtra(EXTRA_VIEW_COUNT, 0),
                intent.getStringExtra(EXTRA_DESCRIPTION),
                audioOnly ? null : file.getAbsolutePath(),
                audioOnly ? file.getAbsolutePath() : null,
                audioOnly ? null : suffix,
                audioOnly ? suffix : null,
                intent.getStringExtra(EXTRA_SEGMENTS),
                complete ? file.length() : 0,
                System.currentTimeMillis(),
                complete,
                intent.getStringExtra(EXTRA_VIDEO_RESOLUTION));
    }

    private void scheduleProgressPoll() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::pollProgress, PROGRESS_POLL_MS);
    }

    private void pollProgress() {
        if (running.isEmpty()) {
            return;
        }
        // Snapshot: watchForStall() may abort a mission and remove it from `running`.
        for (final Map.Entry<DownloadMission, Intent> entry
                : new java.util.ArrayList<>(running.entrySet())) {
            final DownloadMission mission = entry.getKey();
            final Intent intent = entry.getValue();
            final long length = mission.getLength();
            if (length <= 0) {
                // A mission that never reports a length never initialised - it produced no bytes
                // and no error. Skipping it here (as this used to) meant the stall watchdog never
                // saw it either, so a download that silently failed to start just sat there
                // forever with no progress and no failure. Watch it too.
                watchForStall(mission, intent, 0, false);
                continue;
            }
            // "Processing" means ffmpeg is actually remuxing, which is the only phase that
            // legitimately makes no byte progress. psState == 1 is set by SabrFfmpegMuxer;
            // isPsRunning() covers the non-SABR postprocessing path (it returns false for SABR,
            // because SABR missions have no psAlgorithm). Previously this was inferred from
            // "bytes are all in", which mislabelled a genuine stall as processing.
            final boolean processing = mission.psState == 1 || mission.isPsRunning();
            final int percent = (int) Math.min(99, mission.done * 100 / length);
            updateNotification(intent.getStringExtra(EXTRA_TITLE), percent, processing);
            CacheManager.reportProgress(intent.getIntExtra(EXTRA_SERVICE_ID, 0),
                    intent.getStringExtra(EXTRA_URL),
                    processing ? CacheManager.PROGRESS_PROCESSING : percent);
            watchForStall(mission, intent, percent, processing);
        }
        scheduleProgressPoll();
    }

    /**
     * Notices when a mission stops making byte progress and isn't remuxing either, and says so in
     * the debug log with the full mission state. Without this a stall is indistinguishable from
     * slow progress: the UI just sits at a percentage forever with nothing explaining why.
     */
    private void watchForStall(@NonNull final DownloadMission mission,
                               @NonNull final Intent intent,
                               final int percent,
                               final boolean processing) {
        final long now = System.currentTimeMillis();
        long[] watch = stallWatch.get(mission);
        if (watch == null) {
            watch = new long[]{mission.done, now, 0};
            stallWatch.put(mission, watch);
            return;
        }
        if (mission.done != watch[0] || processing) {
            final long previousDone = watch[0];
            watch[0] = mission.done;
            watch[1] = now;
            // Heartbeat while bytes ARE flowing. Crucial for telling a real hang apart from a
            // download that keeps running while the UI shows 99% - which is what happens when
            // `done` overshoots the expected length, since the percentage is capped at 99.
            if (now - watch[2] >= STALL_LOG_INTERVAL_MS) {
                watch[2] = now;
                CacheLogger.d(this, TAG, "progress " + percent + "%"
                        + " done=" + mission.done + " (+" + (mission.done - previousDone) + ")"
                        + " length=" + mission.getLength()
                        + " nearLength=" + mission.nearLength
                        + (mission.done > mission.getLength() ? " OVERSHOOTING-EXPECTED-LENGTH" : "")
                        + " psState=" + mission.psState
                        + " processing=" + processing);
            }
            return;
        }
        final long stalledMs = now - watch[1];
        // A mission that has produced nothing and never initialised didn't start at all, as
        // opposed to one that stalled midway. Give up on it much sooner: there is nothing to wait
        // for, and leaving it "in progress" is what made a failed start look like a live download.
        final boolean neverStarted = mission.done == 0 && !mission.isInitialized();
        final long abortAfterMs = neverStarted ? NEVER_STARTED_ABORT_MS : STALL_ABORT_MS;
        if (stalledMs < STALL_WARN_MS || now - watch[2] < STALL_LOG_INTERVAL_MS) {
            if (!(neverStarted && stalledMs >= abortAfterMs)) {
                return;
            }
        }
        watch[2] = now;
        final String state = (neverStarted ? "NEVER STARTED after " : "STALLED ")
                + (stalledMs / 1000) + "s at " + percent + "%"
                + " url=" + intent.getStringExtra(EXTRA_URL)
                + " done=" + mission.done
                + " length=" + mission.getLength()
                + " nearLength=" + mission.nearLength
                + " current=" + mission.current + "/" + mission.urls.length
                + " running=" + mission.running
                + " psState=" + mission.psState
                + " errCode=" + mission.errCode
                + " unknownLength=" + mission.unknownLength
                + " initialized=" + mission.isInitialized()
                + " finished=" + mission.isFinished();
        CacheLogger.w(this, TAG, state);

        if (stalledMs >= abortAfterMs) {
            // Never leave a download hanging indefinitely: give up with a visible error so the
            // user can retry, instead of a percentage that sits there forever.
            final int serviceId = intent.getIntExtra(EXTRA_SERVICE_ID, 0);
            final String url = intent.getStringExtra(EXTRA_URL);
            CacheLogger.e(this, TAG, "aborting stalled cache download - " + state, null);
            running.remove(mission);
            stallWatch.remove(mission);
            try {
                mission.pause();
            } catch (final Exception ignored) {
                // best effort; we're failing the mission either way
            }
            deleteQuietly(mission.storage);
            if (url != null) {
                runOffMainThread(() -> NewPipeDatabase.getInstance(getApplicationContext())
                        .cachedStreamDAO().deleteByUrl(serviceId, url));
                CacheManager.unregisterRunningMission(serviceId, url);
                CacheManager.reportProgress(serviceId, url, CacheManager.PROGRESS_FAILED);
                CacheManager.cacheChanges.onNext(
                        new CacheManager.CacheChangeEvent(serviceId, url, false));
            }
            updateNotification(intent.getStringExtra(EXTRA_TITLE), -1);
            stopIfIdle();
        }
    }

    private void stopIfIdle() {
        if (running.isEmpty()) {
            handler.removeCallbacksAndMessages(null);
            stopForeground(false);
            stopSelf();
        }
    }

    private void deleteQuietly(@Nullable final StoredFileHelper storage) {
        try {
            if (storage != null && storage.existsAsFile()) {
                storage.delete();
            }
        } catch (final Exception e) {
            CacheLogger.w(this, TAG, "could not delete partial cache file: " + e);
        }
    }

    @NonNull
    private Notification buildNotification(@Nullable final String title, final int percent) {
        return buildNotification(title, percent, false);
    }

    @NonNull
    private Notification buildNotification(@Nullable final String title, final int percent,
                                           final boolean processing) {
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(
                this, getString(R.string.notification_channel_id))
                .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
                .setContentTitle(getString(R.string.cache_notification_title))
                .setContentText(title == null ? "" : title)
                .setOnlyAlertOnce(true);
        if (percent < 0) {
            builder.setContentText(getString(R.string.cache_notification_failed, title));
            builder.setOngoing(false);
            builder.setProgress(0, 0, false);
        } else if (percent >= 100) {
            builder.setContentText(getString(R.string.cache_notification_done, title));
            builder.setOngoing(false);
            builder.setProgress(0, 0, false);
        } else if (processing) {
            // Indeterminate: remuxing reports no byte progress, and a bar parked at 99% for
            // minutes reads as a hang.
            builder.setContentText(getString(R.string.cache_processing_notification, title));
            builder.setProgress(0, 0, true);
        } else {
            builder.setProgress(100, percent, percent == 0);
        }
        return builder.build();
    }

    private void updateNotification(@Nullable final String title, final int percent) {
        updateNotification(title, percent, false);
    }

    private void updateNotification(@Nullable final String title, final int percent,
                                    final boolean processing) {
        final NotificationManager manager = ContextCompat.getSystemService(this,
                NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(title, percent, processing));
        }
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        // shutdown(), not shutdownNow(): a queued "download finished" row must still be written.
        dbExecutor.shutdown();
    }
}
