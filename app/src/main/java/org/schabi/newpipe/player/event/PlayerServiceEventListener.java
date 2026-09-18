package org.schabi.newpipe.player.event;

import org.schabi.newpipe.player.PlayerError;

public interface PlayerServiceEventListener extends PlayerEventListener {
    void onFullscreenStateChanged(boolean fullscreen);

    void onMoreOptionsLongClicked();

    void onPlayerError(PlayerError error, boolean isCatchableException);

    void hideSystemUiIfNeeded();
}
