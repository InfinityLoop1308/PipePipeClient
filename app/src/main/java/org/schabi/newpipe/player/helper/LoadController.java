package org.schabi.newpipe.player.helper;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;

public class LoadController extends DefaultLoadControl {

    public static final String TAG = "LoadController";
    private static final long SETTLED_AFTER_US = 30_000_000L;
    private static final long JUMP_US = 5_000_000L;

    // buffer ahead after a start or a seek, C.TIME_UNSET = whole buffer
    private final long earlyBufferUs;
    private boolean preloadingEnabled = true;
    private long settledSinceUs = Long.MIN_VALUE;
    private long lastPositionUs = Long.MIN_VALUE;

    public LoadController(final long earlyBufferUs) {
        this.earlyBufferUs = earlyBufferUs;
    }

    @Override
    public void onPrepared() {
        preloadingEnabled = true;
        resetBufferRamp();
        super.onPrepared();
    }

    @Override
    public void onStopped() {
        preloadingEnabled = true;
        resetBufferRamp();
        super.onStopped();
    }

    @Override
    public void onReleased() {
        preloadingEnabled = true;
        resetBufferRamp();
        super.onReleased();
    }

    @Override
    public boolean shouldContinueLoading(final long playbackPositionUs,
                                         final long bufferedDurationUs,
                                         final float playbackSpeed) {
        if (!preloadingEnabled) {
            return false;
        }
        if (earlyBufferUs != C.TIME_UNSET && isSettling(playbackPositionUs)
                && bufferedDurationUs >= (long) (earlyBufferUs * playbackSpeed)) {
            return false;
        }
        return super.shouldContinueLoading(
                playbackPositionUs, bufferedDurationUs, playbackSpeed);
    }

    private boolean isSettling(final long playbackPositionUs) {
        if (settledSinceUs == Long.MIN_VALUE
                || Math.abs(playbackPositionUs - lastPositionUs) > JUMP_US) {
            settledSinceUs = playbackPositionUs;
        }
        lastPositionUs = playbackPositionUs;
        return playbackPositionUs - settledSinceUs < SETTLED_AFTER_US;
    }

    private void resetBufferRamp() {
        settledSinceUs = Long.MIN_VALUE;
        lastPositionUs = Long.MIN_VALUE;
    }

    public void disablePreloadingOfCurrentTrack() {
        preloadingEnabled = false;
    }
}
