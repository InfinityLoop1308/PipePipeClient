package org.schabi.newpipe.youtube;

import java.io.IOException;

/**
 * Signals SABR media acquisition that could not be recovered by an in-place retry.
 *
 * <p>The request coordinator observed repeated empty responses and/or server-requested
 * backoff without any media progress, performed its single in-place recovery attempt, and
 * still received no progress. This is <em>not</em> an ordinary network error: retrying the
 * same request on the same session is unlikely to help, so the player should handle it by
 * rebuilding the media source at the current playback position instead.
 */
public final class SabrRecoveryExhaustedException extends IOException {
    private static final long serialVersionUID = 1L;

    private final boolean hadBackoff;

    public SabrRecoveryExhaustedException(final String message, final boolean hadBackoff) {
        super(message);
        this.hadBackoff = hadBackoff;
    }

    /**
     * @return {@code true} when the stalled episode was dominated by server-requested
     *         backoff rather than by empty/deferred responses alone
     */
    public boolean isBackoffRelated() {
        return hadBackoff;
    }
}
