package org.schabi.newpipe.player.datasource;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.sabr.SabrCompatibilityProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrCompatibilityProfileManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/** Owns verified profile generations and their crash-safe local cache. */
final class SabrProfileRegistry {
    @NonNull private final SabrCompatibilityProfileManager manager;
    @NonNull private final SabrProfileCache cache;
    @Nullable private SabrCompatibilityProfile activeProfile;
    @Nullable private SabrCompatibilityProfile previousProfile;
    @Nullable private byte[] activeDocument;
    @Nullable private byte[] previousDocument;
    private long fallbackFromRevision;

    private SabrProfileRegistry(@NonNull final SabrCompatibilityProfileManager manager,
                                @NonNull final SabrProfileCache cache,
                                @Nullable final SabrProfileRestorer.Restored active,
                                @Nullable final SabrProfileRestorer.Restored previous,
                                final long fallbackFromRevision) {
        this.manager = manager;
        this.cache = cache;
        activeProfile = active == null ? null : active.profile;
        activeDocument = active == null ? null : active.document;
        previousProfile = previous == null ? null : previous.profile;
        previousDocument = previous == null ? null : previous.document;
        this.fallbackFromRevision = fallbackFromRevision;
    }

    @NonNull
    static SabrProfileRegistry restore(@NonNull final Context context,
                                       @NonNull final Map<String, byte[]> keys,
                                       @NonNull final String channel,
                                       final long minimumRevision) {
        final SabrProfileCache cache = new SabrProfileCache(context, channel);
        final long floor = Math.max(minimumRevision, cache.readRevisionFloor());
        final SabrCompatibilityProfileManager manager =
                new SabrCompatibilityProfileManager(keys, floor);
        SabrProfileCache.State cached;
        try {
            cached = cache.readState();
        } catch (final IOException | IllegalArgumentException ignored) {
            cached = new SabrProfileCache.State(null, null, 0);
        }
        if (!cached.isGenerationLayoutValid(floor)) {
            cached = new SabrProfileCache.State(null, null, 0);
        }
        final long nowMs = System.currentTimeMillis();
        final boolean persistedFallback = cached.fallbackFromRevision != 0;
        final SabrProfileRestorer.Restored active = SabrProfileRestorer.restoreActive(
                manager, cached.active, nowMs, persistedFallback);
        final SabrProfileRestorer.Restored previous = active == null || persistedFallback ? null
                : SabrProfileRestorer.restorePrevious(manager, cached.previous, nowMs);
        final SabrCompatibilityProfile current = manager.current(nowMs);
        final SabrProfileRestorer.Restored selected =
                SabrProfileRestorer.matching(current, active, previous);
        final SabrProfileRestorer.Restored fallback = selected == active ? previous : active;
        final SabrProfileRegistry registry = new SabrProfileRegistry(
                manager, cache, selected, fallback,
                selected == null ? 0 : cached.fallbackFromRevision);
        registry.compactCache(floor);
        return registry;
    }

    @Nullable
    synchronized SabrCompatibilityProfile current(final long nowMs) {
        return manager.current(nowMs);
    }

    synchronized long currentRevision(final long nowMs) {
        final SabrCompatibilityProfile current = manager.current(nowMs);
        return current == null ? -1 : current.getRevision();
    }

    synchronized void install(@NonNull final byte[] document, final long nowMs)
            throws IOException {
        final SabrCompatibilityProfile verified = manager.verifyDocument(document, nowMs);
        final SabrCompatibilityProfile current = manager.current(nowMs);
        if (current != null && current.getRevision() == verified.getRevision()
                && !Arrays.equals(current.serialize(), verified.serialize())) {
            throw new IllegalArgumentException(
                    "Conflicting SABR compatibility profile revision");
        }
        final boolean replacesSameRevision = current != null
                && current.getRevision() == verified.getRevision();
        final byte[] oldActive = documentFor(current);
        final SabrProfileCache.State oldState =
                new SabrProfileCache.State(
                        activeDocument, previousDocument, fallbackFromRevision);
        cache.writeState(new SabrProfileCache.State(
                document, replacesSameRevision ? previousDocument : oldActive, 0));
        try {
            cache.writeRevisionFloor(verified.getRevision());
        } catch (final IOException failure) {
            restoreState(oldState);
            throw failure;
        }
        manager.activate(verified);
        previousProfile = replacesSameRevision ? previousProfile : current;
        previousDocument = replacesSameRevision ? previousDocument : oldActive;
        activeProfile = verified;
        activeDocument = document.clone();
        fallbackFromRevision = 0;
    }

    synchronized boolean disable(@NonNull final SabrCompatibilityProfile expected) {
        if (!manager.deactivate(expected)) {
            return false;
        }
        final SabrCompatibilityProfile fallback = manager.current(System.currentTimeMillis());
        final byte[] fallbackDocument = documentFor(fallback);
        try {
            if (fallbackDocument == null) {
                cache.deleteState();
            } else {
                cache.writeState(new SabrProfileCache.State(fallbackDocument, null,
                        manager.getHighestRevision()));
            }
        } catch (final IOException failure) {
            cache.deleteState();
        }
        activeProfile = fallback;
        activeDocument = fallbackDocument;
        previousProfile = null;
        previousDocument = null;
        fallbackFromRevision = fallback == null ? 0 : manager.getHighestRevision();
        return true;
    }

    @Nullable
    private byte[] documentFor(@Nullable final SabrCompatibilityProfile profile) {
        if (profile == null) {
            return null;
        }
        if (profile == activeProfile) {
            return activeDocument == null ? null : activeDocument.clone();
        }
        if (profile == previousProfile) {
            return previousDocument == null ? null : previousDocument.clone();
        }
        return null;
    }

    private void compactCache(final long revisionFloor) {
        try {
            cache.writeState(new SabrProfileCache.State(
                    activeDocument, previousDocument, fallbackFromRevision));
            cache.writeRevisionFloor(Math.max(revisionFloor, manager.getHighestRevision()));
        } catch (final IOException ignored) {
            // A cache failure never prevents bundled SABR playback.
        }
    }

    private void restoreState(@NonNull final SabrProfileCache.State state) {
        try {
            cache.writeState(state);
        } catch (final IOException ignored) {
            cache.deleteState();
        }
    }

}
