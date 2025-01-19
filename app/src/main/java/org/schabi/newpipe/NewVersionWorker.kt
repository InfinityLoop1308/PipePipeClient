package org.schabi.newpipe

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.util.ReleaseVersionUtil.coerceUpdateCheckExpiry
import org.schabi.newpipe.util.ReleaseVersionUtil.isLastUpdateCheckExpired
import java.io.IOException

class NewVersionWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    private fun compareVersionName(buildVersionCodes: List<String>, versionCodes: List<String>): Boolean {
        return versionCodes[0].toInt() > buildVersionCodes[0].toInt() ||
            versionCodes[0].toInt() == buildVersionCodes[0].toInt() && versionCodes[1].toInt() > buildVersionCodes[1].toInt() ||
            versionCodes[0].toInt() == buildVersionCodes[0].toInt() && versionCodes[1].toInt() == buildVersionCodes[1].toInt() && versionCodes[2].toInt() > buildVersionCodes[2].toInt()
    }
    /**
     * Method to compare the current and latest available app version.
     * If a newer version is available, we show the update notification.
     *
     * @param versionName    Name of new version
     * @param apkLocationUrl Url with the new apk
     * @param versionCode    Code of new version
     */
    private fun compareAppVersionAndShowNotification(
        versionName: String,
        apkLocationUrl: String?,
    ) {
        val versionCodes = versionName.split(".")
        val buildVersionCodes = BuildConfig.VERSION_NAME.split(".")
        if (!compareVersionName(buildVersionCodes, versionCodes)) {
            ContextCompat.getMainExecutor(applicationContext).execute {
                Toast.makeText(
                    applicationContext, R.string.app_update_unavailable_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        // A pending intent to open the apk location url in the browser.
        val intent = Intent(Intent.ACTION_VIEW, apkLocationUrl?.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val channelId = applicationContext.getString(R.string.app_update_notification_channel_id)
        val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_newpipe_update)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setContentTitle(applicationContext.getString(R.string.app_update_notification_content_title_new))
            .setContentText(
                applicationContext.getString(R.string.app_update_notification_content_text) +
                    " " + versionName
            )
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        notificationManager.notify(2000, notificationBuilder.build())
    }

    @Throws(IOException::class, ReCaptchaException::class)
    private fun checkNewVersion() {
        // Check if the current apk is a github one or not.
//        if (!isReleaseApk()) {
//            return
//        }

        if (!inputData.getBoolean(IS_MANUAL, false)) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            // Check if the last request has happened a certain time ago
            // to reduce the number of API requests.
            val expiry = prefs.getLong(applicationContext.getString(R.string.update_expiry_key), 0)
            if (!isLastUpdateCheckExpired(expiry)) {
                return
            }
        }

        // Make a network request to get latest NewPipe data.
        val response = DownloaderImpl.getInstance().get(NEWPIPE_API_URL)
        handleResponse(response)
    }

    private fun handleResponse(response: Response) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        try {
            // Store a timestamp which needs to be exceeded,
            // before a new request to the API is made.
            val newExpiry = coerceUpdateCheckExpiry(response.getHeader("expires"))
            prefs.edit {
                putLong(applicationContext.getString(R.string.update_expiry_key), newExpiry)
            }
        } catch (e: Exception) {
            if (DEBUG) {
                Log.w(TAG, "Could not extract and save new expiry date", e)
            }
        }

        // Parse the json from the response.
        try {
            val githubReleases = JsonParser.`array`()
                .from(response.responseBody())
            val includePreRelease = prefs.getBoolean(applicationContext.getString(R.string.show_prerelease_key), false)
            var githubStableObject: JsonObject? = null
            for (i in 0 until githubReleases.size) {
                val githubRelease = githubReleases.getObject(i)
                if (!includePreRelease && githubRelease.getBoolean("prerelease")) {
                    continue
                }
                githubStableObject = githubRelease
            }


            val supportedAbis = Build.SUPPORTED_ABIS

            var versionName = githubStableObject!!.getString("name").split("-")[0]
            if (versionName.startsWith("v")) {
                versionName = versionName.substring(1)
            }
            // for assets if abi is supported return the URL
            var apkLocationUrl: String? = null
            for (i in 0 until githubStableObject.getArray("assets").size) {
                val asset = githubStableObject.getArray("assets").getObject(i)
                // if universal use it to init apkLocationUrl
                if (asset.getString("name").endsWith(".apk") && asset.getString("name").contains("universal")) {
                    apkLocationUrl = asset.getString("browser_download_url")
                }
                if (asset.getString("name").endsWith(".apk") && asset.getString("name").contains(supportedAbis[0])) {
                    apkLocationUrl = asset.getString("browser_download_url")
                    break
                }
            }
            compareAppVersionAndShowNotification(versionName, apkLocationUrl)
        } catch (e: JsonParserException) {
            // Most likely something is wrong in data received from NEWPIPE_API_URL.
            // Do not alarm user and fail silently.
            if (DEBUG) {
                Log.w(TAG, "Could not get NewPipe API: invalid json", e)
            }
        }
    }

    override fun doWork(): Result {
        return try {
            checkNewVersion()
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Could not fetch NewPipe API: probably network problem", e)
            Result.failure()
        } catch (e: ReCaptchaException) {
            Log.e(TAG, "ReCaptchaException should never happen here.", e)
            Result.failure()
        }
    }

    companion object {
        private val DEBUG = MainActivity.DEBUG
        private val TAG = NewVersionWorker::class.java.simpleName
        private const val NEWPIPE_API_URL = "https://api.github.com/repositories/490984887/releases"
        private const val IS_MANUAL = "isManual"
        /**
         * Start a new worker which checks if all conditions for performing a version check are met,
         * fetches the API endpoint [.NEWPIPE_API_URL] containing info about the latest NewPipe
         * version and displays a notification about an available update if one is available.
         * <br></br>
         * Following conditions need to be met, before data is requested from the server:
         *
         *  *  The app is signed with the correct signing key (by TeamNewPipe / schabi).
         * If the signing key differs from the one used upstream, the update cannot be installed.
         *  * The user enabled searching for and notifying about updates in the settings.
         *  * The app did not recently check for updates.
         * We do not want to make unnecessary connections and DOS our servers.
         */
        @JvmStatic
        fun enqueueNewVersionCheckingWork(context: Context, isManual: Boolean) {
            val workRequest = OneTimeWorkRequestBuilder<NewVersionWorker>()
                .setInputData(workDataOf(IS_MANUAL to isManual))
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
