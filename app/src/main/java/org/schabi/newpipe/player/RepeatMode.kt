package org.schabi.newpipe.player

/**
 * PipePipe's repeat mode.
 *
 * Replaces the raw ExoPlayer `REPEAT_MODE_*` integers on the player API, so that consumers no
 * longer have to import ExoPlayer to express or compare a repeat mode. The ordinals match the
 * historical ExoPlayer values, which keeps persisted states and player intents compatible.
 */
enum class RepeatMode {
    OFF,
    ONE,
    ALL,
}
