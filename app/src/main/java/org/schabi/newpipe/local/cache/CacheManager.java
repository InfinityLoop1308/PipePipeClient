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
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
     * Picks the default video (preferring a video-only stream, so the smaller separately-muxed
     * audio track can be reused) and audio stream for caching, mirroring the same
     * {@link org.schabi.newpipe.util.ListHelper} logic used to choose the default playback
     * quality, and starts a background download of both plus the sponsor segments already
     * present on {@code info}.
     */
    public static void startCaching(@NonNull final Context context,
                                    @NonNull final StreamInfo info) {
        final Context appContext = context.getApplicationContext();
        final List<VideoStream> videoCandidates = !info.getVideoOnlyStreams().isEmpty()
                ? info.getVideoOnlyStreams() : info.getVideoStreams();
        final int videoIndex = org.schabi.newpipe.util.ListHelper
                .getDefaultResolutionIndex(appContext, videoCandidates);
        final VideoStream video = videoIndex >= 0 && videoIndex < videoCandidates.size()
                ? videoCandidates.get(videoIndex) : null;

        final List<AudioStream> audioStreams = info.getAudioStreams();
        final int audioIndex = org.schabi.newpipe.util.ListHelper
                .getDefaultAudioFormat(appContext, audioStreams);
        final AudioStream audio = audioIndex >= 0 && audioIndex < audioStreams.size()
                ? audioStreams.get(audioIndex) : null;

        CacheDownloadService.enqueue(appContext, info, video, audio);
    }

    public static void removeCache(@NonNull final Context context,
                                   @NonNull final CachedStreamEntity entity) {
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
        final CachedStreamEntity entity =
                findCachedStream(context, serviceId, url).blockingGet();
        return entity != null && entity.isComplete();
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
