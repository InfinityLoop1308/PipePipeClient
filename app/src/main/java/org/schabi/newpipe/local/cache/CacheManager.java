package org.schabi.newpipe.local.cache;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.database.cache.dao.CachedStreamDAO;
import org.schabi.newpipe.database.cache.model.CachedStreamEntity;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockAction;
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockCategory;
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockSegment;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.subjects.PublishSubject;

import us.shandian.giga.get.HlsDownloadStreamHelper;
import us.shandian.giga.get.MissionRecoveryInfo;
import us.shandian.giga.get.SabrDownloadStreamHelper;
import us.shandian.giga.postprocessing.Postprocessing;

/**
 * Backs the "cache for offline viewing" feature requested in
 * https://github.com/InfinityLoop1308/PipePipe/issues/2782. Unlike the existing download
 * feature (which exports a permanent file to user-chosen storage via SAF), this stores a
 * disposable copy of a stream's video/audio + the metadata needed to render its page in the
 * app's private storage, so it can be watched offline and then thrown away.
 */
public final class CacheManager {

    private static final String CACHE_DIR_NAME = "stream_cache";

    private CacheManager() {
    }

    /**
     * Fired whenever a stream is cached or removed from the cache, so any currently visible UI
     * (e.g. the cache/delete button on the video page) can update itself without re-querying the
     * database on a timer. Carries the affected stream's identity and its new cached state.
     */
    public static final PublishSubject<CacheChangeEvent> cacheChanges = PublishSubject.create();

    public static final class CacheChangeEvent {
        public final int serviceId;
        @NonNull public final String url;
        public final boolean cached;

        public CacheChangeEvent(final int serviceId, @NonNull final String url,
                                final boolean cached) {
            this.serviceId = serviceId;
            this.url = url;
            this.cached = cached;
        }
    }

    /**
     * Fired by {@link CacheDownloadService} as a download progresses, so any visible UI (the
     * video page's Cache button, list-item badges) can show live progress instead of the
     * unhelpful "started, then nothing" experience of only reacting to {@link #cacheChanges}.
     */
    public static final PublishSubject<CacheProgressEvent> cacheProgress = PublishSubject.create();

    /** In-memory only (not persisted) so list rows can synchronously check "is this in progress
     * right now" the same way {@link #isCachedBlocking} checks "is this cached" - keyed the same
     * way as {@link #cacheKey}. */
    private static final Map<String, Integer> PROGRESS_BY_KEY = new ConcurrentHashMap<>();

    public static final class CacheProgressEvent {
        public final int serviceId;
        @NonNull public final String url;
        /** 0-99 while downloading, {@link #PROGRESS_DONE} on success, {@link #PROGRESS_FAILED}
         * on failure. */
        public final int percent;

        public CacheProgressEvent(final int serviceId, @NonNull final String url,
                                  final int percent) {
            this.serviceId = serviceId;
            this.url = url;
            this.percent = percent;
        }
    }

    public static final int PROGRESS_FAILED = -1;
    public static final int PROGRESS_DONE = 100;
    /**
     * All bytes are downloaded but the file is still being remuxed/post-processed. A distinct
     * sentinel rather than 99 so the UI can say "Processing…" instead of sitting on a frozen
     * "99%" that is indistinguishable from a hang - and so it can't be confused with a genuine
     * 99% of bytes.
     */
    public static final int PROGRESS_PROCESSING = -2;

    /**
     * Records and broadcasts a download progress update. A {@code percent} of
     * {@link #PROGRESS_FAILED} or {@link #PROGRESS_DONE} clears the in-progress marker for this
     * stream (the download is no longer "in progress" either way).
     */
    public static void reportProgress(final int serviceId, @NonNull final String url,
                                      final int percent) {
        final String key = cacheKey(serviceId, url);
        if (percent == PROGRESS_FAILED || percent >= PROGRESS_DONE) {
            PROGRESS_BY_KEY.remove(key);
        } else {
            // 0-99 while fetching bytes, or PROGRESS_PROCESSING while remuxing: both mean the
            // stream is still being worked on and list badges should keep showing it.
            PROGRESS_BY_KEY.put(key, percent);
        }
        cacheProgress.onNext(new CacheProgressEvent(serviceId, url, percent));
    }

