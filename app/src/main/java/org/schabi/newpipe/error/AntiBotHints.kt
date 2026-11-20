package org.schabi.newpipe.error

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AntiBotException
import org.schabi.newpipe.extractor.exceptions.AntiBotLoginRequiredException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.settings.SettingsActivity

/**
 * Builds the user-facing hints for [AntiBotException] and its subclasses, which name the
 * service or site that blocked the request.
 */
object AntiBotHints {

    /** Bound on the cause chain walk, as protection against unusual exception graphs. */
    private const val MAX_CAUSE_DEPTH = 16

    /** The hint strings, all taking the service name as their only argument */
    @JvmField
    val HINT_RES = setOf(
        R.string.anti_bot_hint,
        R.string.anti_bot_login_hint,
        R.string.anti_bot_captcha_hint
    )

    /**
     * @return the [AntiBotException] found in [throwable] or its causes, or null if there is none.
     * Errors often reach the UI wrapped in other exceptions, so the whole chain is searched.
     */
    @JvmStatic
    fun find(throwable: Throwable?): AntiBotException? {
        var current = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is AntiBotException) {
                return current
            }
            val cause = current.cause
            if (cause === current) {
                return null
            }
            current = cause
            depth++
        }
        return null
    }

    @StringRes
    @JvmStatic
    fun hintRes(exception: AntiBotException): Int = when (exception) {
        is ReCaptchaException -> R.string.anti_bot_captcha_hint
        is AntiBotLoginRequiredException -> R.string.anti_bot_login_hint
        else -> R.string.anti_bot_hint
    }

    /**
     * @return who blocked the request: the service named by the thrower, else the service
     * whose site the blocked url belongs to, else the site itself (requests to e.g. SponsorBlock
     * belong to no service), or null if it is unknown
     */
    @JvmStatic
    fun serviceName(exception: AntiBotException): String? {
        ServiceList.getById(exception.serviceId)?.let { return it.serviceInfo.name }
        val domain = ChallengeCookies.domainOf(exception.url) ?: return null
        return ServiceList.all().firstOrNull { service ->
            runCatching { ChallengeCookies.domainOf(service.baseUrl) }.getOrNull() == domain
        }?.serviceInfo?.name ?: domain
    }

    /**
     * Formats [hintRes] with [serviceName], falling back to a generic subject when the service
     * is unknown.
     */
    @JvmStatic
    fun format(context: Context, @StringRes hintRes: Int, serviceName: String?): String =
        context.getString(
            hintRes,
            serviceName ?: context.getString(R.string.anti_bot_unknown_service)
        )

    @JvmStatic
    fun message(context: Context, exception: AntiBotException): String =
        format(context, hintRes(exception), serviceName(exception))

    /** @return the label of the button that resolves [exception], or null if none can */
    @StringRes
    @JvmStatic
    fun actionRes(exception: AntiBotException): Int? = when (exception) {
        is ReCaptchaException -> R.string.anti_bot_captcha_action
        is AntiBotLoginRequiredException -> R.string.anti_bot_login_action
        else -> null
    }

    /**
     * @return the intent of the screen that resolves [exception]: the challenge for captchas,
     * the blocking service's account settings for login, or null if nothing can resolve it
     */
    @JvmStatic
    fun actionIntent(context: Context, exception: AntiBotException): Intent? = when (exception) {
        is ReCaptchaException -> Intent(context, ReCaptchaActivity::class.java)
            .putExtra(ReCaptchaActivity.RECAPTCHA_URL_EXTRA, exception.challengeUrl)
        is AntiBotLoginRequiredException -> Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_ACCOUNT_SERVICE_ID, exception.serviceId)
        else -> null
    }
}
