package us.shandian.giga.service

/**
 * Turns the state of a mission into what other apps can read from
 * [DownloadProgressProvider]. Bytes are only reported while downloading: post-processing restarts
 * the counters of a mission and leaves its length too high afterwards.
 */
internal object DownloadProgress {
    const val UNKNOWN = -1L

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME_PREFIX = "primary:"

    enum class State { QUEUED, RUNNING, PAUSED, POSTPROCESSING, ERROR, FINISHED }

    data class Row(
        val id: Long,
        val name: String?,
        val relativePath: String?,
        val uri: String,
        val bytes: Long,
        val total: Long,
        val state: State,
    )

    /**
     * Finished is never derived from here: a mission looks finished for a moment before its
     * chapters get written, so only the message of the finished mission can tell.
     *
     * @param postProcessing whether a muxer or the writer of the chapters works on the mission;
     * only meaningful while it runs, as a killed mission keeps its saved post-processing state
     */
    fun stateOf(
        failed: Boolean,
        running: Boolean,
        postProcessing: Boolean,
        enqueued: Boolean,
    ): State {
        return when {
            failed -> State.ERROR
            running && postProcessing -> State.POSTPROCESSING
            running -> State.RUNNING
            enqueued -> State.QUEUED
            else -> State.PAUSED
        }
    }

    fun bytesOf(state: State, done: Long): Long {
        return if (state == State.RUNNING) done.coerceAtLeast(0) else UNKNOWN
    }

    fun totalOf(state: State, done: Long, length: Long, lengthUnknown: Boolean): Long {
        if (state != State.RUNNING || lengthUnknown || length <= 0) {
            return UNKNOWN
        }
        return length.coerceAtLeast(done)
    }

    /**
     * @return the path below the shared storage, `null` for anything else such as an SD card
     */
    fun relativePathOfDocument(authority: String?, documentId: String): String? {
        if (authority != EXTERNAL_STORAGE_AUTHORITY) {
            return null
        }
        return documentId.takeIf { it.startsWith(PRIMARY_VOLUME_PREFIX) }
            ?.removePrefix(PRIMARY_VOLUME_PREFIX)
    }

    /**
     * @return the path below the shared storage, `null` for anything else
     */
    fun relativePathOfFile(path: String, storageRoot: String): String? {
        val root = storageRoot.trimEnd('/') + "/"
        return path.takeIf { it.startsWith(root) }?.removePrefix(root)
    }
}
