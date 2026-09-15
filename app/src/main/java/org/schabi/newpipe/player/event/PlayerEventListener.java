package org.schabi.newpipe.player.event;


import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.PlayerPlaybackParameters;
import org.schabi.newpipe.player.PlayerPlaybackState;
import org.schabi.newpipe.player.RepeatMode;
import org.schabi.newpipe.player.playqueue.PlayQueue;

public interface PlayerEventListener {
    void onQueueUpdate(PlayQueue queue);
    void onPlaybackUpdate(PlayerPlaybackState state, RepeatMode repeatMode, boolean shuffled,
                          PlayerPlaybackParameters parameters);
    void onProgressUpdate(int currentProgress, int duration, int bufferPercent);
    void onMetadataUpdate(StreamInfo info, PlayQueue queue);
    void onServiceStopped();
}
