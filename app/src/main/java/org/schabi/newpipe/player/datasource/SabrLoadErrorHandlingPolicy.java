package org.schabi.newpipe.player.datasource;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy;

import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrAttestationException;

import java.io.IOException;

/** Retries locally pending SABR segments without applying network-error backoff. */
final class SabrLoadErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {
    private static final long PENDING_RETRY_DELAY_MS = 100;

    @Override
    public long getRetryDelayMsFor(final LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo) {
        if (isPending(loadErrorInfo.exception)) {
            return PENDING_RETRY_DELAY_MS;
        }
        // The request coordinator already spent the attestation retry budget with fresh tokens;
        // reloading the chunk would only resend the rejected token.
        if (hasCause(loadErrorInfo.exception, SabrAttestationException.class)) {
            return C.TIME_UNSET;
        }
        final int normalRetryCount = super.getMinimumLoadableRetryCount(
                loadErrorInfo.mediaLoadData.dataType);
        return loadErrorInfo.errorCount > normalRetryCount
                ? C.TIME_UNSET : super.getRetryDelayMsFor(loadErrorInfo);
    }

    @Override
    public int getMinimumLoadableRetryCount(final int dataType) {
        // Pending-segment retries can exceed the normal network retry budget while a response is
        // streaming. Real errors are still made fatal by getRetryDelayMsFor after that budget.
        return Integer.MAX_VALUE;
    }

    private static boolean isPending(final IOException exception) {
        return hasCause(exception, SabrSegmentPendingException.class);
    }

    private static boolean hasCause(final IOException exception,
                                    final Class<? extends Throwable> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }
}
