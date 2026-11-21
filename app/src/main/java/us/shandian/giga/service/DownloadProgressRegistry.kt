package us.shandian.giga.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import org.schabi.newpipe.BuildConfig
import us.shandian.giga.get.DownloadMission
import us.shandian.giga.service.DownloadProgress.Row
import us.shandian.giga.service.DownloadProgress.State

/**
 * The downloads of this process as [DownloadProgressProvider] shows them. Nothing here survives
 * the process, so a missing row only means that nothing is known about a download.
 */
internal object DownloadProgressRegistry {
    const val PATH_MISSIONS = "missions"

    /** Long enough for a reader which was busy to still see how a download ended. */
    private const val FINISHED_ROW_KEPT_MS = 60_000L

    @JvmField
    val CONTENT_URI: Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(BuildConfig.APPLICATION_ID + ".downloadprogress")
        .appendPath(PATH_MISSIONS)
        .build()

    private class FinishedRow(val row: Row, val keptUntil: Long)

    /** Reading these from a storage access framework file means asking a content provider. */
    private class Identity(val name: String?, val relativePath: String?, val uri: String)

    private var manager: DownloadManager? = null
    private val identities = HashMap<Long, Identity>()
    private val finished = LinkedHashMap<Long, FinishedRow>()
    private var published: List<Row> = emptyList()

    @JvmStatic
    @Synchronized
    fun attach(manager: DownloadManager) {
        this.manager = manager
    }

    @JvmStatic
    @Synchronized
    fun detach() {
        manager = null
        identities.clear()
    }

    /**
     * Must be called while the mission is still pending, it is the only source of a finished row.
     */
    @JvmStatic
    @Synchronized
    fun markFinished(mission: DownloadMission) {
        val identity = identityOf(mission) ?: return
        val row = Row(
            mission.timestamp,
            identity.name,
            identity.relativePath,
            identity.uri,
            DownloadProgress.UNKNOWN,
            DownloadProgress.UNKNOWN,
            State.FINISHED,
        )
        finished[mission.timestamp] =
            FinishedRow(row, SystemClock.elapsedRealtime() + FINISHED_ROW_KEPT_MS)
        identities.remove(mission.timestamp)
    }

    @JvmStatic
    @Synchronized
    fun markDeleted(mission: DownloadMission) {
        finished.remove(mission.timestamp)
        identities.remove(mission.timestamp)
    }

    @Synchronized
    fun rows(): List<Row> {
        val now = SystemClock.elapsedRealtime()
        finished.values.removeAll { it.keptUntil <= now }
        val pending = manager?.pendingMissions.orEmpty()
            .filter { it.timestamp !in finished }
            .mapNotNull(::rowOf)
        return pending + finished.values.map { it.row }
    }

    /**
     * Tells the readers about a change, if there is one.
     */
    @JvmStatic
    @Synchronized
    fun publish(context: Context) {
        val current = rows()
        if (current != published) {
            published = current
            context.contentResolver.notifyChange(CONTENT_URI, null)
        }
    }

    private fun rowOf(mission: DownloadMission): Row? {
        val identity = identityOf(mission) ?: return null
        val state = DownloadProgress.stateOf(
            failed = mission.errCode != DownloadMission.ERROR_NOTHING,
            running = mission.running,
            // A remux of the SABR and HLS downloaders has no post-processing algorithm.
            postProcessing = mission.isPsRunning || mission.psState == 1,
            enqueued = mission.enqueued,
        )
        val done = mission.done
        return Row(
            mission.timestamp,
            identity.name,
            identity.relativePath,
            identity.uri,
            DownloadProgress.bytesOf(state, done),
            DownloadProgress.totalOf(state, done, mission.getLength(), mission.unknownLength),
            state,
        )
    }

    private fun identityOf(mission: DownloadMission): Identity? {
        identities[mission.timestamp]?.let { return it }
        val storage = mission.storage
        if (storage == null || storage.isInvalid) {
            return null
        }
        val uri = storage.uri
        val identity = Identity(storage.name, relativePathOf(uri), uri.toString())
        identities[mission.timestamp] = identity
        return identity
    }

    private fun relativePathOf(uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val storageRoot = Environment.getExternalStorageDirectory().absolutePath
            return uri.path?.let { DownloadProgress.relativePathOfFile(it, storageRoot) }
        }
        return try {
            DownloadProgress.relativePathOfDocument(
                uri.authority,
                DocumentsContract.getDocumentId(uri),
            )
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }
}
