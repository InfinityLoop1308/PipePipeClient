package project.pipepipe.app.service

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.preference.PreferenceManager
import project.pipepipe.app.SharedContext
import project.pipepipe.app.mediasource.ExtractorMediaSourceFactory
import project.pipepipe.app.platform.AndroidMediaController

@UnstableApi
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var controller: AndroidMediaController? = null

    override fun onCreate() {
        super.onCreate()
        if (!PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("use_experimental_new_ui", false)
        ) {
            stopSelf()
            return
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ExtractorMediaSourceFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        controller = AndroidMediaController(player, ::stopSelf)
        SharedContext.platformMediaController = controller
        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {})
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (controller?.isPlaying?.value != true) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (SharedContext.platformMediaController === controller) {
            SharedContext.platformMediaController = null
        }
        controller?.release()
        session?.player?.release()
        session?.release()
        controller = null
        session = null
        super.onDestroy()
    }
}
