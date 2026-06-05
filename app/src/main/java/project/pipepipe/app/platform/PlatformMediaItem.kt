package project.pipepipe.app.platform

import java.util.UUID

data class PlatformMediaItem(
    val mediaId: String,
    val title: String?,
    val artist: String?,
    val artworkUrl: String?,
    val durationMs: Long?,
    val serviceId: Int?,
    val extras: Map<String, Any?>? = null,
    val uuid: String = UUID.randomUUID().toString()
)
