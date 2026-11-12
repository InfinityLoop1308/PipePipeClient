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
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ChallengeCookies
import org.schabi.newpipe.error.ChallengeRequest
import org.schabi.newpipe.error.ReCaptchaActivity

/**
 * Alerts the user that playback is paused until they pass a site's verification challenge.
 *
 * While active it shows a notification that opens [ReCaptchaActivity]. By default the notification
 * goes to the error report channel, like other playback errors. If the user enabled the repeating
 * alert, it goes to its own high-importance channel instead and the default notification sound
 * repeats every [REPEAT_INTERVAL_MS] until the user responds: opening the challenge, dismissing
 * the notification or pressing play all silence it. Passing the challenge runs the callback given
 * to [start], which the player uses to retry the item.
 *
 * All methods must be called on the main thread.
 */
object ChallengeAlert {
    private const val TAG = "ChallengeAlert"
    private const val NOTIFICATION_ID = 0x0C4A11E
    private const val REPEAT_INTERVAL_MS = 5_000L
    private const val ACTION_DISMISSED = "org.schabi.newpipe.player.CHALLENGE_ALERT_DISMISSED"
    private val SOUND_TYPES = intArrayOf(
        RingtoneManager.TYPE_NOTIFICATION,
        RingtoneManager.TYPE_ALARM,
        RingtoneManager.TYPE_RINGTONE,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var ringtone: Ringtone? = null
    private var dismissReceiver: BroadcastReceiver? = null
    private var onSolved: Runnable? = null
    private var pendingUrl: String? = null
    private var pendingTitle: String = ""
    private var repeating = false

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

    /** Whether the user wants the alert sound to repeat until they respond to the challenge. */
    private fun isRepeatAlertEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.challenge_repeat_alert_key), false)

    /** Whether an alert is pending, i.e. a challenge has not been passed or cancelled yet. */
    @JvmStatic
    val isActive: Boolean
        get() = onSolved != null

    /**
     * Show the notification and, if the user enabled it, start repeating the alert sound.
     * Replaces any pending alert.
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
        pendingUrl = url
        pendingTitle = title.orEmpty()
        repeating = isRepeatAlertEnabled(app)
        if (repeating) {
            createRepeatingChannel(app)
            registerDismissReceiver(app)
        }
        showNotification(app, url, pendingTitle)
        if (repeating) {
            startSound(app)
        }
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
        pendingUrl = null
        pendingTitle = ""
        repeating = false
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
     * The pending alert counts as passed only if the screen stored cookies for the domain of
     * the pending challenge; a challenge solved for another site does not retry this one. If it
     * was not passed, the alert stays pending and its notification is shown again, silently,
     * because tapping the notification removed it and it is the way back to the challenge.
     *
     * @param solvedDomains the registrable domains the screen stored cookies for
     */
    @JvmStatic
    @MainThread
    fun onChallengeScreenClosed(solvedDomains: Collection<String>) {
        silence()
        val callback = onSolved ?: return
        if (ChallengeRequest.isSolvedBy(ChallengeCookies.domainOf(pendingUrl), solvedDomains)) {
            cancel()
            callback.run()
            return
        }
        appContext?.let { showNotification(it, pendingUrl, pendingTitle) }
    }

    /**
     * Create the channel of the repeating alert. It is silent because [startSound] plays the
     * sound itself, and it is created only once the user enabled the repeating alert.
     */
    private fun createRepeatingChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(
                context.getString(R.string.challenge_notification_channel_id),
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
                .setName(context.getString(R.string.challenge_notification_channel_name))
                .setDescription(
                    context.getString(R.string.challenge_notification_channel_description)
                )
                .setSound(null, null)
                .build()
        )
    }

    private fun startSound(context: Context) {
        // The channel is silent, so without a sound here the alert would make no noise at all:
        // fall back to the alarm and ringtone sounds when no notification sound is set.
        // getDefaultUri() is never null, even when the user set a type to "None"; the actual
        // URI is null then, so it tells which types have a sound. Still play the default URI,
        // which the system resolves, since the actual one may point to a file we cannot read.
        val tone = SOUND_TYPES.asSequence()
            .filter { RingtoneManager.getActualDefaultRingtoneUri(context, it) != null }
            .mapNotNull { RingtoneManager.getDefaultUri(it) }
            .mapNotNull { RingtoneManager.getRingtone(context, it) }
            .firstOrNull() ?: return
        // Notification usage, so silent mode and Do Not Disturb are respected
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone = tone
        handler.post(repeatSound)
    }

    @SuppressLint("MissingPermission") // without the permission, the player still stops
    private fun showNotification(context: Context, url: String?, title: String) {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openChallenge = Intent(context, ReCaptchaActivity::class.java)
            .putExtra(ReCaptchaActivity.RECAPTCHA_URL_EXTRA, url)
            .putExtra(ReCaptchaActivity.RETURN_TO_CALLER_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val dismissed = Intent(ACTION_DISMISSED).setPackage(context.packageName)

        val channelId = context.getString(
            if (repeating) R.string.challenge_notification_channel_id
            else R.string.error_report_channel_id
        )
        val notification = NotificationCompat.Builder(context, channelId)
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
            .setCategory(
                if (repeating) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_ERROR
            )
            .setPriority(
                if (repeating) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
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
