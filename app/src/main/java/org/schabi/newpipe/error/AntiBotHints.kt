package org.schabi.newpipe.error

import android.content.Context
import androidx.annotation.StringRes
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AntiBotException
import org.schabi.newpipe.extractor.exceptions.AntiBotLoginRequiredException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * Builds the user-facing hints for [AntiBotException] and its subclasses, which name the
 * service that blocked the request.
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
     * @return the name of the service that blocked the request, or null if it is unknown
     */
    @JvmStatic
    fun serviceName(exception: AntiBotException): String? =
        ServiceList.getById(exception.serviceId)?.serviceInfo?.name

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
}
