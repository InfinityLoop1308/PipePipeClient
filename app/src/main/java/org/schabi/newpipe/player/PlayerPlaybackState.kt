package org.schabi.newpipe.player

/**
 * The playback state of a [Player].
 *
 * This is a PipePipe-level state machine which sits on top of ExoPlayer's four states
 * (`STATE_IDLE`, `STATE_BUFFERING`, `STATE_READY`, `STATE_ENDED`): it adds the
 * PipePipe-specific states [BLOCKED] and [PAUSED_SEEK] as well as a well-defined initial
 * value ([PREFLIGHT]), so that the UI, the notification and the various listeners only
 * have to reason about a single value.
 *
 * State changes happen in `Player.changeState()`; [code] only keeps the
 * historical integer values and is used for debugging/logging.
 *
 * Note: this is not to be confused with [PlayerState], which is a serializable snapshot
 * of the play queue and the playback parameters.
 */
enum class PlayerPlaybackState(val code: Int) {
    /** The player has been created, but playback has not started yet. */
    PREFLIGHT(-1),

    /** Playback is blocked, e.g. while waiting for a media source or during SABR back-off. */
    BLOCKED(123),

    /** ExoPlayer is ready and currently playing. */
    PLAYING(124),

    /** ExoPlayer is buffering. */
    BUFFERING(125),

    /** ExoPlayer is ready, but playback is paused. */
    PAUSED(126),

    /** PipePipe-specific: the user is currently dragging the seek bar. */
    PAUSED_SEEK(127),

    /** Playback reached the end of the current stream. */
    COMPLETED(128);

    val isPlaying: Boolean get() = this == PLAYING
    val isPaused: Boolean get() = this == PAUSED
    val isPausedSeek: Boolean get() = this == PAUSED_SEEK
    val isBlocked: Boolean get() = this == BLOCKED
    val isCompleted: Boolean get() = this == COMPLETED
    val isPreflight: Boolean get() = this == PREFLIGHT

    /** Whether playback is not progressing yet, i.e. loading or blocked. */
    val isLoading: Boolean get() = this == PREFLIGHT || this == BLOCKED || this == BUFFERING

    companion object {
        /** Returns the [PlayerPlaybackState] matching the given historical [code], or `null`. */
        @JvmStatic
        fun fromCode(code: Int): PlayerPlaybackState? = entries.firstOrNull { it.code == code }
    }
}
