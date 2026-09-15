package org.schabi.newpipe.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A playback failure, translated out of ExoPlayer's {@code PlaybackException}.
 *
 * <p>This is what leaves the player package on playback failure, so that listeners, error
 * notifications and the error UI do not need to depend on the media engine. The engine-specific
 * code and the original exception are kept as the {@link #getCause() cause}.</p>
 */
public final class PlayerError extends Exception {

    /** Mirrors the kind of failure ExoPlayer classified the error as. */
    public enum Type {
        SOURCE,
        RENDERER,
        UNEXPECTED,
        REMOTE,
        OTHER,
    }

    private final int errorCode;
    @NonNull private final String errorCodeName;
    @NonNull private final Type type;

    public PlayerError(final int errorCode, @NonNull final String errorCodeName,
                       @NonNull final Type type, @Nullable final Throwable cause) {
        super(errorCodeName, cause);
        this.errorCode = errorCode;
        this.errorCodeName = errorCodeName;
        this.type = type;
    }

    public int getErrorCode() {
        return errorCode;
    }

    @NonNull
    public String getErrorCodeName() {
        return errorCodeName;
    }

    @NonNull
    public Type getType() {
        return type;
    }
}
