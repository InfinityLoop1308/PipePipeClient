package org.schabi.newpipe.player.mediaitem

import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Registry of the typed [Extras] slots used by the player.
 *
 * Every strategy publishes its output under one of these keys and reads other strategies'
 * output back by key. This keeps strategies decoupled: they share data, never references.
 *
 * Only slots that are actually produced and consumed are listed here; new strategies add their
 * own [Extras.Key] next to them.
 */
object ItemKeys {
    /**
     * The resolved stream, set once a [PlayerMediaItem] actually became playable.
     */
    @JvmField
    val STREAM_INFO = Extras.Key<StreamInfo>("streamInfo")

    /**
     * The sorted video streams and the selected index that produced this media item.
     */
    @JvmField
    val QUALITY = Extras.Key<MediaItemTag.Quality>("quality")
}
