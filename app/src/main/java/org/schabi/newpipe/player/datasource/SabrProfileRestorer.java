package org.schabi.newpipe.player.datasource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.sabr.SabrCompatibilityProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrCompatibilityProfileManager;

/** Restores signed cache generations through their distinct trust paths. */
final class SabrProfileRestorer {
    private SabrProfileRestorer() {
    }

    @Nullable
    static Restored restoreActive(@NonNull final SabrCompatibilityProfileManager manager,
                                  @Nullable final byte[] document,
                                  final long nowMs,
                                  final boolean persistedFallback) {
        if (document == null) {
            return null;
        }
        try {
            final SabrCompatibilityProfile profile = persistedFallback
                    ? manager.restoreFallbackDocument(document, nowMs)
                    : manager.restoreDocument(document, nowMs);
            return new Restored(profile, document);
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    static Restored restorePrevious(@NonNull final SabrCompatibilityProfileManager manager,
                                    @Nullable final byte[] document,
                                    final long nowMs) {
        if (document == null) {
            return null;
        }
        try {
            return new Restored(manager.restorePreviousDocument(document, nowMs), document);
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    static Restored matching(@Nullable final SabrCompatibilityProfile profile,
                             @Nullable final Restored first,
                             @Nullable final Restored second) {
        if (profile == null) {
            return null;
        }
        if (first != null && first.profile == profile) {
            return first;
        }
        return second != null && second.profile == profile ? second : null;
    }

    static final class Restored {
        @NonNull final SabrCompatibilityProfile profile;
        @NonNull final byte[] document;

        private Restored(@NonNull final SabrCompatibilityProfile profile,
                         @NonNull final byte[] document) {
            this.profile = profile;
            this.document = document.clone();
        }
    }
}
