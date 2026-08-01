package org.schabi.newpipe.local.cache;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

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
        intent.putExtra(EXTRA_DESCRIPTION,
                info.getDescription() != null ? info.getDescription().getContent() : null);
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
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        pendingJobs++;
        startForeground(NOTIFICATION_ID, buildNotification(intent.getStringExtra(EXTRA_TITLE), 0));
        executor.execute(() -> {
            try {
                runJob(intent);
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
        if (url == null || streamId == null || title == null) {
            return;
        }

        final File dir = CacheManager.getCacheDirFor(this, serviceId, streamId);
        final String videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL);
        final String audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL);
        final String videoFormat = intent.getStringExtra(EXTRA_VIDEO_FORMAT);
        final String audioFormat = intent.getStringExtra(EXTRA_AUDIO_FORMAT);

        String videoPath = null;
        String audioPath = null;
        long totalBytes = 0;
        try {
            final OkHttpClient client =
                    org.schabi.newpipe.DownloaderImpl.getInstance().getClient();
            if (videoUrl != null) {
                final File videoFile = new File(dir,
                        "video." + (videoFormat != null ? videoFormat : "mp4"));
                totalBytes += download(client, videoUrl, videoFile, title, "video");
                videoPath = videoFile.getAbsolutePath();
            }
            if (audioUrl != null) {
                final File audioFile = new File(dir,
                        "audio." + (audioFormat != null ? audioFormat : "m4a"));
                totalBytes += download(client, audioUrl, audioFile, title, "audio");
                audioPath = audioFile.getAbsolutePath();
            }
        } catch (final IOException e) {
            Log.e(TAG, "Failed to cache stream " + url, e);
            deletePartial(dir);
            updateNotification(title, -1);
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

        NewPipeDatabase.getInstance(getApplicationContext()).cachedStreamDAO().insert(entity);
        updateNotification(title, 100);
    }

    private long download(@NonNull final OkHttpClient client,
                          @NonNull final String url,
                          @NonNull final File target,
                          @NonNull final String title,
                          @NonNull final String label) throws IOException {
        final Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            final ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code() + " while caching " + label);
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
                        final int percent = (int) (readBytes * 100 / contentLength);
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent;
                            updateNotification(title, percent);
                        }
                    }
                }
            }
            return readBytes;
        }
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
