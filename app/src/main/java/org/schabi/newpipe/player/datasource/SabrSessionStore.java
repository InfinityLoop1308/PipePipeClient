package org.schabi.newpipe.player.datasource;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrPoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one shared {@link YoutubeSabrSession} per videoId so the audio and video
 * {@link SabrDataSource}s drive the same session (a single SABR response carries both formats, so
 * the session's segment cache serves both without doubling bandwidth).
 *
 * <p>v1: uses the best audio/video formats from the player response and a fixed en/US locale.</p>
 */
public final class SabrSessionStore {

    private static final Map<String, Holder> SESSIONS = new ConcurrentHashMap<>();
    // Keep only the few most-recent sessions so the map + per-video segment caches + pump threads
    // don't accumulate forever as the user browses videos. Mutated only under the class lock.
    private static final int MAX_SESSIONS = 3;
    private static final java.util.Deque<String> ORDER = new java.util.ArrayDeque<>();
    // Shared across videos so the PO-token cache (videoId-keyed, ~6h) is reused and a single
    // WebView is held instead of one per video.
    private static volatile WebViewPoTokenProvider sharedProvider;

    private SabrSessionStore() {
    }

    @NonNull
    private static WebViewPoTokenProvider provider(@NonNull final Context context) {
        WebViewPoTokenProvider p = sharedProvider;
        if (p == null) {
            synchronized (SabrSessionStore.class) {
                p = sharedProvider;
                if (p == null) {
                    p = new WebViewPoTokenProvider(context.getApplicationContext());
                    sharedProvider = p;
                }
            }
        }
        return p;
    }

    /** Bundle of the session and its selected formats for a given video. */
    public static final class Holder {
        @NonNull public final String videoId;
        @NonNull public final YoutubeSabrInfo info;
        @NonNull public final YoutubeSabrSession session;
        @NonNull public final YoutubeSabrFormat audioFormat;
        @NonNull public final YoutubeSabrFormat videoFormat;

        // Real playback position (ms); written by the player loop. Kept for reference but NOT used to
        // drive the pump/eviction: it freezes when the player buffers, which deadlocked everything.
        private volatile long playerTimeMs;
        // What each track's data source has actually read (segment end ms). This is the truth the pump
        // and eviction run on: it never goes stale (a stalled reader sits on its last segment, so the
        // pump sees edge ~= readerHead and keeps feeding instead of pacing off a frozen play head).
        private final Map<Integer, Long> readerPositions = new ConcurrentHashMap<>();
        private volatile SabrStreamPump pump;

        Holder(@NonNull final String videoId,
               @NonNull final YoutubeSabrInfo info,
               @NonNull final YoutubeSabrSession session,
               @NonNull final YoutubeSabrFormat audioFormat,
               @NonNull final YoutubeSabrFormat videoFormat) {
            this.videoId = videoId;
            this.info = info;
            this.session = session;
            this.audioFormat = audioFormat;
            this.videoFormat = videoFormat;
        }

        public long getPlayerTimeMs() {
            return playerTimeMs;
        }

        void setPlayerTimeMs(final long playerTimeMs) {
            this.playerTimeMs = playerTimeMs;
        }

        /** A data source reports how far it has read (last served segment end, ms). */
        public void setReaderPositionMs(final int itag, final long ms) {
            readerPositions.put(itag, ms);
        }

        /** Furthest-read track: the pump keeps the buffered edge a cushion ahead of THIS. */
        public long getReaderHeadMs() {
            long head = 0;
            final Long a = readerPositions.get(audioFormat.getItag());
            final Long v = readerPositions.get(videoFormat.getItag());
            if (a != null) {
                head = Math.max(head, a);
            }
            if (v != null) {
                head = Math.max(head, v);
            }
            return head;
        }

        /** Slowest-read track: nothing before this is needed any more, so eviction starts here. Zero
         * until BOTH tracks have read something (else we'd evict the other track's unread segments). */
        public long getReaderTailMs() {
            final Long a = readerPositions.get(audioFormat.getItag());
            final Long v = readerPositions.get(videoFormat.getItag());
            if (a == null || v == null) {
                return 0;
            }
            return Math.min(a, v);
        }

        /** Lazily create the single background pump that feeds both data sources for this video. */
        synchronized SabrStreamPump getPump(@NonNull final Localization localization) {
            if (pump == null) {
                pump = new SabrStreamPump(session, this, localization);
            }
            return pump;
        }

        boolean isBeyondEnd(@NonNull final SabrSegmentRequest request) {
            return session.isBeyondEnd(request);
        }
    }

    // Report the real playback position; no-op when the video has no live SABR session.
    public static void updatePlayerTime(@NonNull final String videoId, final long playerTimeMs) {
        final Holder holder = SESSIONS.get(videoId);
        if (holder != null && playerTimeMs >= 0) {
            holder.setPlayerTimeMs(playerTimeMs);
        }
    }

    @NonNull
    public static Holder getOrCreate(@NonNull final Context context,
                                     @NonNull final String videoId)
            throws IOException, ExtractionException {
        final Holder existing = SESSIONS.get(videoId);
        if (existing != null) {
            return existing;
        }
        synchronized (SabrSessionStore.class) {
            final Holder racing = SESSIONS.get(videoId);
            if (racing != null) {
                return racing;
            }
            final Localization localization = new Localization("en", "US");
            final ContentCountry contentCountry = new ContentCountry("US");
            final YoutubeSabrInfo info = YoutubeSabrProbeFetch(videoId, localization, contentCountry);
            final YoutubeSabrFormat audioFormat = info.findBestAudioFormat();
            final YoutubeSabrFormat videoFormat = pickHardwareFriendlyVideo(info);
            if (audioFormat == null || videoFormat == null) {
                throw new IOException("SABR: could not select audio/video formats for " + videoId);
            }
            final SabrPoTokenProvider provider = provider(context);
            final YoutubeSabrSession session =
                    new YoutubeSabrSession(info, audioFormat, videoFormat, provider);
            final Holder holder = new Holder(videoId, info, session, audioFormat, videoFormat);
            SESSIONS.put(videoId, holder);
            // LRU bound: evict the oldest sessions (their pumps are stopped, caches freed).
            ORDER.remove(videoId);
            ORDER.addLast(videoId);
            while (ORDER.size() > MAX_SESSIONS) {
                final String old = ORDER.pollFirst();
                if (old != null && !old.equals(videoId)) {
                    evict(old);
                }
            }
            // Pre-warm the PO token off-thread so the ~45s WebView mint overlaps the initial probe
            // and buffering instead of stalling the pump on its first protected response.
            final Thread warm = new Thread(() -> {
                try {
                    provider.getPoToken(info, session.getStreamState());
                } catch (final Exception ignored) {
                    // Best-effort; the pump mints on demand if this fails.
                }
            }, "SabrTokenPrewarm");
            warm.setDaemon(true);
            warm.start();
            return holder;
        }
    }

    @NonNull
    private static YoutubeSabrInfo YoutubeSabrProbeFetch(@NonNull final String videoId,
                                                        @NonNull final Localization localization,
                                                        @NonNull final ContentCountry contentCountry)
            throws IOException, ExtractionException {
        return org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrProbe.fetchSabrInfo(
                videoId, YoutubeSabrClientProfile.WEB, localization, contentCountry);
    }

    /**
     * Pick the highest-resolution video format the device can decode in HARDWARE. The decoder is
     * chosen by ExoPlayer from the container bytes, so a codec the device only decodes in software
     * (e.g. AV1 on most phones, or VP9 where there's no HW VP9) melts the CPU and overheats. So we
     * allow AVC always (universally HW), VP9 only when a HW VP9 decoder exists, AV1 only when a HW
     * AV1 decoder exists; otherwise fall back to the overall best (better some playback than none).
     */
    @NonNull
    private static YoutubeSabrFormat pickHardwareFriendlyVideo(@NonNull final YoutubeSabrInfo info) {
        final boolean hwVp9 = hasHardwareDecoder("video/x-vnd.on2.vp9");
        final boolean hwAv1 = hasHardwareDecoder("video/av01");
        YoutubeSabrFormat best = null;
        for (final YoutubeSabrFormat f : info.getFormats()) {
            if (!f.isVideo()) {
                continue;
            }
            final String codec = codecFamily(f.getMimeType());
            final boolean decodable = "avc".equals(codec)
                    || ("vp9".equals(codec) && hwVp9)
                    || ("av1".equals(codec) && hwAv1);
            if (!decodable) {
                continue;
            }
            if (best == null || f.getHeight() > best.getHeight()
                    || (f.getHeight() == best.getHeight() && f.getBitrate() > best.getBitrate())) {
                best = f;
            }
        }
        return best != null ? best : info.findBestVideoFormat();
    }

    /** Normalise a SABR format mimeType ({@code codecs="..."}) to a codec family, or null. */
    @NonNull
    private static String codecFamily(final String mimeType) {
        if (mimeType == null) {
            return "";
        }
        if (mimeType.contains("avc1") || mimeType.contains("avc3")) {
            return "avc";
        }
        if (mimeType.contains("vp9") || mimeType.contains("vp09")) {
            return "vp9";
        }
        if (mimeType.contains("av01")) {
            return "av1";
        }
        return "";
    }

    /** True if the device exposes a non-software (hardware) decoder for the given mime type. */
    private static boolean hasHardwareDecoder(@NonNull final String mimeType) {
        try {
            for (final MediaCodecInfo codec
                    : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) {
                    continue;
                }
                final String name = codec.getName().toLowerCase();
                // Software decoders on Android are named c2.android.* / c2.google.* / omx.google.*.
                if (name.startsWith("c2.android.") || name.startsWith("c2.google.")
                        || name.startsWith("omx.google.")) {
                    continue;
                }
                for (final String type : codec.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        return true;
                    }
                }
            }
        } catch (final Exception e) {
            // If capability probing fails, be conservative (treat as no HW decoder).
            return false;
        }
        return false;
    }

    /** Evict a cached session, stopping its pump so the thread + buffers are released. */
    public static void evict(@NonNull final String videoId) {
        final Holder holder = SESSIONS.remove(videoId);
        if (holder != null && holder.pump != null) {
            holder.pump.stop();
        }
    }
}