    /**
     * @return the last reported progress percent (0-99) for a stream currently being cached, or
     *         {@link #PROGRESS_FAILED} if nothing is in progress for it. Purely in-memory (no DB
     *         access), safe to call from the main thread during RecyclerView bind.
     */
    public static int getProgressBlocking(final int serviceId, @NonNull final String url) {
        final Integer percent = PROGRESS_BY_KEY.get(cacheKey(serviceId, url));
        return percent == null ? PROGRESS_FAILED : percent;
    }

    @NonNull
    public static File getCacheRootDir(@NonNull final Context context) {
        final File base = context.getExternalFilesDir(null) != null
                ? context.getExternalFilesDir(null) : context.getFilesDir();
        final File dir = new File(base, CACHE_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    @NonNull
    public static File getCacheDirFor(@NonNull final Context context,
                                      final int serviceId,
                                      @NonNull final String streamId) {
        final String safeId = streamId.replaceAll("[^A-Za-z0-9_.-]", "_");
        final File dir = new File(getCacheRootDir(context), serviceId + "_" + safeId);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    @NonNull
    private static CachedStreamDAO dao(@NonNull final Context context) {
        return NewPipeDatabase.getInstance(context.getApplicationContext()).cachedStreamDAO();
    }

    @NonNull
    public static Maybe<CachedStreamEntity> findCachedStream(@NonNull final Context context,
                                                              final int serviceId,
                                                              @NonNull final String url) {
        return dao(context).findStream(serviceId, url);
    }

    /**
     * Like {@link #findCachedStream} but only matches a finished entry. Offline playback must use
     * this: a row now exists from the moment caching starts, so the Cached videos screen can list
     * the download in progress, and playing that row's half-written file would fail.
     */
    @NonNull
    public static Maybe<CachedStreamEntity> findCompleteCachedStream(
            @NonNull final Context context,
            final int serviceId,
            @NonNull final String url) {
        return dao(context).findCompleteStream(serviceId, url);
    }

    /**
     * Whether {@link CacheDownloadService} can fetch this stream.
     *
     * <p>Originally the cache ran its own OkHttp GET over {@link Stream#getContent()}, which only
     * works when the content really is a direct media URL. For an inline manifest
     * ({@code !isUrl()}) that threw an unchecked {@link IllegalArgumentException} which escaped
     * the download worker and was swallowed by its {@code ExecutorService} - the notorious
     * "it said caching started and nothing happened". For {@link DeliveryMethod#SABR} (YouTube's
     * current default) and {@link DeliveryMethod#HLS} it "succeeded" but saved a manifest instead
     * of media.</p>
     *
     * <p>The cache now runs the same {@link us.shandian.giga.get.DownloadMission} engine as the
     * regular Download feature, so all of those work - if the app can download a stream, it can
     * cache it. Torrents are the sole exception, exactly as in the download UI
     * ({@code ListHelper.removeNonUrlAndTorrentStreams}), because there is no torrent client.</p>
     */
    public static boolean isCacheable(@Nullable final Stream stream) {
        // Anything the download engine can fetch, the cache can fetch, because they are now the
        // same engine. Torrents are the one exception: DownloadMission has no torrent client, and
        // the regular download UI filters them out too (ListHelper.removeNonUrlAndTorrentStreams).
        return stream != null && stream.getDeliveryMethod() != DeliveryMethod.TORRENT;
    }

    /**
     * The video streams that can actually be cached, best quality first, in the same order and
     * with the same language filtering the download dialog uses.
     */
    @NonNull
    public static List<VideoStream> getCacheableVideoStreams(@NonNull final Context context,
                                                             @NonNull final StreamInfo info) {
        // Same pipeline the download dialog builds its quality list with, including the
        // HLS manifest fallback, so the cache offers the same qualities Download does.
        final List<VideoStream> sorted = new ArrayList<>(org.schabi.newpipe.util.ListHelper
                .getSortedStreamVideosList(context, info.getVideoStreams(),
                        info.getVideoOnlyStreams(), false, false));
        final List<VideoStream> filtered = new ArrayList<>(org.schabi.newpipe.util.ListHelper
                .filterVideoStreamsByPreferredLanguage(context, sorted, info.getAudioStreams()));
        HlsDownloadStreamHelper.addManifestFallbackIfNeeded(filtered, info);

        final List<VideoStream> cacheable = new ArrayList<>();
        for (final VideoStream stream : filtered) {
            if (isCacheable(stream)) {
                cacheable.add(stream);
            }
        }
        return cacheable;
    }

    /** The audio streams that can actually be cached, matching the download dialog's list. */
    @NonNull
    public static List<AudioStream> getCacheableAudioStreams(@NonNull final StreamInfo info) {
        final List<AudioStream> downloadable = new ArrayList<>(org.schabi.newpipe.util.ListHelper
                .filterDownloadableAudioStreams(info.getAudioStreams()));
        HlsDownloadStreamHelper.addAudioFallbackIfNeeded(downloadable, info);

        final List<AudioStream> cacheable = new ArrayList<>();
        for (final AudioStream stream : downloadable) {
            if (isCacheable(stream)) {
                cacheable.add(stream);
            }
        }
        return cacheable;
    }

    /**
     * Human-readable summary of every stream the extractor returned and why it is or isn't
     * cacheable. Written to the debug log whenever caching can't proceed, so a bug report
     * screenshot immediately shows whether the video only offers SABR/HLS/manifest streams.
     */
    @NonNull
    public static String describeStreams(@NonNull final StreamInfo info) {
        final StringBuilder sb = new StringBuilder();
        appendStreamDescriptions(sb, "video", info.getVideoStreams());
        appendStreamDescriptions(sb, "videoOnly", info.getVideoOnlyStreams());
        appendStreamDescriptions(sb, "audio", info.getAudioStreams());
        return sb.length() == 0 ? "(extractor returned no streams at all)" : sb.toString();
    }

    private static void appendStreamDescriptions(@NonNull final StringBuilder sb,
                                                 @NonNull final String label,
                                                 @Nullable final List<? extends Stream> streams) {
        if (streams == null || streams.isEmpty()) {
            sb.append(label).append(": none; ");
            return;
        }
        for (final Stream stream : streams) {
            sb.append(label).append('[')
                    .append("delivery=").append(stream.getDeliveryMethod())
                    .append(" isUrl=").append(stream.isUrl())
                    .append(" format=").append(stream.getFormat())
                    .append(" cacheable=").append(isCacheable(stream))
                    .append("]; ");
        }
    }

    /**
     * Starts caching {@code info} using explicitly chosen streams (see
     * {@code CacheDialog} - the quality selector). Either stream may be null: video-only gives a
     * silent video, audio-only gives an audio-only cache entry, and passing both caches a
     * video-only track plus its separate audio track.
     *
     * @return {@code true} if a download was actually enqueued.
     */
    public static boolean startCaching(@NonNull final Context context,
                                       @NonNull final StreamInfo info,
                                       @Nullable final VideoStream video,
                                       @Nullable final AudioStream audio) {
        final Context appContext = context.getApplicationContext();
        CacheLogger.d(appContext, "startCaching", "requested for serviceId=" + info.getServiceId()
                + " url=" + info.getUrl() + " title=" + info.getName()
                + " video=" + (video != null ? video.getResolution() : "none")
                + " audio=" + (audio != null ? audio.getAverageBitrate() + "kbps" : "none"));

        if (video == null && audio == null) {
            CacheLogger.w(appContext, "startCaching",
                    "nothing was selected for " + info.getUrl()
                            + " - available streams: " + describeStreams(info));
            return false;
        }

        final CacheRequest request = buildRequest(info, video, audio);
        reportProgress(info.getServiceId(), info.getUrl(), 0);
        CacheDownloadService.enqueue(appContext, request);
        return true;
    }

    /**
     * Everything {@link CacheDownloadService} needs to run a {@link us.shandian.giga.get
     * .DownloadMission} plus the metadata to record afterwards. Assembled here so the mission
     * parameters are derived in exactly one place, matching what {@code DownloadDialog} passes to
     * {@code DownloadManagerService.startMission()}.
     */
    public static final class CacheRequest {
        public int serviceId;
        public String url;
        public String streamId;
        public String title;
        public String streamType;
        public long duration;
        public String uploaderName;
        public String uploaderUrl;
        public String uploaderAvatarUrl;
        public String thumbnailUrl;
        public String textualUploadDate;
        public long viewCount;
        public String description;
        public String sponsorBlockSegments;

        public String[] urls;
        public char kind;
        public String psName;
        public String[] psArgs;
        public MissionRecoveryInfo[] recoveryInfo;
        public String[] resourceDeliveryMethods;
        public String[] resourceManifestUrls;
        public boolean[] resourceIsUrls;
        public String fileSuffix;
        public String mimeType;
        public long nearLength;
    }

    /**
     * Mirrors the download dialog's mission setup: which URLs to fetch, which post-processing
     * algorithm muxes them, and the per-resource delivery metadata that lets the mission engine
     * handle SABR/HLS streams rather than treating everything as a plain file.
     */
    @NonNull
    private static CacheRequest buildRequest(@NonNull final StreamInfo info,
                                             @Nullable final VideoStream video,
                                             @Nullable final AudioStream audio) {
        final CacheRequest request = new CacheRequest();
        request.serviceId = info.getServiceId();
        request.url = info.getUrl();
        request.streamId = info.getId();
        request.title = info.getName();
        request.streamType = info.getStreamType().name();
        request.duration = info.getDuration();
        request.uploaderName = info.getUploaderName();
        request.uploaderUrl = info.getUploaderUrl();
        request.uploaderAvatarUrl = info.getUploaderAvatarUrl();
        request.thumbnailUrl = info.getThumbnailUrl();
        request.textualUploadDate = info.getTextualUploadDate();
        request.viewCount = info.getViewCount();
        request.description =
                info.getDescription() != null ? info.getDescription().getContent() : null;
        request.sponsorBlockSegments = serializeSegments(info.getSponsorBlockSegments());

        // A muxed video stream already carries audio; only a video-only track needs its separate
        // audio track fetched and muxed in.
        final Stream primary = video != null ? video : audio;
        final Stream secondary = video != null && video.isVideoOnly() ? audio : null;
        request.kind = video != null ? 'v' : 'a';

        if (secondary != null) {
            if (info.getService() == ServiceList.BiliBili) {
                request.psName = Postprocessing.BILIBILI_MUXER;
            } else if (info.getService() == ServiceList.NicoNico) {
                request.psName = Postprocessing.NICONICO_MUXER;
            } else if (video.getFormat() == MediaFormat.MPEG_4) {
                request.psName = Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER;
            } else {
                request.psName = Postprocessing.ALGORITHM_WEBM_MUXER;
            }
        } else if (video == null && audio != null) {
            if (info.getService() == ServiceList.NicoNico) {
                request.psName = Postprocessing.NICONICO_MUXER;
            } else if (audio.getFormat() == MediaFormat.M4A
                    && info.getService() != ServiceList.BiliBili) {
                request.psName = Postprocessing.ALGORITHM_M4A_NO_DASH;
            } else if (audio.getFormat() == MediaFormat.WEBMA_OPUS) {
                request.psName = Postprocessing.ALGORITHM_OGG_FROM_WEBM_DEMUXER;
            }
        }

        request.urls = secondary == null
                ? new String[]{primary.getContent()}
                : new String[]{primary.getContent(), secondary.getContent()};
        request.recoveryInfo = secondary == null
                ? new MissionRecoveryInfo[]{new MissionRecoveryInfo(primary)}
                : new MissionRecoveryInfo[]{new MissionRecoveryInfo(primary),
                        new MissionRecoveryInfo(secondary)};
        request.resourceDeliveryMethods =
                HlsDownloadStreamHelper.buildResourceDeliveryMethods(primary, secondary);
        request.resourceManifestUrls =
                HlsDownloadStreamHelper.buildResourceManifestUrls(primary, secondary);
        request.resourceIsUrls =
                HlsDownloadStreamHelper.buildResourceIsUrls(primary, secondary);

        // HLS and SABR resources are assembled by the mission engine itself and must not be run
        // through a muxer afterwards - same rule the download dialog applies.
        if (HlsDownloadStreamHelper.containsHlsResource(request.resourceDeliveryMethods,
                request.resourceManifestUrls, request.urls)
                || SabrDownloadStreamHelper.containsSabrStream(primary, secondary)) {
            request.psName = null;
            request.psArgs = null;
        }

        final MediaFormat format = primary.getFormat();
        request.fileSuffix = format != null ? format.getSuffix() : (video != null ? "mp4" : "m4a");
        request.mimeType = format != null ? format.getMimeType()
                : (video != null ? "video/mp4" : "audio/mp4");
        return request;
    }

    /**
     * Convenience entry point that picks the default quality itself, for callers without a UI to
     * show the quality selector in.
     *
     * @return {@code true} if a download was actually enqueued, {@code false} if this video has
     *         no cacheable stream at all - in which case the caller must tell the user rather
     *         than claiming caching "started".
     */
    public static boolean startCaching(@NonNull final Context context,
                                       @NonNull final StreamInfo info) {
        final Context appContext = context.getApplicationContext();
        final List<VideoStream> videos = getCacheableVideoStreams(appContext, info);
        final List<AudioStream> audios = getCacheableAudioStreams(info);

        final VideoStream video;
        if (videos.isEmpty()) {
            video = null;
        } else {
            final int index = org.schabi.newpipe.util.ListHelper
                    .getDefaultResolutionIndex(appContext, videos);
            video = index >= 0 && index < videos.size() ? videos.get(index) : videos.get(0);
        }

        // Only pair a separate audio track with a video-only stream; a muxed video stream
        // already carries its own audio, and caching a second copy would just waste space.
        AudioStream audio = null;
        if (!audios.isEmpty() && (video == null || video.isVideoOnly())) {
            final int index = org.schabi.newpipe.util.ListHelper
                    .getDefaultAudioFormat(appContext, audios);
            audio = index >= 0 && index < audios.size() ? audios.get(index) : audios.get(0);
        }

        if (video == null && audio == null) {
            CacheLogger.w(appContext, "startCaching",
                    "no cacheable stream for " + info.getUrl()
                            + " - available streams: " + describeStreams(info));
            return false;
        }
        return startCaching(context, info, video, audio);
    }

    /** Missions currently downloading, so an entry removed mid-download can actually be stopped. */
    private static final Map<String, us.shandian.giga.get.DownloadMission> RUNNING_MISSIONS =
            new ConcurrentHashMap<>();

    static void registerRunningMission(final int serviceId, @NonNull final String url,
                                       @NonNull final us.shandian.giga.get.DownloadMission m) {
        RUNNING_MISSIONS.put(cacheKey(serviceId, url), m);
    }

    static void unregisterRunningMission(final int serviceId, @NonNull final String url) {
        RUNNING_MISSIONS.remove(cacheKey(serviceId, url));
    }

    public static void removeCache(@NonNull final Context context,
                                   @NonNull final CachedStreamEntity entity) {
        CacheLogger.d(context, "removeCache", "removing serviceId=" + entity.getServiceId()
                + " url=" + entity.getUrl() + " complete=" + entity.isComplete());

        // If it's still downloading, stop the mission first, otherwise it would keep running and
        // re-insert its row when it finished.
        final us.shandian.giga.get.DownloadMission mission =
                RUNNING_MISSIONS.remove(cacheKey(entity.getServiceId(), entity.getUrl()));
        if (mission != null) {
            try {
                mission.pause();
            } catch (final Exception e) {
                CacheLogger.w(context, "removeCache", "could not stop running mission: " + e);
            }
            reportProgress(entity.getServiceId(), entity.getUrl(), PROGRESS_FAILED);
        }

        deleteFilesFor(entity);
        dao(context).delete(entity);
        cacheChanges.onNext(
                new CacheChangeEvent(entity.getServiceId(), entity.getUrl(), false));
    }

    /**
     * Synchronous cache lookup for use on list-item binding (e.g. on the main thread during
     * RecyclerView bind), mirroring the existing
     * {@code HistoryRecordManager.loadStreamState(...).blockingGet()} per-item convention used
     * for the watch-progress indicator (see {@code StreamInfoItemHolder}). Goes through the
     * {@link Maybe}-returning {@link #findCachedStream} rather than a plain blocking DAO method,
     * since Room only allows blocking a caller thread with {@code blockingGet()} when the actual
     * query runs on Room's own query executor (as it does for Rx-returning DAO methods) — a
     * direct blocking DAO method throws {@code IllegalStateException: Cannot access database on
     * the main thread} instead.
     */
    public static boolean isCachedBlocking(@NonNull final Context context,
                                           final int serviceId,
                                           @NonNull final String url) {
        observeCachedKeys(context);
        if (snapshotReady) {
            // Fast path: pure in-memory lookup, so scrolling a long list doesn't do one
            // round-trip to the database per row per bind.
            return CACHED_KEYS.contains(cacheKey(serviceId, url));
        }
        final CachedStreamEntity entity =
                findCachedStream(context, serviceId, url).blockingGet();
        return entity != null && entity.isComplete();
    }

    /**
     * Fires whenever the set of cached streams changes, so lists can refresh their badges. Unlike
     * {@link #cacheChanges} this carries no payload - it just means "re-check everything".
     */
    public static final PublishSubject<Object> cacheDataChanged = PublishSubject.create();

    private static final Set<String> CACHED_KEYS = ConcurrentHashMap.newKeySet();
    private static volatile boolean snapshotReady = false;
    private static io.reactivex.rxjava3.disposables.Disposable snapshotDisposable;

    /**
     * Subscribes (once per process) to the cache table so {@link #CACHED_KEYS} always mirrors it.
     * Room re-emits the {@link Flowable} on every write, so the snapshot maintains itself and
     * list-item binding never has to touch the database.
     */
    private static synchronized void observeCachedKeys(@NonNull final Context context) {
        if (snapshotDisposable != null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        snapshotDisposable = dao(appContext).getAllComplete()
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .subscribe(entities -> {
                    final Set<String> fresh = new HashSet<>(entities.size());
                    for (final CachedStreamEntity entity : entities) {
                        fresh.add(cacheKey(entity.getServiceId(), entity.getUrl()));
                    }
                    CACHED_KEYS.clear();
                    CACHED_KEYS.addAll(fresh);
                    snapshotReady = true;
                    cacheDataChanged.onNext(Boolean.TRUE);
                }, throwable -> CacheLogger.e(appContext, "CacheManager",
                        "failed to observe cached streams", throwable));
    }

    /** What, if anything, a list-item badge should show for a stream. */
    public enum CacheDisplayState { NONE, DOWNLOADING, CACHED }

    /**
     * Combines {@link #isCachedBlocking} and {@link #getProgressBlocking} into the single tri-
     * state a list-item badge needs, so callers (list holders, feed items) don't have to
     * duplicate the "which takes priority" logic themselves.
     */
    @NonNull
    public static CacheDisplayState getCacheDisplayState(@NonNull final Context context,
                                                         final int serviceId,
                                                         @NonNull final String url) {
        if (isCachedBlocking(context, serviceId, url)) {
            return CacheDisplayState.CACHED;
        }
        if (getProgressBlocking(serviceId, url) != PROGRESS_FAILED) {
            return CacheDisplayState.DOWNLOADING;
        }
        return CacheDisplayState.NONE;
    }

    /**
     * All currently complete cache entries' identities, for filtering a list of streams down to
     * only the ones available offline (e.g. the subscriptions feed's "cached only" toggle).
     * Only ever called off the main thread (from {@code FeedViewModel}'s IO-scheduled combine
     * pipeline), but still goes through the {@link Flowable}-returning DAO method rather than a
     * plain blocking one, so it stays safe even if a future caller doesn't guarantee that.
     */
    @NonNull
    public static Set<String> getAllCompleteCacheKeysBlocking(@NonNull final Context context) {
        final List<CachedStreamEntity> entities =
                dao(context).getAllComplete().blockingFirst(new ArrayList<>());
        final Set<String> keys = new HashSet<>(entities.size());
        for (final CachedStreamEntity entity : entities) {
            keys.add(cacheKey(entity.getServiceId(), entity.getUrl()));
        }
        return keys;
    }

    @NonNull
    public static String cacheKey(final int serviceId, @NonNull final String url) {
        return serviceId + " " + url;
    }

    static void deleteFilesFor(@NonNull final CachedStreamEntity entity) {
        deleteQuietly(entity.getVideoFilePath());
        deleteQuietly(entity.getAudioFilePath());
    }

    private static void deleteQuietly(@Nullable final String path) {
        if (path == null) {
            return;
        }
        final File file = new File(path);
        if (file.exists()) {
            final File parent = file.getParentFile();
            file.delete();
            if (parent != null) {
                final String[] remaining = parent.list();
                if (remaining != null && remaining.length == 0) {
                    parent.delete();
                }
            }
        }
    }

    @NonNull
    public static String serializeSegments(@Nullable final SponsorBlockSegment[] segments) {
        if (segments == null || segments.length == 0) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (final SponsorBlockSegment segment : segments) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(safe(segment.uuid)).append('|')
                    .append(segment.startTime).append('|')
                    .append(segment.endTime).append('|')
                    .append(segment.category.name()).append('|')
                    .append(segment.action.name());
        }
        return sb.toString();
    }

    private static String safe(@Nullable final String s) {
        return s == null ? "" : s.replace("|", "").replace(";", "");
    }

    @NonNull
    public static SponsorBlockSegment[] deserializeSegments(@Nullable final String data,
                                                            final int serviceId) {
        if (data == null || data.isEmpty()) {
            return new SponsorBlockSegment[0];
        }
        final String[] parts = data.split(";");
        final List<SponsorBlockSegment> segments = new ArrayList<>(parts.length);
        for (final String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            final String[] fields = part.split("\\|", -1);
            if (fields.length != 5) {
                continue;
            }
            try {
                segments.add(new SponsorBlockSegment(
                        fields[0],
                        Double.parseDouble(fields[1]),
                        Double.parseDouble(fields[2]),
                        SponsorBlockCategory.valueOf(fields[3]),
                        SponsorBlockAction.valueOf(fields[4]),
                        serviceId));
            } catch (final IllegalArgumentException ignored) {
                // skip malformed entry
            }
        }
        return segments.toArray(new SponsorBlockSegment[0]);
    }

    /**
     * Reconstructs a {@link StreamInfo} purely from cached, on-device data - no network
     * involved - so the normal video page / player code path can be reused unchanged for
     * offline playback (including watch position tracking, which keys off service id + url).
     */
    @NonNull
    public static StreamInfo buildStreamInfoFromCache(@NonNull final CachedStreamEntity entity) {
        final StreamInfo info = new StreamInfo(
                entity.getServiceId(),
                entity.getUrl(),
                entity.getUrl(),
                StreamType.valueOf(entity.getStreamType()),
                entity.getStreamId(),
                entity.getTitle(),
                0);
        info.setThumbnailUrl(entity.getThumbnailUrl());
        info.setUploaderName(entity.getUploaderName());
        info.setUploaderUrl(entity.getUploaderUrl());
        info.setUploaderAvatarUrl(entity.getUploaderAvatarUrl());
        info.setTextualUploadDate(entity.getTextualUploadDate());
        info.setViewCount(entity.getViewCount());
        info.setDuration(entity.getDuration());
        if (entity.getDescription() != null) {
            info.setDescription(new org.schabi.newpipe.extractor.stream.Description(
                    entity.getDescription(),
                    org.schabi.newpipe.extractor.stream.Description.PLAIN_TEXT));
        }
        info.setSponsorBlockSegments(
                deserializeSegments(entity.getSponsorBlockSegmentsData(), entity.getServiceId()));
        info.setFetchSponsorBlockFinished(true);

        final List<VideoStream> videoStreams = new ArrayList<>();
        final List<VideoStream> videoOnlyStreams = new ArrayList<>();
        if (entity.getVideoFilePath() != null) {
            final org.schabi.newpipe.extractor.MediaFormat format =
                    formatFromSuffix(entity.getVideoMediaFormatSuffix());
            final boolean hasSeparateAudio = entity.getAudioFilePath() != null;
            final VideoStream stream = new VideoStream.Builder()
                    .setId("cached-video")
                    .setContent("file://" + entity.getVideoFilePath(), true)
                    .setMediaFormat(format)
                    .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod
                            .PROGRESSIVE_HTTP)
                    .setResolution("cached")
                    .setIsVideoOnly(hasSeparateAudio)
                    .build();
            if (hasSeparateAudio) {
                videoOnlyStreams.add(stream);
            } else {
                videoStreams.add(stream);
            }
        }
        info.setVideoStreams(videoStreams);
        info.setVideoOnlyStreams(videoOnlyStreams);

        final List<AudioStream> audioStreams = new ArrayList<>();
        if (entity.getAudioFilePath() != null) {
            final org.schabi.newpipe.extractor.MediaFormat format =
                    formatFromSuffix(entity.getAudioMediaFormatSuffix());
            audioStreams.add(new AudioStream.Builder()
                    .setId("cached-audio")
                    .setContent("file://" + entity.getAudioFilePath(), true)
                    .setMediaFormat(format)
                    .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod
                            .PROGRESSIVE_HTTP)
                    .setAverageBitrate(128)
                    .build());
        }
        info.setAudioStreams(audioStreams);

        info.setRelatedItems(new ArrayList<>());
        info.setSupportComments(false);
        info.setSupportRelatedItems(false);
        return info;
    }

    @Nullable
    private static org.schabi.newpipe.extractor.MediaFormat formatFromSuffix(
            @Nullable final String suffix) {
        if (suffix == null) {
            return null;
        }
        for (final org.schabi.newpipe.extractor.MediaFormat format
                : org.schabi.newpipe.extractor.MediaFormat.values()) {
            if (format.getSuffix().equals(suffix)) {
                return format;
            }
        }
        return null;
    }
}
