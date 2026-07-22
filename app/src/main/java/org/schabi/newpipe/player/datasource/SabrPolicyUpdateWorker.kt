package org.schabi.newpipe.player.datasource

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrCompatibilityProfileDocument
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class SabrPolicyUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val url = BuildConfig.SABR_COMPATIBILITY_PROFILE_URL
        if (url.isEmpty() || BuildConfig.SABR_COMPATIBILITY_PROFILE_PUBLIC_KEYS.isEmpty()) {
            return Result.success()
        }
        return try {
            val parsedUrl = url.toHttpUrlOrNull()
                ?.takeIf { it.isHttps }
                ?: throw IllegalArgumentException("SABR profile URL must use HTTPS")
            val client = DownloaderImpl.getInstance().client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val request = Request.Builder()
                .url(parsedUrl)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        SabrPolicyRuntime.installDocument(readBounded(response),
                            System.currentTimeMillis())
                        Result.success()
                    }
                    204, 304 -> Result.success()
                    408, 425, 429, in 500..599 -> Result.retry()
                    else -> {
                        Log.w(TAG, "SABR profile update failed with HTTP ${response.code}")
                        Result.success()
                    }
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "Could not update SABR profile", error)
            Result.retry()
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Rejected SABR compatibility profile", error)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "SabrPolicyUpdate"
        private const val BUFFER_BYTES = 8192

        private fun readBounded(response: okhttp3.Response): ByteArray {
            val body = response.body
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (bytes.size() + read >
                        SabrCompatibilityProfileDocument.MAX_DOCUMENT_BYTES
                    ) {
                        throw IllegalArgumentException(
                            "SABR profile response exceeds size limit",
                        )
                    }
                    bytes.write(buffer, 0, read)
                }
            }
            return bytes.toByteArray()
        }

        @JvmStatic
        fun initialize(context: Context) {
            if (BuildConfig.SABR_COMPATIBILITY_PROFILE_URL.isEmpty() ||
                BuildConfig.SABR_COMPATIBILITY_PROFILE_PUBLIC_KEYS.isEmpty()
            ) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val immediate = OneTimeWorkRequestBuilder<SabrPolicyUpdateWorker>()
                .setConstraints(constraints)
                .build()
            val periodic = PeriodicWorkRequestBuilder<SabrPolicyUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(
                "sabr-profile-update-now",
                ExistingWorkPolicy.REPLACE,
                immediate,
            )
            workManager.enqueueUniquePeriodicWork(
                "sabr-profile-update-periodic",
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic,
            )
        }
    }
}
