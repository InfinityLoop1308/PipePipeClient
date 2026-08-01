package org.schabi.newpipe.database.cache.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * Persists everything needed to render and play back a stream's page while offline, as
 * requested in https://github.com/InfinityLoop1308/PipePipe/issues/2782: a lightweight
 * "cache for offline viewing" companion to the existing (export-oriented) download feature.
 */
@Entity(
    tableName = CachedStreamEntity.CACHED_STREAM_TABLE,
    indices = [
        Index(value = [CachedStreamEntity.SERVICE_ID, CachedStreamEntity.URL], unique = true)
    ]
)
data class CachedStreamEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = UID)
    var uid: Long = 0,

    @ColumnInfo(name = SERVICE_ID)
    var serviceId: Int,

    @ColumnInfo(name = URL)
    var url: String,

    @ColumnInfo(name = STREAM_ID)
    var streamId: String,

    @ColumnInfo(name = TITLE)
    var title: String,

    @ColumnInfo(name = STREAM_TYPE)
    var streamType: String,

    @ColumnInfo(name = DURATION)
    var duration: Long,

    @ColumnInfo(name = UPLOADER_NAME)
    var uploaderName: String?,

    @ColumnInfo(name = UPLOADER_URL)
    var uploaderUrl: String?,

    @ColumnInfo(name = UPLOADER_AVATAR_URL)
    var uploaderAvatarUrl: String?,

    @ColumnInfo(name = THUMBNAIL_URL)
    var thumbnailUrl: String?,

    @ColumnInfo(name = TEXTUAL_UPLOAD_DATE)
    var textualUploadDate: String?,

    @ColumnInfo(name = VIEW_COUNT)
    var viewCount: Long,

    @ColumnInfo(name = DESCRIPTION)
    var description: String?,

    /** Absolute path of the cached video (or muxed video+audio) file, if any. */
    @ColumnInfo(name = VIDEO_FILE_PATH)
    var videoFilePath: String?,

    /** Absolute path of the cached audio-only file, if any (used when muxing isn't done). */
    @ColumnInfo(name = AUDIO_FILE_PATH)
    var audioFilePath: String?,

    @ColumnInfo(name = VIDEO_MEDIA_FORMAT)
    var videoMediaFormatSuffix: String?,


    @ColumnInfo(name = AUDIO_MEDIA_FORMAT)
    var audioMediaFormatSuffix: String?,

    /**
     * SponsorBlock segments serialized as `uuid,startMs,endMs,category,action;...` so that
     * offline playback can still skip sponsor segments without needing nanojson on the
     * read path.
     */
    @ColumnInfo(name = SPONSOR_BLOCK_SEGMENTS)
    var sponsorBlockSegmentsData: String?,

    @ColumnInfo(name = TOTAL_SIZE_BYTES)
    var totalSizeBytes: Long = 0,

    @ColumnInfo(name = CACHED_AT)
    var cachedAt: Long = 0,

    /** True once both media files finished downloading and the entry is playable offline. */
    @ColumnInfo(name = IS_COMPLETE)
    var isComplete: Boolean = false,

    /**
     * The real resolution label of the cached video, e.g. "1080p60". Must be something
     * `ListHelper.calculateResolution()` can parse: `getVideoStreamIndex()` calls it unguarded
     * while picking a playback quality, so the placeholder `"cached"` this used to invent threw
     * NumberFormatException and a cached video played nothing but a black screen.
     *
     * Deliberately last in the constructor: the Java call sites pass arguments positionally.
     */
    @ColumnInfo(name = VIDEO_RESOLUTION)
    var videoResolution: String? = null
) : Serializable {
    companion object {
        const val CACHED_STREAM_TABLE = "cached_streams"
        const val UID = "uid"
        const val SERVICE_ID = "service_id"
        const val URL = "url"
        const val STREAM_ID = "stream_id"
        const val TITLE = "title"
        const val STREAM_TYPE = "stream_type"
        const val DURATION = "duration"
        const val UPLOADER_NAME = "uploader_name"
        const val UPLOADER_URL = "uploader_url"
        const val UPLOADER_AVATAR_URL = "uploader_avatar_url"
        const val THUMBNAIL_URL = "thumbnail_url"
        const val TEXTUAL_UPLOAD_DATE = "textual_upload_date"
        const val VIEW_COUNT = "view_count"
        const val DESCRIPTION = "description"
        const val VIDEO_FILE_PATH = "video_file_path"
        const val AUDIO_FILE_PATH = "audio_file_path"
        const val VIDEO_MEDIA_FORMAT = "video_media_format"
        const val VIDEO_RESOLUTION = "video_resolution"
        const val AUDIO_MEDIA_FORMAT = "audio_media_format"
        const val SPONSOR_BLOCK_SEGMENTS = "sponsor_block_segments"
        const val TOTAL_SIZE_BYTES = "total_size_bytes"
        const val CACHED_AT = "cached_at"
        const val IS_COMPLETE = "is_complete"
    }
}
