package org.schabi.newpipe.local.playlist

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.pm.ShortcutManagerCompat
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NavigationHelper

/**
 * Invisible trampoline launched by a pinned home screen shortcut:
 * starts background playback of a local playlist.
 */
class PlayPlaylistShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1)
        if (playlistId < 0) {
            finish()
            return
        }
        val appContext = applicationContext
        val database = NewPipeDatabase.getInstance(appContext)
        database.playlistDAO().getPlaylist(playlistId)
            .firstOrError()
            .flatMap { playlists ->
                if (playlists.isEmpty()) {
                    Single.error(PlaylistGoneException())
                } else {
                    LocalPlaylistManager(database).getPlaylistStreams(playlistId).firstOrError()
                }
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ items ->
                if (items.isEmpty()) {
                    Toast.makeText(appContext, R.string.no_videos, Toast.LENGTH_SHORT).show()
                } else {
                    val queue = SinglePlayQueue(items.map { it.toStreamInfoItem() }, 0)
                    NavigationHelper.playOnBackgroundPlayer(appContext, queue, true)
                }
                finish()
            }, { error ->
                if (error is PlaylistGoneException) {
                    val message = getString(R.string.playlist_no_longer_exists)
                    ShortcutManagerCompat.disableShortcuts(
                        appContext, listOf(shortcutId(playlistId)), message
                    )
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(appContext, R.string.error_snackbar_message, Toast.LENGTH_SHORT).show()
                }
                finish()
            })
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"

        @JvmStatic
        fun shortcutId(playlistId: Long) = "local_playlist_$playlistId"

        private class PlaylistGoneException : Exception()
    }
}
