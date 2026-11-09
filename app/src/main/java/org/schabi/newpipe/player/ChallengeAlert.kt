package org.schabi.newpipe.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ReCaptchaActivity

/**
 * Alerts the user that playback is paused until they pass a site's verification challenge.
 *
 * While active it shows a notification that opens [ReCaptchaActivity] and repeats the default
 * notification sound every [REPEAT_INTERVAL_MS] until the user responds: opening the challenge,
 * dismissing the notification or pressing play all silence it. Passing the challenge runs the
 * callback given to [start], which the player uses to retry the item.
 *
 * All methods must be called on the main thread.
 */
object ChallengeAlert {
    private const val TAG = "ChallengeAlert"
    private const val NOTIFICATION_ID = 0x0C4A11E
    private const val REPEAT_INTERVAL_MS = 3_000L
    private const val ACTION_DISMISSED = "org.schabi.newpipe.player.CHALLENGE_ALERT_DISMISSED"

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var ringtone: Ringtone? = null
    private var dismissReceiver: BroadcastReceiver? = null
    private var onSolved: Runnable? = null

    private val repeatSound = object : Runnable {
        override fun run() {
            val tone = ringtone ?: return
            if (!tone.isPlaying) {
                tone.play()
            }
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    /** Whether the user wants playback to stop, rather than skip, on a challenge. */
    @JvmStatic
    fun isStopOnChallengeEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.challenge_stop_playback_key), true)

    /** Whether an alert is pending, i.e. a challenge has not been passed or cancelled yet. */
    @JvmStatic
    val isActive: Boolean
        get() = onSolved != null

    /**
     * Show the notification and start repeating the alert sound. Replaces any pending alert.
     *
     * @param url      the page to open in [ReCaptchaActivity]
     * @param title    the title of the item that is waiting, shown in the notification
     * @param onSolved run once the user has passed the challenge
     */
    @JvmStatic
    @MainThread
    fun start(context: Context, url: String?, title: String?, onSolved: Runnable) {
        cancel()
        val app = context.applicationContext
        appContext = app
        this.onSolved = onSolved
        registerDismissReceiver(app)
        showNotification(app, url, title.orEmpty())
        startSound(app)
    }

    /** Stop the sound, keep the notification and the pending callback. */
    @JvmStatic
    @MainThread
    fun silence() {
        handler.removeCallbacks(repeatSound)
        ringtone?.stop()
        ringtone = null
    }

    /** Stop the alert entirely, without running the callback. */
    @JvmStatic
    @MainThread
    fun cancel() {
        silence()
        onSolved = null
        val app = appContext ?: return
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
        dismissReceiver?.let {
            runCatching { app.unregisterReceiver(it) }
        }
        dismissReceiver = null
    }

    /**
     * Called by [ReCaptchaActivity] when it closes.
     *
     * @param solved whether the challenge produced cookies. If not, the alert stays pending
     *               (silenced) so the user can try again from the notification.
     */
    @JvmStatic
    @MainThread
    fun onChallengeScreenClosed(solved: Boolean) {
        silence()
        if (!solved) {
            return
        }
        val callback = onSolved ?: return
        cancel()
        callback.run()
    }

    private fun startSound(context: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        val tone = RingtoneManager.getRingtone(context, uri) ?: return
        // Notification usage, so silent mode and Do Not Disturb are respected
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone = tone
        handler.post(repeatSound)
    }

    @SuppressLint("MissingPermission") // without the permission, the sound alone still alerts
    private fun showNotification(context: Context, url: String?, title: String) {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openChallenge = Intent(context, ReCaptchaActivity::class.java)
            .putExtra(ReCaptchaActivity.RECAPTCHA_URL_EXTRA, url)
            .putExtra(ReCaptchaActivity.RETURN_TO_CALLER_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val dismissed = Intent(ACTION_DISMISSED).setPackage(context.packageName)

        val notification = NotificationCompat.Builder(
            context, context.getString(R.string.challenge_notification_channel_id)
        )
            .setSmallIcon(R.drawable.ic_pipepipe)
            .setContentTitle(context.getString(R.string.challenge_notification_title))
            .setContentText(context.getString(R.string.challenge_notification_text, title))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.challenge_notification_text, title))
            )
            .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, openChallenge,
                immutable))
            .setDeleteIntent(PendingIntent.getBroadcast(context, NOTIFICATION_ID, dismissed,
                immutable))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot show the challenge notification", e)
        }
    }

    private fun registerDismissReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                silence()
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_DISMISSED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        dismissReceiver = receiver
    }
}
