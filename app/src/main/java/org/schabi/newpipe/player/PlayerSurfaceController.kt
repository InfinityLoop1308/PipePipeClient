package org.schabi.newpipe.player

import android.os.Build
import org.schabi.newpipe.player.playback.SurfaceHolderCallback

/**
 * Owns the video surface of a [Player].
 *
 * The [SurfaceHolderCallback] that detaches the surface from ExoPlayer while it is not writable
 * lives here. [Player] only keeps delegate methods with the same names, so the player setup and
 * teardown are unaffected.
 */
class PlayerSurfaceController(private val player: Player) {

    private var surfaceHolderCallback: SurfaceHolderCallback? = null

    fun setupVideoSurface() {
        // make sure there is nothing left over from previous calls
        cleanupVideoSurface()

        val simpleExoPlayer = player.simpleExoPlayer
        val binding = player.binding
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // >=API23
            val callback = SurfaceHolderCallback(simpleExoPlayer)
            surfaceHolderCallback = callback
            binding.surfaceView.holder.addCallback(callback)
            val surface = binding.surfaceView.holder.surface
            // ensure player is using an unreleased surface, which the surfaceView might not be
            // when starting playback on background or during player switching
            if (surface.isValid) {
                // initially set the surface manually otherwise
                // onRenderedFirstFrame() will not be called
                simpleExoPlayer.setVideoSurface(surface)
            }
        } else {
            simpleExoPlayer.setVideoSurfaceView(binding.surfaceView)
        }
    }

    fun cleanupVideoSurface() {
        // Only for API >= 23
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            surfaceHolderCallback?.let { callback ->
                player.binding?.surfaceView?.holder?.removeCallback(callback)
            }
            surfaceHolderCallback = null
        }
    }
}
