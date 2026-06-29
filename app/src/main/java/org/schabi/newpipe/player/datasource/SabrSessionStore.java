package org.schabi.newpipe.player.datasource;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one shared {@link YoutubeSabrSession} per videoId so the audio and video
 * {@link SabrSegmentDataSource}s drive the same session (a single SABR response carries both formats, so
 * the session's segment cache serves both without doubling bandwidth).
 *
 * <p>v1: uses the best audio/video formats from the player response and a fixed en/US locale.</p>
 */
public final class SabrSessionStore {

    // Debug: log the AAC audio-track candidates + the chosen one. Keep false outside debugging.
    private static final boolean DIAG_AUDIO = false;

    private static final Map<String, Holder> SESSIONS = new ConcurrentHashMap<>();
    // The user-selected audio track id per video, applied on the next (re)build of its session.
    private static final Map<String, String> PREFERRED_AUDIO = new ConcurrentHashMap<>();
    // Current video plus one (next-item prefetch). Keeping more let abandoned sessions' pump threads
    // linger and bleed into the new playback on a switch, leaving the decoder with no usable frame
    // (black screen). Evicting the superseded session promptly (and stopping its pump) fixes that.
    // Mutated only under the class lock.
    private static final int MAX_SESSIONS = 2;
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
        // Tracks currently selected by ExoPlayer. Background/audio-only playback disables the video
        // renderer, so requiring a video reader position there pins the SABR cache at the beginning.
        private final Set<Integer> activeReaderItags =
                Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
        private volatile SabrStreamPump pump;
        private volatile Thread warmThread;

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

        void setActiveTracks(final boolean videoActive, final boolean audioActive) {
            setTrackActive(videoFormat.getItag(), videoActive);
            setTrackActive(audioFormat.getItag(), audioActive);
        }

        private void setTrackActive(final int itag, final boolean active) {
            if (active) {
                activeReaderItags.add(itag);
            } else {
                activeReaderItags.remove(itag);
                readerPositions.remove(itag);
            }
        }

        /** Furthest-read selected track: the pump keeps the buffered edge a cushion ahead of THIS. */
        public long getReaderHeadMs() {
            long head = 0;
            for (final int itag : activeReaderItags) {
                final Long position = readerPositions.get(itag);
                if (position != null) {
                    head = Math.max(head, position);
                }
            }
            return head;
        }

        /** Slowest-read selected track: nothing before this is needed any more, so eviction starts here.
         * Zero until every selected track has read something (else we'd evict unread segments). */
        public long getReaderTailMs() {
            if (activeReaderItags.isEmpty()) {
                return 0;
            }
            long tail = Long.MAX_VALUE;
            for (final int itag : activeReaderItags) {
                final Long position = readerPositions.get(itag);
                if (position == null) {
                    return 0;
                }
                tail = Math.min(tail, position);
            }
            return tail == Long.MAX_VALUE ? 0 : tail;
        }

        /** Lazily create the single background pump that feeds both data sources for this video. */
        synchronized SabrStreamPump getPump(@NonNull final Localization localization) {
            if (pump == null) {
                pump = new SabrStreamPump(session, this, localization);
            }
            return pump;
        }

        void setWarmThread(@NonNull final Thread warmThread) {
            this.warmThread = warmThread;
        }

        void clearWarmThread(final Thread thread) {
            if (warmThread == thread) {
                warmThread = null;
            }
        }

        void stop() {
            setActiveTracks(false, false);
            final Thread warm = warmThread;
            if (warm != null && warm != Thread.currentThread()) {
                warm.interrupt();
            }
            final SabrStreamPump streamPump = pump;
            if (streamPump != null) {
                streamPump.stop();
            }
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

    // <=0 = audio-only / no preference -> any cached session is fine. Otherwise the session matches
    // when the requested itag RESOLVES to the same format the session already holds. We resolve both
    // sides through pickVideoFormat so an itag the probe doesn't carry (which both map to the same
    // fallback) doesn't trigger a needless rebuild on every resolve. Only a real quality change,
    // which resolves to a different format, triggers a rebuild.
    private static boolean sessionMatchesItag(@NonNull final Holder holder,
                                              final int preferredVideoItag) {
        if (preferredVideoItag <= 0) {
            return true;
        }
        final YoutubeSabrFormat wanted = pickVideoFormat(holder.info, preferredVideoItag);
        return wanted != null && wanted.getItag() == holder.videoFormat.getItag();
    }

    private static boolean sessionMatchesAudioTrack(@NonNull final Holder holder,
                                                    @Nullable final String preferredTrackId) {
        // No explicit pick -> any cached track is fine (the default original). Otherwise the cached
        // session must already stream the requested track, else rebuild.
        return preferredTrackId == null
                || preferredTrackId.equals(holder.audioFormat.getAudioTrackId());
    }

    @NonNull
    /**
     * Set (or clear with {@code null}) the audio track the user picked for a video. Read by
     * {@link #getOrCreate} so the next session (re)build streams that language; a different value
     * than the cached session's track forces a rebuild.
     */
    public static void setPreferredAudioTrack(@NonNull final String videoId,
                                              @Nullable final String audioTrackId) {
        if (DIAG_AUDIO) {
            System.out.println("SABR-AUDIO setPreferred video=" + videoId + " track=" + audioTrackId);
        }
        if (audioTrackId == null) {
            PREFERRED_AUDIO.remove(videoId);
        } else {
            PREFERRED_AUDIO.put(videoId, audioTrackId);
        }
    }

    public static Holder getOrCreate(@NonNull final Context context,
                                     @NonNull final String videoId,
                                     final int preferredVideoItag)
            throws IOException, ExtractionException {
        final String preferredAudioTrackId = PREFERRED_AUDIO.get(videoId);
        final Holder existing = SESSIONS.get(videoId);
        if (existing != null && sessionMatchesItag(existing, preferredVideoItag)
                && sessionMatchesAudioTrack(existing, preferredAudioTrackId)) {
            return existing;
        }
        synchronized (SabrSessionStore.class) {
            final Holder current = SESSIONS.get(videoId);
            if (current != null) {
                if (sessionMatchesItag(current, preferredVideoItag)
                        && sessionMatchesAudioTrack(current, preferredAudioTrackId)) {
                    return current;
                }
                // Quality/codec OR audio-track change: the resolver re-asks with a different video
                // itag or audio track for the same video. The cached session is locked to its
                // formats, so returning it would re-prepare the player on the old pick and
                // dead-buffer. Drop it (stops the pump) + rebuild below.
                evict(videoId);
            }
            final Localization localization = new Localization("en", "US");
            final ContentCountry contentCountry = new ContentCountry("US");
            final YoutubeSabrInfo info = YoutubeSabrProbeFetch(videoId, localization, contentCountry);
            final YoutubeSabrFormat audioFormat = pickAudioFormat(info, preferredAudioTrackId);
            final YoutubeSabrFormat videoFormat = pickVideoFormat(info, preferredVideoItag);
            if (audioFormat == null || videoFormat == null) {
                throw new IOException("SABR: could not select audio/video formats for " + videoId);
            }
            final WebViewPoTokenProvider provider = provider(context);
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
            // and buffering instead of stalling the pump on its first protected response. Keep the
            // init preload off this creation path too: it is best-effort and can wait on the same
            // protected request, so doing it synchronously lets playback teardown cancel source
            // resolution before a MediaSource is returned.
            final boolean preloadInit = preferredAudioTrackId != null || provider.hasCachedToken(videoId);
            final Thread warm = new Thread(() -> {
                try {
                    if (Thread.currentThread().isInterrupted() || !isCurrentHolder(videoId, holder)) {
                        return;
                    }
                    provider.getPoToken(info, session.getStreamState());
                    if (Thread.currentThread().isInterrupted() || !isCurrentHolder(videoId, holder)) {
                        return;
                    }
                    // Pre-load init metadata when a seek will follow (audio switch, or cold-restore:
                    // a cached token means we played this recently). Else the seek maps with the
                    // default 5000ms segment duration -> audio UnexpectedDiscontinuityException.
                    if (preloadInit) {
                        session.fetchSegment(SabrSegmentRequest.initialization(audioFormat),
                                localization);
                        session.fetchSegment(SabrSegmentRequest.initialization(videoFormat),
                                localization);
                    }
                } catch (final Exception ignored) {
                    // Best-effort; the pump mints/fetches on demand if this fails.
                } finally {
                    holder.clearWarmThread(Thread.currentThread());
                }
            }, "SabrTokenPrewarm");
            warm.setDaemon(true);
            holder.setWarmThread(warm);
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

    // Force AAC (mp4) audio instead of the "best" (Opus/webm). honestly: Opus/webm audio just does
    // NOT work through this chunk pipeline. it under-supplies the audio renderer -> AudioTrack
    // underruns -> constant rebuffering (hundreds vs ~2 on AAC, phone cooks). re-confirmed on media3
    // 1.10 AFTER fixing the separate ~2min pump false-stall, so it's its own bug, not that one. i
    // spent ~2h on it: ruled out fetch, cache, chunk timing, the media3 loading contract, buffer
    // size... the data IS cached fine, so it's somewhere inside media3's Opus/webm extract->render
    // with the way we chunk it, and i still have no fucking idea how to fix it. AAC (itag 140) is
    // mp4, hardware-decoded, ~same bitrate (130 vs 136 kbps) and plays perfectly smooth. so: AAC
    // until someone cracks the Opus path. (audio codec isn't user-facing, so this isn't a band-aid
    // on a user setting, just an internal pick.)
    private static YoutubeSabrFormat pickAudioFormat(@NonNull final YoutubeSabrInfo info,
                                                     @Nullable final String preferredTrackId) {
        YoutubeSabrFormat aac = null;
        for (final YoutubeSabrFormat f : info.getFormats()) {
            if (!f.isAudio()) {
                continue;
            }
            final String mime = f.getMimeType();
            if (mime == null || !mime.contains("mp4")) {
                continue;
            }
            // When the user picked a language, only consider that track; otherwise fall through to
            // the original-language preference below.
            if (preferredTrackId != null && !preferredTrackId.equals(f.getAudioTrackId())) {
                continue;
            }
            if (DIAG_AUDIO) {
                System.out.println("SABR-AUDIO candidate itag=" + f.getItag()
                        + " trackId=" + f.getAudioTrackId()
                        + " name=" + f.getAudioTrackDisplayName()
                        + " default=" + f.isAudioDefault()
                        + " original=" + f.isOriginalAudio()
                        + " bitrate=" + f.getBitrate());
            }
            if (aac == null) {
                aac = f;
                continue;
            }
            // Prefer the original-language track over an auto-dub, then the highest bitrate, so a
            // dubbed default doesn't override the source audio. Falls back to plain highest-bitrate
            // when no track is marked original (single-track videos).
            final boolean preferForTrack = f.isOriginalAudio() && !aac.isOriginalAudio();
            final boolean preferForBitrate = f.isOriginalAudio() == aac.isOriginalAudio()
                    && f.getBitrate() > aac.getBitrate();
            if (preferForTrack || preferForBitrate) {
                aac = f;
            }
        }
        if (DIAG_AUDIO && aac != null) {
            System.out.println("SABR-AUDIO chosen video=" + info.getVideoId()
                    + " itag=" + aac.getItag()
                    + " trackId=" + aac.getAudioTrackId()
                    + " name=" + aac.getAudioTrackDisplayName()
                    + " original=" + aac.isOriginalAudio());
        }
        if (aac == null && preferredTrackId != null) {
            // The requested track has no mp4/AAC variant: fall back to the default original pick.
            return pickAudioFormat(info, null);
        }
        return aac != null ? aac : info.findBestAudioFormat();
    }

    /** Honour the user-selected quality when that format is present and hardware-decodable;
     * otherwise fall back to the best hardware-friendly one. */
    /**
     * Map the resolver's chosen video itag to a SABR format. The format-selection policy (the
     * "Enable advanced formats" preference, codec ordering, resolution, etc.) is already applied
     * upstream by the normal resolver path, so SABR just honors that pick: match the requested itag,
     * and fall back to the probe's overall best only if it doesn't carry that itag. No independent
     * decoder filtering here: Android codec capabilities are unreliable, so the user preference is
     * the single source of truth (same as the non-SABR playback path).
     */
    private static YoutubeSabrFormat pickVideoFormat(@NonNull final YoutubeSabrInfo info,
                                                     final int preferredItag) {
        if (preferredItag > 0) {
            for (final YoutubeSabrFormat f : info.getFormats()) {
                if (f.isVideo() && f.getItag() == preferredItag) {
                    return f;
                }
            }
        }
        return info.findBestVideoFormat();
    }

    /** Evict a cached session, stopping its pump so the thread + buffers are released. */
    public static void evict(@NonNull final String videoId) {
        final Holder holder;
        synchronized (SabrSessionStore.class) {
            holder = SESSIONS.remove(videoId);
            ORDER.remove(videoId);
        }
        if (holder != null) {
            holder.stop();
        }
    }

    private static boolean isCurrentHolder(@NonNull final String videoId,
                                           @NonNull final Holder holder) {
        return SESSIONS.get(videoId) == holder;
    }
}
