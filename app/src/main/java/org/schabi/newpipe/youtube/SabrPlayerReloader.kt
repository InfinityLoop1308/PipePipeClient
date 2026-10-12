package org.schabi.newpipe.youtube

import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrInfoReloader
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo

/**
 * Fetches a fresh [YoutubeSabrInfo] when YouTube asks the player to reload (UMP
 * `RELOAD_PLAYER_RESPONSE`).
 *
 * A reload only invalidates the current streaming URL, ustreamer config and PO token, so the
 * player request is re-run with the token carried by the reload response and the returned
 * parameters are swapped into the running session. Playback and downloads share this callback.
 */
object SabrPlayerReloader : SabrInfoReloader {
    override fun reload(videoId: String, reloadToken: String): YoutubeSabrInfo? =
        try {
            YoutubeStreamExtractor.fetchSabrInfoForReload(videoId, reloadToken)
        } catch (error: Exception) {
            null
        }
}
