package org.schabi.newpipe.local.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.DialogCacheBinding;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.SecondaryStreamHelper;
import org.schabi.newpipe.util.StreamItemAdapter;
import org.schabi.newpipe.util.StreamItemAdapter.StreamSizeWrapper;

import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import us.shandian.giga.get.SabrDownloadStreamHelper;

/**
 * Quality selector for the "cache for offline viewing" feature, mirroring what the regular
 * download dialog offers (video quality vs audio-only, with file sizes filled in asynchronously)
 * but listing <em>only</em> the streams the cache downloader can actually fetch - see
 * {@link CacheManager#isCacheable}.
 */
public final class CacheDialog {

    private static final String TAG = "CacheDialog";

    private CacheDialog() {
    }

    /**
     * Shows the quality selector, or - if this video has nothing cacheable at all - a toast
     * explaining why instead of a dialog with an empty list.
     *
     * @return {@code true} if the dialog was shown.
     */
    public static boolean show(@NonNull final Context context, @NonNull final StreamInfo info) {
        if (useDefaultQuality(context)) {
            // "Cache at the default quality": no selector at all - CacheManager picks the same
            // default resolution/format the selector would have pre-selected.
            final boolean started = CacheManager.startCaching(context, info);
            Toast.makeText(context,
                    started ? R.string.cache_started : R.string.cache_failed_no_streams,
                    started ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            return started;
        }

        final List<VideoStream> videoStreams =
                CacheManager.getCacheableVideoStreams(context, info);
        final List<AudioStream> audioStreams = CacheManager.getCacheableAudioStreams(info);

        if (videoStreams.isEmpty() && audioStreams.isEmpty()) {
            Toast.makeText(context, R.string.cache_failed_no_streams, Toast.LENGTH_LONG).show();
            return false;
        }

        final StreamSizeWrapper<VideoStream> wrappedVideo =
                new StreamSizeWrapper<>(videoStreams, context);
        final StreamSizeWrapper<AudioStream> wrappedAudio =
                new StreamSizeWrapper<>(audioStreams, context);

        // Pair each video-only entry with the audio track that will be cached alongside it, so
        // the spinner can show the combined size - exactly like the download dialog does.
        final SparseArray<SecondaryStreamHelper<AudioStream>> secondaryStreams =
                new SparseArray<>(4);
        for (int i = 0; i < videoStreams.size(); i++) {
            if (!videoStreams.get(i).isVideoOnly()) {
                continue;
            }
            // SABR video must be paired with SABR audio (and vice versa) - the same constraint
            // the download dialog enforces, otherwise the mission can't assemble the two.
            final AudioStream audioStream = SecondaryStreamHelper.getAudioStreamFor(
                    context,
                    SabrDownloadStreamHelper.audioStreamsForVideo(audioStreams,
                            videoStreams.get(i)),
                    videoStreams.get(i));
            if (audioStream != null && SabrDownloadStreamHelper
                    .isCompatibleSecondaryStream(videoStreams.get(i), audioStream)) {
                secondaryStreams.append(i,
                        new SecondaryStreamHelper<>(wrappedAudio, audioStream));
            }
        }

        final StreamItemAdapter<VideoStream, AudioStream> videoAdapter =
                new StreamItemAdapter<>(context, wrappedVideo, secondaryStreams);
        final StreamItemAdapter<AudioStream, Stream> audioAdapter =
                new StreamItemAdapter<>(context, wrappedAudio);

        final DialogCacheBinding binding =
                DialogCacheBinding.inflate(LayoutInflater.from(context));

        // Default to whichever kind is actually available, preferring video.
        final boolean startWithVideo = !videoStreams.isEmpty();
        binding.cacheKindVideo.setEnabled(!videoStreams.isEmpty());
        binding.cacheKindAudio.setEnabled(!audioStreams.isEmpty());
        binding.cacheKindVideo.setChecked(startWithVideo);
        binding.cacheKindAudio.setChecked(!startWithVideo);

        if (videoStreams.isEmpty()) {
            showHint(binding, R.string.cache_hint_no_video);
        } else if (audioStreams.isEmpty()) {
            showHint(binding, R.string.cache_hint_no_audio);
        } else if (videoStreams.size() < info.getVideoStreams().size()
                + info.getVideoOnlyStreams().size()) {
            showHint(binding, R.string.cache_hint_reduced);
        }

        if (startWithVideo) {
            binding.cacheQualitySpinner.setAdapter(videoAdapter);
        } else {
            binding.cacheQualitySpinner.setAdapter(audioAdapter);
        }
        selectDefault(context, binding, startWithVideo, videoStreams, audioStreams);

        // The same two options as Settings -> Downloads -> Caching, so they can be turned on
        // from the place where their effect is obvious. They are written straight through to the
        // preference, exactly as the settings switches do; "always use the default quality"
        // therefore takes effect from the next time this dialog would have been shown.
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String defaultQualityKey =
                context.getString(R.string.cache_use_default_quality_key);
        final String autoRemoveKey =
                context.getString(R.string.cache_auto_remove_after_watching_key);

        binding.cacheUseDefaultQuality.setChecked(prefs.getBoolean(defaultQualityKey, false));
        binding.cacheUseDefaultQuality.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(defaultQualityKey, checked).apply());

        binding.cacheAutoRemove.setChecked(prefs.getBoolean(autoRemoveKey, false));
        binding.cacheAutoRemove.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(autoRemoveKey, checked).apply());

