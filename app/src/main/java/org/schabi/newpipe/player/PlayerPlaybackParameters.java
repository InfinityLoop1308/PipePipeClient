package org.schabi.newpipe.player;

import java.util.Objects;

/**
 * PipePipe's playback parameters (speed and pitch).
 *
 * Replaces ExoPlayer's {@code PlaybackParameters} on the player API, so that consumers no longer
 * have to import ExoPlayer to read the current speed or pitch. The values still originate from
 * ExoPlayer and are translated at the engine boundary.
 */
public final class PlayerPlaybackParameters {

    public final float speed;
    public final float pitch;

    public PlayerPlaybackParameters(final float speed, final float pitch) {
        this.speed = speed;
        this.pitch = pitch;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlayerPlaybackParameters)) {
            return false;
        }
        final PlayerPlaybackParameters other = (PlayerPlaybackParameters) o;
        return Float.compare(other.speed, speed) == 0 && Float.compare(other.pitch, pitch) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(speed, pitch);
    }

    @Override
    public String toString() {
        return "PlayerPlaybackParameters{speed=" + speed + ", pitch=" + pitch + "}";
    }
}
