package org.schabi.newpipe.player.resolver

import com.google.android.exoplayer2.source.MediaSource
import org.schabi.newpipe.extractor.stream.StreamInfo

/** Resolves a [StreamInfo] into the [MediaSource] to play; built by [MediaSourceBuilder]. */
interface PlaybackResolver : Resolver<StreamInfo, MediaSource>