        binding.cacheKindGroup.setOnCheckedChangeListener((group, checkedId) -> {
            final boolean video = checkedId == R.id.cache_kind_video;
            if (video) {
                binding.cacheQualitySpinner.setAdapter(videoAdapter);
            } else {
                binding.cacheQualitySpinner.setAdapter(audioAdapter);
            }
            selectDefault(context, binding, video, videoStreams, audioStreams);
        });

        final CompositeDisposable disposables = new CompositeDisposable();
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.cache_dialog_title)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.cache_action, (d, which) -> {
                    final boolean video = binding.cacheKindVideo.isChecked();
                    final int position = binding.cacheQualitySpinner.getSelectedItemPosition();
                    startCaching(context, info, video, position,
                            videoStreams, audioStreams, secondaryStreams);
                })
                .create();
        dialog.setOnDismissListener(d -> disposables.dispose());
        dialog.show();

        // Fill in the "(12.3 MB)" part of each entry once the sizes come back.
        disposables.add(StreamSizeWrapper.fetchSizeForWrapper(wrappedVideo)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> videoAdapter.notifyDataSetChanged(),
                        e -> Log.e(TAG, "failed to fetch video stream sizes", e)));
        disposables.add(StreamSizeWrapper.fetchSizeForWrapper(wrappedAudio)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> audioAdapter.notifyDataSetChanged(),
                        e -> Log.e(TAG, "failed to fetch audio stream sizes", e)));
        return true;
    }

    /**
     * Whether "Cache at the default quality" (Settings -> Downloads -> Caching) is on, in which
     * case {@link #show} caches immediately instead of asking.
     */
    private static boolean useDefaultQuality(@NonNull final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.cache_use_default_quality_key), false);
    }

    private static void showHint(@NonNull final DialogCacheBinding binding, final int stringRes) {
        binding.cacheHint.setText(stringRes);
        binding.cacheHint.setVisibility(View.VISIBLE);
    }

    private static void selectDefault(@NonNull final Context context,
                                      @NonNull final DialogCacheBinding binding,
                                      final boolean video,
                                      @NonNull final List<VideoStream> videoStreams,
                                      @NonNull final List<AudioStream> audioStreams) {
        final int index = video
                ? ListHelper.getDefaultResolutionIndex(context, videoStreams)
                : ListHelper.getDefaultAudioFormat(context, audioStreams);
        final int count = video ? videoStreams.size() : audioStreams.size();
        if (index >= 0 && index < count) {
            binding.cacheQualitySpinner.setSelection(index);
        }
    }

    private static void startCaching(
            @NonNull final Context context,
            @NonNull final StreamInfo info,
            final boolean video,
            final int position,
            @NonNull final List<VideoStream> videoStreams,
            @NonNull final List<AudioStream> audioStreams,
            @NonNull final SparseArray<SecondaryStreamHelper<AudioStream>> secondaryStreams) {

        final VideoStream selectedVideo;
        final AudioStream selectedAudio;
        if (video) {
            if (position < 0 || position >= videoStreams.size()) {
                Toast.makeText(context, R.string.cache_failed_no_streams, Toast.LENGTH_LONG).show();
                return;
            }
            selectedVideo = videoStreams.get(position);
            // A video-only track needs its separate audio cached too, otherwise offline
            // playback would be silent. A muxed stream already contains audio.
            final SecondaryStreamHelper<AudioStream> secondary = secondaryStreams.get(position);
            selectedAudio = selectedVideo.isVideoOnly() && secondary != null
                    ? secondary.getStream() : null;
        } else {
            if (position < 0 || position >= audioStreams.size()) {
                Toast.makeText(context, R.string.cache_failed_no_streams, Toast.LENGTH_LONG).show();
                return;
            }
            selectedVideo = null;
            selectedAudio = audioStreams.get(position);
        }

        final boolean started =
                CacheManager.startCaching(context, info, selectedVideo, selectedAudio);
        Toast.makeText(context,
                started ? R.string.cache_started : R.string.cache_failed_no_streams,
                started ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
    }
}
