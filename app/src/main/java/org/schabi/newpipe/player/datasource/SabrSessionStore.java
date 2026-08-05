package org.schabi.newpipe.player.datasource;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.App;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.PlaybackStartupTrace;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.youtube.LocalDomPoTokenProvider;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/** Prepares SABR source data and retains a small LRU of Extractor protocol sessions. */
public final class SabrSessionStore {
    private static final int MAX_WARM_ENTRIES = 32;
    private static final int MAX_SESSIONS = 8;
    private static final ExecutorService WARM_EXECUTOR = Executors.newFixedThreadPool(2,
            runnable -> daemonThread(runnable, "SabrAdaptivePrewarm"));
    private static final Map<String, Future<byte[]>> WARM_ENTRIES =
            Collections.synchronizedMap(new LinkedHashMap<String, Future<byte[]>>(
                    MAX_WARM_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<String, Future<byte[]>> eldest) {
                    return size() > MAX_WARM_ENTRIES;
                }
            });
    private static final Map<String, YoutubeSabrSession> SESSIONS =
            new LinkedHashMap<String, YoutubeSabrSession>(MAX_SESSIONS + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<String, YoutubeSabrSession> eldest) {
                    return size() > MAX_SESSIONS;
                }
            };
    private static volatile LocalDomPoTokenProvider sharedProvider;

    private SabrSessionStore() {
    }

    private static Thread daemonThread(final Runnable runnable, final String name) {
        final Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @NonNull
    private static LocalDomPoTokenProvider provider(@NonNull final Context context) {
        LocalDomPoTokenProvider result = sharedProvider;
        if (result != null) return result;
        synchronized (SabrSessionStore.class) {
            if (sharedProvider == null) {
                sharedProvider = new LocalDomPoTokenProvider(context.getApplicationContext());
            }
            return sharedProvider;
        }
    }

    @NonNull
    public static SabrSourceSpec createSourceSpec(@NonNull final String videoId,
                                                  final int preferredVideoItag,
                                                  @NonNull final List<AudioStream> audioStreams,
                                                  @Nullable final YoutubeSabrInfo extractorInfo)
            throws IOException, ExtractionException {
        PlaybackStartupTrace.markForVideoId(videoId, "sabr_source_spec_started");
        if (!isUsableExtractorInfo(extractorInfo, videoId)) {
            throw new IOException("SABR extractor info is missing for " + videoId);
        }
        final YoutubeSabrInfo info = Objects.requireNonNull(extractorInfo);
        final AudioSelection audio = selectAudioGroup(App.getApp(), info, audioStreams);
        final YoutubeSabrInfo.Format preferredVideo = pickVideoFormat(info, preferredVideoItag);
        if (audio == null || preferredVideo == null) {
            throw new IOException("Could not select SABR formats for " + videoId);
        }
        final List<YoutubeSabrInfo.Format> videoFormats =
                Collections.singletonList(preferredVideo);
        final YoutubeSabrInfo.Format videoBootstrap = preferredVideo;
        final String key = warmKey(info);
        final byte[] warmedPoToken = takeWarmedPoToken(key, videoId);
        final LocalDomPoTokenProvider tokenProvider = provider(App.getApp());
        final byte[] poToken = warmedPoToken == null
                ? tokenProvider.getPoToken(info) : warmedPoToken;
        PlaybackStartupTrace.markForVideoId(videoId, "sabr_source_spec_ready");
        return new SabrSourceSpec(videoId, info, poToken,
                audio.bootstrapFormat, audio.formats, videoFormats, videoBootstrap,
                null, null, null, null, Collections.emptyList());
    }

    public static void prewarm(@NonNull final Context context, @NonNull final StreamInfo streamInfo,
                               @NonNull final VideoStream selectedStream) {
        if (selectedStream.getDeliveryMethod() != DeliveryMethod.SABR
                || !(selectedStream.getDeliveryMethodInfo() instanceof YoutubeSabrInfo)) return;
        final YoutubeSabrInfo info = (YoutubeSabrInfo) selectedStream.getDeliveryMethodInfo();
        if (!isUsableExtractorInfo(info, streamInfo.getId())) return;
        final AudioSelection audio = selectAudioGroup(context, info, streamInfo.getAudioStreams());
        final YoutubeSabrInfo.Format video = pickVideoFormat(info, selectedStream.getItag());
        if (audio == null || video == null) return;
        final String key = warmKey(info);
        synchronized (WARM_ENTRIES) {
            if (WARM_ENTRIES.containsKey(key)) return;
            final FutureTask<byte[]> task = new FutureTask<>(() ->
                    provider(context).getPoToken(info));
            WARM_ENTRIES.put(key, task);
            WARM_EXECUTOR.execute(task);
        }
    }

    @NonNull
    static YoutubeSabrSession getOrCreateSession(@NonNull final Context context,
                                                 @NonNull final SabrSourceSpec spec)
            throws IOException, ExtractionException {
        final String key = sessionKey(spec.getInfo());
        final YoutubeSabrSession cached = getSession(key);
        if (cached != null) return cached;
        final File spool = new File(context.getCacheDir(),
                "sabr-segments/" + spec.getVideoId() + '-' + System.nanoTime());
        final YoutubeSabrSession created = new YoutubeSabrSession(spec.getInfo(), null, null, spool);
        final LocalDomPoTokenProvider tokenProvider = provider(context);
        created.setPoTokenRefresher(() -> tokenProvider.getPoToken(spec.getInfo()));
        created.setIdentityRefresher(() -> refreshIdentity(context, spec.getInfo()));
        final byte[] token = spec.getPoToken();
        if (token == null || token.length == 0) {
            throw new SabrLogicException("SABR PO token provider returned no token for video="
                    + spec.getVideoId());
        }
        created.setPoToken(token);
        return cacheSession(key, created);
    }

    @NonNull
    private static YoutubeSabrSession.SessionIdentity refreshIdentity(
            @NonNull final Context context, @NonNull final YoutubeSabrInfo rejectedInfo)
            throws IOException, ExtractionException {
        final StreamInfo refreshed = StreamInfo.getInfo(ServiceList.YouTube,
                "https://www.youtube.com/watch?v=" + rejectedInfo.getVideoId());
        YoutubeSabrInfo freshInfo = null;
        for (final VideoStream stream : refreshed.getVideoOnlyStreams()) {
            if (stream.getDeliveryMethod() == DeliveryMethod.SABR
                    && stream.getDeliveryMethodInfo() instanceof YoutubeSabrInfo) {
                freshInfo = (YoutubeSabrInfo) stream.getDeliveryMethodInfo();
                break;
            }
        }
        if (freshInfo == null) {
            for (final AudioStream stream : refreshed.getAudioStreams()) {
                if (stream.getDeliveryMethod() == DeliveryMethod.SABR
                        && stream.getDeliveryMethodInfo() instanceof YoutubeSabrInfo) {
                    freshInfo = (YoutubeSabrInfo) stream.getDeliveryMethodInfo();
                    break;
                }
            }
        }
        if (freshInfo == null) {
            throw new SabrLogicException("Refreshed player response has no SABR identity for "
                    + rejectedInfo.getVideoId());
        }
        final byte[] token = provider(context).getPoToken(freshInfo);
        if (token == null || token.length == 0) {
            throw new SabrLogicException("Refreshed SABR identity returned no PO token for "
                    + rejectedInfo.getVideoId());
        }
        return new YoutubeSabrSession.SessionIdentity(freshInfo, token);
    }

    @Nullable
    private static synchronized YoutubeSabrSession getSession(@NonNull final String key) {
        return SESSIONS.get(key);
    }

    @NonNull
    private static synchronized YoutubeSabrSession cacheSession(
            @NonNull final String key, @NonNull final YoutubeSabrSession session) {
        final YoutubeSabrSession existing = SESSIONS.get(key);
        if (existing != null) return existing;
        SESSIONS.put(key, session);
        return session;
    }

    @Nullable
    private static byte[] takeWarmedPoToken(@NonNull final String key,
                                           @NonNull final String videoId)
            throws IOException, ExtractionException {
        final Future<byte[]> future;
        synchronized (WARM_ENTRIES) {
            future = WARM_ENTRIES.remove(key);
        }
        if (future == null) return null;
        try {
            final byte[] poToken = future.get();
            return poToken == null ? null : poToken.clone();
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted awaiting SABR prewarm for " + videoId, error);
        } catch (final ExecutionException error) {
            // Prewarm is opportunistic. A failed task must not poison the real resolve path.
            return null;
        }
    }

    private static boolean isUsableExtractorInfo(@Nullable final YoutubeSabrInfo info,
                                                 @NonNull final String videoId) {
        return info != null && videoId.equals(info.getVideoId())
                && info.getServerAbrStreamingUrl() != null
                && !info.getServerAbrStreamingUrl().isEmpty() && !info.getFormats().isEmpty();
    }

    @Nullable
    private static AudioSelection selectAudioGroup(@NonNull final Context context,
                                                    @NonNull final YoutubeSabrInfo info,
                                                    @NonNull final List<AudioStream> streams) {
        final List<AudioStream> candidates = new ArrayList<>();
        for (final AudioStream stream : streams) {
            if (stream.getDeliveryMethod() == DeliveryMethod.SABR
                    && stream.getDeliveryMethodInfo() instanceof YoutubeSabrInfo
                    && info.getVideoId().equals(((YoutubeSabrInfo)
                    stream.getDeliveryMethodInfo()).getVideoId())) {
                candidates.add(stream);
            }
        }
        final int selectedIndex = ListHelper.getDefaultAudioFormat(context, candidates);
        if (selectedIndex < 0 || selectedIndex >= candidates.size()) return null;
        final AudioStream selected = candidates.get(selectedIndex);
        final String selectedCodec = codecGroup(selected);
        final List<YoutubeSabrInfo.Format> formats = new ArrayList<>();
        for (final AudioStream stream : candidates) {
            if (!selectedCodec.equals(codecGroup(stream))) continue;
            final YoutubeSabrInfo.Format format = findAudioFormat(info, stream);
            if (format != null && !formats.contains(format)) formats.add(format);
        }
        final YoutubeSabrInfo.Format bootstrap = findAudioFormat(info, selected);
        if (bootstrap == null || formats.isEmpty()) return null;
        formats.sort(Comparator
                .comparingInt((YoutubeSabrInfo.Format format) ->
                        Objects.equals(format.getAudioTrackId(), bootstrap.getAudioTrackId())
                                ? 0 : 1)
                .thenComparing(format ->
                        Objects.toString(format.getAudioTrackDisplayName(), ""))
                .thenComparingInt(YoutubeSabrInfo.Format::getBitrate));
        return new AudioSelection(bootstrap, formats);
    }

    @Nullable
    private static YoutubeSabrInfo.Format findAudioFormat(
            @NonNull final YoutubeSabrInfo info, @NonNull final AudioStream stream) {
        for (final YoutubeSabrInfo.Format format : info.getFormats()) {
            if (format.isAudio() && format.getItag() == stream.getItag()
                    && Objects.equals(format.getAudioTrackId(), stream.getAudioTrackId())) {
                return format;
            }
        }
        return null;
    }

    @NonNull
    private static String codecGroup(@NonNull final AudioStream stream) {
        final String codec = stream.getCodec();
        if (codec == null || codec.isEmpty()) {
            return Objects.toString(stream.getFormat(), "unknown");
        }
        final int separator = codec.indexOf('.');
        return (separator < 0 ? codec : codec.substring(0, separator))
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static final class AudioSelection {
        @NonNull private final YoutubeSabrInfo.Format bootstrapFormat;
        @NonNull private final List<YoutubeSabrInfo.Format> formats;

        AudioSelection(@NonNull final YoutubeSabrInfo.Format bootstrapFormat,
                       @NonNull final List<YoutubeSabrInfo.Format> formats) {
            this.bootstrapFormat = bootstrapFormat;
            this.formats = formats;
        }
    }

    private static YoutubeSabrInfo.Format pickVideoFormat(@NonNull final YoutubeSabrInfo info,
                                                           final int preferredItag) {
        for (final YoutubeSabrInfo.Format format : info.getFormats()) {
            if (format.isVideo() && format.getItag() == preferredItag) return format;
        }
        YoutubeSabrInfo.Format lowest = null;
        for (final YoutubeSabrInfo.Format format : info.getFormats()) {
            if (!format.isVideo()) continue;
            if (lowest == null || format.getHeight() < lowest.getHeight()
                    || format.getHeight() == lowest.getHeight()
                    && format.getBitrate() < lowest.getBitrate()) {
                lowest = format;
            }
        }
        return lowest;
    }

    @NonNull
    private static String warmKey(@NonNull final YoutubeSabrInfo info) {
        return Objects.requireNonNull(info.getServerAbrStreamingUrl());
    }

    @NonNull
    private static String sessionKey(@NonNull final YoutubeSabrInfo info) {
        return warmKey(info);
    }

}
