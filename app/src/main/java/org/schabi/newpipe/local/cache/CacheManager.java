package org.schabi.newpipe.local.cache;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.database.cache.dao.CachedStreamDAO;
import org.schabi.newpipe.database.cache.model.CachedStreamEntity;
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
     * Records and broadcasts a download progress update. A {@code percent} of
     * {@link #PROGRESS_FAILED} or {@link #PROGRESS_DONE} clears the in-progress marker for this
     * stream (the download is no longer "in progress" either way).
     */
    public static void reportProgress(final int serviceId, @NonNull final String url,
                                      final int percent) {
        final String key = cacheKey(serviceId, url);
        if (percent < 0 || percent >= PROGRESS_DONE) {
            PROGRESS_BY_KEY.remove(key);
        } else {
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
     * Whether {@link CacheDownloadService} can actually fetch this stream with a plain HTTP GET.
     *
     * <p>This is the crux of the "I pressed Cache, it said it started, and nothing happened" bug:
     * the cache downloader streams {@link Stream#getContent()} straight to a file over OkHttp,
     * which only works for a stream whose content really is a direct media URL. Two other kinds
     * exist and both fail:</p>
     * <ul>
     *   <li>{@code !isUrl()} - the content is an <em>inline manifest document</em> (DASH/HLS/SMIL
     *       text), not a link. Handing that to {@code Request.Builder().url(...)} throws
     *       {@link IllegalArgumentException}, an unchecked exception that used to escape the
     *       download worker thread and get swallowed by its {@code ExecutorService} - no crash,
     *       no error, no notification, nothing.</li>
     *   <li>{@link DeliveryMethod#SABR} (YouTube's current default) and
     *       {@link DeliveryMethod#HLS}/{@link DeliveryMethod#DASH} URLs - fetchable, but what
     *       comes back is a manifest/segment-protocol endpoint, not the media itself. Saving it
     *       would produce a small file that can never play, i.e. a cache entry that silently
     *       does nothing useful.</li>
     * </ul>
     *
     * <p>The regular download feature copes with these via the whole
     * {@code us.shandian.giga} mission machinery (SABR/HLS-aware, multi-threaded, resumable);
     * the cache deliberately does not reimplement that, so it restricts itself to streams it can
     * genuinely handle and says so plainly when there are none.</p>
     */
    public static boolean isCacheable(@Nullable final Stream stream) {
        return stream != null
                && stream.isUrl()
                && stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP;
    }

    /**
     * The video streams that can actually be cached, best quality first, in the same order and
     * with the same language filtering the download dialog uses.
     */
    @NonNull
    public static List<VideoStream> getCacheableVideoStreams(@NonNull final Context context,
                                                             @NonNull final StreamInfo info) {
        final List<VideoStream> sorted = new ArrayList<>(org.schabi.newpipe.util.ListHelper
                .getSortedStreamVideosList(context, info.getVideoStreams(),
                        info.getVideoOnlyStreams(), false, false));
        final List<VideoStream> cacheable = new ArrayList<>();
        for (final VideoStream stream : sorted) {
            if (isCacheable(stream)) {
                cacheable.add(stream);
            }
        }
        return cacheable;
    }

    /** The audio streams that can actually be cached. */
    @NonNull
    public static List<AudioStream> getCacheableAudioStreams(@NonNull final StreamInfo info) {
        final List<AudioStream> cacheable = new ArrayList<>();
        for (final AudioStream stream : info.getAudioStreams()) {
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

        if (!isCacheable(video) && !isCacheable(audio)) {
            CacheLogger.w(appContext, "startCaching",
                    "nothing cacheable was selected for " + info.getUrl()
                            + " - available streams: " + describeStreams(info));
            return false;
        }

        reportProgress(info.getServiceId(), info.getUrl(), 0);
        CacheDownloadService.enqueue(appContext, info,
                isCacheable(video) ? video : null,
                isCacheable(audio) ? audio : null);
        return true;
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

    public static void removeCache(@NonNull final Context context,
                                   @NonNull final CachedStreamEntity entity) {
        CacheLogger.d(context, "removeCache", "removing serviceId=" + entity.getServiceId()
                + " url=" + entity.getUrl());
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
        if (getProgressBlocking(serviceId, url) >= 0) {
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
