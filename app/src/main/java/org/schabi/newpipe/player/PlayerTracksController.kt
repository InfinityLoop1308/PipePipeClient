package org.schabi.newpipe.player

import android.view.View
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.CaptionStyleCompat
import com.google.android.exoplayer2.ui.SubtitleView
import org.schabi.newpipe.R
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.util.ListHelper
import java.util.stream.Collectors

/**
 * Owns the caption and audio track selection of a [Player].
 *
 * The subtitle view styling, the translation of ExoPlayer's [Tracks] into the available caption
 * languages and audio tracks, and the audio track selection (preferred languages for SABR
 * sources, a play queue manager reload otherwise) all live here. [Player] only keeps delegate
 * methods with the same names, so [PlayerMenuController] and the ExoPlayer listener callbacks are
 * unaffected.
 */
class PlayerTracksController(private val player: Player) {

    /**
     * Builds the subtitle view from the user's caption style and scale preferences.
     */
    fun setupSubtitleView() {
        val context = player.getContext()
        val binding = player.binding
        val captionScale = PlayerHelper.getCaptionScale(context)
        val captionStyle = PlayerHelper.getCaptionStyle(context)
        if (player.popupPlayerSelected()) {
            val captionRatio = (captionScale - 1.0f) / 5.0f + 1.0f
            binding.subtitleView.setFractionalTextSize(
                SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * captionRatio
            )
        } else {
            binding.subtitleView.setFractionalTextSize(
                SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * captionScale
            )
        }
        binding.subtitleView.setApplyEmbeddedStyles(captionStyle === CaptionStyleCompat.DEFAULT)
        binding.subtitleView.setStyle(captionStyle)
    }

    fun onTextTracksChanged(currentTrack: Tracks) {
        val binding = player.binding ?: return
        val trackSelector = player.trackSelector

        val trackTypeTextSupported = !currentTrack.containsType(C.TRACK_TYPE_TEXT)
            || currentTrack.isTypeSupported(C.TRACK_TYPE_TEXT, false)
        if (trackSelector.currentMappedTrackInfo == null || !trackTypeTextSupported) {
            binding.captionTextView.visibility = View.GONE
            return
        }

        // Extract all loaded languages
        val textTracks = currentTrack.groups.stream()
            .filter { trackGroupInfo -> C.TRACK_TYPE_TEXT == trackGroupInfo.type }
            .collect(Collectors.toList())
        val availableLanguages = textTracks.stream()
            .map { it.mediaTrackGroup }
            .filter { textTrack -> textTrack.length > 0 }
            .map { textTrack -> textTrack.getFormat(0).language }
            .collect(Collectors.toList())
            .filterNotNull()
        player.getStateHolder().setAvailableSubtitles(availableLanguages)

        // Find selected text track
        val selectedTracks = textTracks.stream()
            .filter { it.isSelected }
            .filter { info -> info.mediaTrackGroup.length >= 1 }
            .map { info -> info.mediaTrackGroup.getFormat(0) }
            .findFirst()

        // Build UI
        player.getMenuController().buildCaptionMenu(availableLanguages)
        if (trackSelector.parameters.getRendererDisabled(captionRendererIndex)
            || !selectedTracks.isPresent
        ) {
            binding.captionTextView.setText(R.string.caption_none)
        } else {
            binding.captionTextView.text = selectedTracks.get().language
        }
        binding.captionTextView.visibility =
            if (availableLanguages.isEmpty()) View.GONE else View.VISIBLE
    }

    fun onAudioTracksChanged() {
        val binding = player.binding ?: return

        val optStreamInfo = player.getCurrentStreamInfo()
        if (!optStreamInfo.isPresent) {
            binding.audioTrackTextView.visibility = View.GONE
            return
        }

        val streamInfo = optStreamInfo.get()
        val audioStreams = ListHelper.getFilteredAudioStreams(
            player.getContext(), streamInfo.audioStreams
        )
        player.getStateHolder().setAvailableAudioLanguages(audioStreams)

        if (audioStreams.size <= 1) {
            binding.audioTrackTextView.visibility = View.GONE
            return
        }

        player.getMenuController().buildAudioTrackMenu(audioStreams)

        val currentAudioTrack = player.getSourceResolver().getAudioTrack()
        val selectedIndex = if (currentAudioTrack != null) {
            var idx = -1
            for (i in audioStreams.indices) {
                if (currentAudioTrack == audioStreams[i].audioTrackId) {
                    idx = i
                    break
                }
            }
            if (idx >= 0) idx else 0
        } else {
            ListHelper.getDefaultAudioFormat(player.getContext(), audioStreams)
        }

        if (selectedIndex >= 0 && selectedIndex < audioStreams.size) {
            val selected = audioStreams[selectedIndex]
            binding.audioTrackTextView.text = selected.audioTrackName
                ?: (selected.audioLocale ?: "Unknown")
        }

        binding.audioTrackTextView.visibility = View.VISIBLE
    }

    fun setAudioTrack(audioTrackId: String?) {
        player.saveStreamProgressState()
        player.setRecovery()
        player.getSourceResolver().setAudioTrack(audioTrackId)
        if (player.isCurrentStreamSabr() && !player.exoPlayerIsNull()) {
            val parameters: DefaultTrackSelector.Parameters.Builder =
                player.trackSelector.buildUponParameters()
            if (audioTrackId.isNullOrEmpty()) {
                parameters.setPreferredAudioLanguages()
            } else {
                parameters.setPreferredAudioLanguages(
                    audioTrackId.split(Regex("[._-]"), 2)[0]
                )
            }
            player.trackSelector.setParameters(parameters)
            return
        }
        player.reloadPlayQueueManager()
    }

    val captionRendererIndex: Int
        get() {
            if (player.exoPlayerIsNull()) {
                return Player.RENDERER_UNAVAILABLE
            }

            val exoPlayer = player.simpleExoPlayer
            for (t in 0 until exoPlayer.rendererCount) {
                if (exoPlayer.getRendererType(t) == C.TRACK_TYPE_TEXT) {
                    return t
                }
            }

            return Player.RENDERER_UNAVAILABLE
        }
}
