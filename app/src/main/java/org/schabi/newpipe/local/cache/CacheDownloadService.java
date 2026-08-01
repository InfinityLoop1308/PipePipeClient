package org.schabi.newpipe.local.cache;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.cache.model.CachedStreamEntity;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads the video/audio files for the "cache for offline viewing" feature
 * (see {@link CacheManager}) into app-private storage and persists a {@link CachedStreamEntity}
 * once done. Runs as a foreground service so long downloads survive the app being backgrounded,
 * mirroring {@link us.shandian.giga.service.DownloadManagerService}.
 */
public final class CacheDownloadService extends Service {
    private static final String TAG = "CacheDownloadService";
    private static final int NOTIFICATION_ID = 0x63616368; // "cach"

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
    private static final String EXTRA_VIDEO_URL = "cache_video_url";
    private static final String EXTRA_VIDEO_FORMAT = "cache_video_format";
    private static final String EXTRA_AUDIO_URL = "cache_audio_url";
    private static final String EXTRA_AUDIO_FORMAT = "cache_audio_format";
    private static final String EXTRA_SEGMENTS = "cache_segments";

    private ExecutorService executor;
    private int pendingJobs = 0;

    public static void enqueue(@NonNull final Context context,
                               @NonNull final StreamInfo info,
                               @Nullable final VideoStream video,
                               @Nullable final AudioStream audio) {
        CacheLogger.d(context, TAG, "enqueue() serviceId=" + info.getServiceId()
                + " url=" + info.getUrl() + " video=" + (video != null) + " audio="
                + (audio != null) + " descriptionLength=" + (info.getDescription() != null
                        ? info.getDescription().getContent().length() : 0));
        final Intent intent = new Intent(context, CacheDownloadService.class);
        intent.putExtra(EXTRA_SERVICE_ID, info.getServiceId());
        intent.putExtra(EXTRA_URL, info.getUrl());
        intent.putExtra(EXTRA_STREAM_ID, info.getId());
        intent.putExtra(EXTRA_TITLE, info.getName());
        intent.putExtra(EXTRA_STREAM_TYPE, info.getStreamType().name());
        intent.putExtra(EXTRA_DURATION, info.getDuration());
        intent.putExtra(EXTRA_UPLOADER_NAME, info.getUploaderName());
        intent.putExtra(EXTRA_UPLOADER_URL, info.getUploaderUrl());
        intent.putExtra(EXTRA_UPLOADER_AVATAR_URL, info.getUploaderAvatarUrl());
        intent.putExtra(EXTRA_THUMBNAIL_URL, info.getThumbnailUrl());
        intent.putExtra(EXTRA_TEXTUAL_UPLOAD_DATE, info.getTextualUploadDate());
        intent.putExtra(EXTRA_VIEW_COUNT, info.getViewCount());
        intent.putExtra(EXTRA_DESCRIPTION, truncateDescription(
                info.getDescription() != null ? info.getDescription().getContent() : null));
        if (video != null) {
            intent.putExtra(EXTRA_VIDEO_URL, video.getContent());
            intent.putExtra(EXTRA_VIDEO_FORMAT,
                    video.getFormat() != null ? video.getFormat().getSuffix() : null);
        }
        if (audio != null) {
            intent.putExtra(EXTRA_AUDIO_URL, audio.getContent());
            intent.putExtra(EXTRA_AUDIO_FORMAT,
                    audio.getFormat() != null ? audio.getFormat().getSuffix() : null);
        }
        intent.putExtra(EXTRA_SEGMENTS,
                CacheManager.serializeSegments(info.getSponsorBlockSegments()));
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (final Exception e) {
            // e.g. android.os.TransactionTooLargeException for a very long description, or
            // ForegroundServiceStartNotAllowedException - either way, don't let the caller crash
            // or the caching attempt disappear without a trace.
            CacheLogger.e(context, TAG, "Failed to start CacheDownloadService for url="
                    + info.getUrl(), e);
            CacheManager.reportProgress(info.getServiceId(), info.getUrl(),
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
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        CacheLogger.d(this, TAG, "onStartCommand() intent=" + (intent == null ? "null"
                : intent.getStringExtra(EXTRA_URL)));
        if (intent == null) {
            return START_NOT_STICKY;
        }
        pendingJobs++;
        startForeground(NOTIFICATION_ID, buildNotification(intent.getStringExtra(EXTRA_TITLE), 0));
        executor.execute(() -> {
            try {
                runJob(intent);
            } catch (final Throwable t) {
                // Must not let anything escape uncaught here: an ExecutorService swallows an
                // uncaught exception from its worker thread silently (just logs it, no crash,
                // no callback) - which is exactly how this used to fail with no visible error
                // at all ("said it started, nothing else"). Always leave a trace and reset the
                // in-progress state instead.
                final int serviceId = intent.getIntExtra(EXTRA_SERVICE_ID, 0);
                final String url = intent.getStringExtra(EXTRA_URL);
                CacheLogger.e(this, TAG, "Uncaught exception while caching url=" + url, t);
                updateNotification(intent.getStringExtra(EXTRA_TITLE), -1);
                if (url != null) {
                    CacheManager.reportProgress(serviceId, url, CacheManager.PROGRESS_FAILED);
                }
            } finally {
                pendingJobs--;
                if (pendingJobs <= 0) {
                    stopForeground(true);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    private void runJob(@NonNull final Intent intent) {
        final int serviceId = intent.getIntExtra(EXTRA_SERVICE_ID, 0);
        final String url = intent.getStringExtra(EXTRA_URL);
        final String streamId = intent.getStringExtra(EXTRA_STREAM_ID);
        final String title = intent.getStringExtra(EXTRA_TITLE);
        CacheLogger.d(this, TAG, "runJob() starting for serviceId=" + serviceId + " url=" + url
                + " streamId=" + streamId + " title=" + title);
        if (url == null || streamId == null || title == null) {
            CacheLogger.e(this, TAG, "runJob() aborting: missing required extra(s) - url="
                    + url + " streamId=" + streamId + " title=" + title, null);
            updateNotification(title, -1);
            if (url != null) {
                CacheManager.reportProgress(serviceId, url, CacheManager.PROGRESS_FAILED);
            }
            return;
        }

        final String videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL);
        final String audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL);
        final String videoFormat = intent.getStringExtra(EXTRA_VIDEO_FORMAT);
        final String audioFormat = intent.getStringExtra(EXTRA_AUDIO_FORMAT);
        CacheLogger.d(this, TAG, "runJob() videoUrl=" + (videoUrl != null) + " audioUrl="
                + (audioUrl != null));

        if (videoUrl == null && audioUrl == null) {
            // Nothing to download - inserting a "complete" DB row here (as this used to do)
            // would create a cached-videos entry that can never actually play. Fail loudly
            // instead so the user sees why, rather than the cache silently doing nothing.
            CacheLogger.e(this, TAG, "runJob() aborting: no video or audio URL to download for "
                    + url, null);
            updateNotification(title, -1);
            CacheManager.reportProgress(serviceId, url, CacheManager.PROGRESS_FAILED);
            return;
        }

        final File dir = CacheManager.getCacheDirFor(this, serviceId, streamId);
        CacheLogger.d(this, TAG, "runJob() dir=" + dir.getAbsolutePath());

        String videoPath = null;
        String audioPath = null;
        long totalBytes = 0;
        try {
            final OkHttpClient client =
                    org.schabi.newpipe.DownloaderImpl.getInstance().getClient();
            // Video gets the first half of the progress range (0-50) and audio the second half
            // (50-100) when both are downloaded, so the reported percent reflects the whole job
            // rather than restarting at 0 for the second file.
            final int videoRangeEnd = audioUrl != null ? 50 : 100;
            if (videoUrl != null) {
                final File videoFile = new File(dir,
                        "video." + (videoFormat != null ? videoFormat : "mp4"));
                totalBytes += download(client, videoUrl, videoFile, title, "video",
                        serviceId, url, 0, videoRangeEnd);
                videoPath = videoFile.getAbsolutePath();
                CacheLogger.d(this, TAG, "runJob() video download complete: "
                        + videoFile.getAbsolutePath() + " (" + videoFile.length() + " bytes)");
            }
            if (audioUrl != null) {
                final File audioFile = new File(dir,
                        "audio." + (audioFormat != null ? audioFormat : "m4a"));
                totalBytes += download(client, audioUrl, audioFile, title, "audio",
                        serviceId, url, videoRangeEnd, 100);
                audioPath = audioFile.getAbsolutePath();
                CacheLogger.d(this, TAG, "runJob() audio download complete: "
                        + audioFile.getAbsolutePath() + " (" + audioFile.length() + " bytes)");
            }
        } catch (final IOException e) {
            CacheLogger.e(this, TAG, "Failed to cache stream " + url, e);
            deletePartial(dir);
            updateNotification(title, -1);
            CacheManager.reportProgress(serviceId, url, CacheManager.PROGRESS_FAILED);
            return;
        }

        final CachedStreamEntity entity = new CachedStreamEntity(
                0,
                serviceId,
                url,
                streamId,
                title,
                intent.getStringExtra(EXTRA_STREAM_TYPE),
                intent.getLongExtra(EXTRA_DURATION, 0),
                intent.getStringExtra(EXTRA_UPLOADER_NAME),
                intent.getStringExtra(EXTRA_UPLOADER_URL),
                intent.getStringExtra(EXTRA_UPLOADER_AVATAR_URL),
                intent.getStringExtra(EXTRA_THUMBNAIL_URL),
                intent.getStringExtra(EXTRA_TEXTUAL_UPLOAD_DATE),
                intent.getLongExtra(EXTRA_VIEW_COUNT, 0),
                intent.getStringExtra(EXTRA_DESCRIPTION),
                videoPath,
                audioPath,
                videoFormat,
                audioFormat,
                intent.getStringExtra(EXTRA_SEGMENTS),
                totalBytes,
                System.currentTimeMillis(),
                true);

        CacheLogger.d(this, TAG, "runJob() all downloads complete for url=" + url
                + ", inserting DB row (" + totalBytes + " bytes total)");
        NewPipeDatabase.getInstance(getApplicationContext()).cachedStreamDAO().insert(entity);
        updateNotification(title, 100);
        CacheManager.reportProgress(serviceId, url, CacheManager.PROGRESS_DONE);
        CacheManager.cacheChanges.onNext(new CacheManager.CacheChangeEvent(serviceId, url, true));
        CacheLogger.d(this, TAG, "runJob() done for url=" + url);
    }

    private long download(@NonNull final OkHttpClient client,
                          @NonNull final String url,
                          @NonNull final File target,
                          @NonNull final String title,
                          @NonNull final String label,
                          final int serviceId,
                          @NonNull final String streamUrl,
                          final int rangeStart,
                          final int rangeEnd) throws IOException {
        final Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    // Some CDNs (notably googlevideo) reject or throttle requests without a
                    // browser-ish User-Agent, and DownloaderImpl only adds one on its own
                    // request path - this call bypasses that and uses the raw OkHttp client.
                    .header("User-Agent", org.schabi.newpipe.DownloaderImpl.USER_AGENT)
                    .get()
                    .build();
        } catch (final IllegalArgumentException e) {
            // OkHttp throws this (unchecked!) when the content isn't a parseable http(s) URL,
            // e.g. an inline DASH/HLS manifest document. Convert it to a checked IOException so
            // it travels the normal failure path instead of silently killing the worker thread.
            throw new IOException("Stream content for " + label + " is not a usable URL: "
                    + abbreviate(url), e);
        }

        try (Response response = client.newCall(request).execute()) {
            final ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code() + " while caching " + label
                        + " from " + abbreviate(url));
            }
            final long contentLength = body.contentLength();
            long readBytes = 0;
            int lastReportedPercent = -1;
            try (InputStream input = body.byteStream();
                OutputStream output = new FileOutputStream(target)) {
                final byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    readBytes += read;
                    if (contentLength > 0) {
                        final int filePercent = (int) (readBytes * 100 / contentLength);
                        final int overallPercent = rangeStart
                                + filePercent * (rangeEnd - rangeStart) / 100;
                        if (filePercent != lastReportedPercent) {
                            lastReportedPercent = filePercent;
                            updateNotification(title, overallPercent);
                            CacheManager.reportProgress(serviceId, streamUrl, overallPercent);
                        }
                    }
                }
            }
            if (readBytes == 0) {
                throw new IOException("Cached " + label + " came back empty (0 bytes) from "
                        + abbreviate(url));
            }
            CacheLogger.d(this, TAG, "downloaded " + label + ": " + readBytes + " bytes"
                    + (contentLength > 0 ? " of " + contentLength + " expected" : ""));
            if (contentLength > 0 && readBytes < contentLength) {
                throw new IOException("Cached " + label + " is truncated: got " + readBytes
                        + " of " + contentLength + " bytes");
            }
            return readBytes;
        }
    }

    /** Media URLs are enormous and full of tokens; keep the log readable and less sensitive. */
    @NonNull
    private static String abbreviate(@NonNull final String url) {
        return url.length() <= 120 ? url : url.substring(0, 120) + "…(" + url.length() + " chars)";
    }

    private void deletePartial(@NonNull final File dir) {
        final File[] files = dir.listFiles();
        if (files != null) {
            for (final File file : files) {
                file.delete();
            }
        }
        dir.delete();
    }

    @NonNull
    private Notification buildNotification(@Nullable final String title, final int percent) {
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
        } else {
            builder.setProgress(100, percent, percent == 0);
        }
        return builder.build();
    }

    private void updateNotification(@Nullable final String title, final int percent) {
        final NotificationManager manager = ContextCompat.getSystemService(this,
                NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(title, percent));
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
        executor.shutdownNow();
    }
}
