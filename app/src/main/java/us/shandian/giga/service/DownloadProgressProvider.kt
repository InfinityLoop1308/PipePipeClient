package us.shandian.giga.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Lets other apps, such as a gallery or a file manager, show the real state of a download instead
 * of guessing it from the size of the file. Reading needs the READ_DOWNLOAD_PROGRESS permission
 * declared in the manifest, which the user grants to each app. One row per mission, listed by
 * [DownloadProgressRegistry]; `bytes` and `total` are -1 unless the mission is downloading. Asking
 * for the rows never starts a download.
 */
class DownloadProgressProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(uri.lastPathSegment == DownloadProgressRegistry.PATH_MISSIONS) { "Unknown $uri" }

        val cursor = MatrixCursor(COLUMNS)
        DownloadProgressRegistry.rows().forEach { row ->
            cursor.addRow(
                arrayOf<Any?>(
                    row.id,
                    row.name,
                    row.relativePath,
                    row.uri,
                    row.bytes,
                    row.total,
                    row.state.name,
                ),
            )
        }
        cursor.setNotificationUri(context?.contentResolver, DownloadProgressRegistry.CONTENT_URI)
        return cursor
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.pipepipe.download"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw readOnly()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw readOnly()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw readOnly()

    private fun readOnly() = UnsupportedOperationException("The downloads are read only")

    private companion object {
        val COLUMNS = arrayOf("id", "name", "relativePath", "uri", "bytes", "total", "state")
    }
}
