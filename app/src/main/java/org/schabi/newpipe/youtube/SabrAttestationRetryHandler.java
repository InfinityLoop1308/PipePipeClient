package org.schabi.newpipe.youtube;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrAttestationException;

/** Maintains the PO-token recovery budget for one SABR media acquisition. */
public final class SabrAttestationRetryHandler {
    private static final int MAX_RETRIES = 3;

    @NonNull private final String videoId;
    private int retriesRemaining = MAX_RETRIES;
    /** Generation of the minter behind the session's current token, or null when unknown. */
    @Nullable private Long tokenGeneration;

    public SabrAttestationRetryHandler(@NonNull final String videoId,
                                       @Nullable final byte[] initialPoToken) {
        this.videoId = videoId;
        tokenGeneration = initialPoToken == null || initialPoToken.length == 0
                ? null : LocalDomPoTokenProvider.INSTANCE.generationOf(initialPoToken);
    }

    /** Mints and injects the token for the next retry, or fails after the retry budget is spent. */
    public synchronized void prepareRetry(
            @NonNull final YoutubeSabrSession session,
            @NonNull final SabrAttestationException rejectedTokenError)
            throws SabrAttestationException {
        if (retriesRemaining == 0) {
            // Leave a fresh minter for the next playback instead of the rejected one.
            if (tokenGeneration != null) {
                LocalDomPoTokenProvider.INSTANCE.invalidate(tokenGeneration);
            }
            throw new SabrAttestationException(
                    "SABR PO token was rejected after " + MAX_RETRIES
                            + " attestation recovery retries for video=" + videoId,
                    rejectedTokenError);
        }

        final int retryNumber = MAX_RETRIES - retriesRemaining + 1;
        retriesRemaining--;
        // Tokens minted by the minter whose token was rejected are rejected as well, so the retry
        // token has to come from a new minter. The generation check keeps a minter that was
        // already replaced by another session from being thrown away again.
        if (tokenGeneration != null) {
            LocalDomPoTokenProvider.INSTANCE.invalidate(tokenGeneration);
        }
        session.resetProtocolState();
        try {
            final MintedPlayerPoToken minted =
                    LocalDomPoTokenProvider.INSTANCE.mintPlayerPoToken(videoId);
            if (minted.getToken().length == 0) {
                throw new IllegalArgumentException("minted token is empty");
            }
            session.setPoToken(minted.getToken());
            tokenGeneration = minted.getGeneration();
        } catch (final Exception error) {
            throw new SabrAttestationException(
                    "SABR PO token recovery failed on retry " + retryNumber + " of "
                            + MAX_RETRIES + " for video=" + videoId + ": "
                            + error.getMessage(), error);
        }
    }

    /** A media payload proves that the current token is usable and starts a fresh budget. */
    public synchronized void onMediaReceived() {
        retriesRemaining = MAX_RETRIES;
    }
}
