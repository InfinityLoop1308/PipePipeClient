package org.schabi.newpipe.player

/**
 * Intent constants of the player service communication protocol.
 *
 * The extras are written by [org.schabi.newpipe.util.NavigationHelper] and read back from the
 * intent in [Player.handleIntent] (and in the player services to decide whether to go
 * foreground). They live outside of [Player] so that the service protocol is not buried inside
 * the player state machine. The string values must not be changed.
 */
object PlayerIntentConstants {
    /**
     * Key of the [org.schabi.newpipe.player.playqueue.PlayQueue] inside
     * [org.schabi.newpipe.util.SerializedCache]. The queue is too large to be put into an intent
     * directly, so it is serialized to the cache first and referenced by this key.
     */
    const val PLAY_QUEUE_KEY = "play_queue_key"

    /** Ordinal of a [PlayerService.PlayerType], selecting which player is started. */
    const val PLAYER_TYPE = "player_type"

    /** Whether playback should resume from the position saved in the watch history. */
    const val RESUME_PLAYBACK = "resume_playback"

    /** Whether playback should start as soon as the player is ready. */
    const val PLAY_WHEN_READY = "play_when_ready"

    /** Whether the queue should be appended to the current one instead of replacing it. */
    const val ENQUEUE = "enqueue"

    /** Whether the queue should be inserted right after the currently playing item. */
    const val ENQUEUE_NEXT = "enqueue_next"

    /** Repeat mode to apply when starting playback. */
    const val REPEAT_MODE = "repeat_mode"

    /** Muted state to apply when starting playback. */
    const val IS_MUTED = "is_muted"

    /** Action used to bind to an already running player service instead of starting playback. */
    const val BIND_PLAYER_HOLDER_ACTION = "bind_player_holder_action"
}
