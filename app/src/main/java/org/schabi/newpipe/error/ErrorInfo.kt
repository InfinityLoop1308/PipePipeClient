package org.schabi.newpipe.error

import android.content.Context
import android.os.Parcelable
import androidx.annotation.StringRes
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.NeedLoginException
import org.schabi.newpipe.extractor.exceptions.WebViewUnavailableException
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.DeobfuscateException
import org.schabi.newpipe.ktx.isNetworkRelated
import org.schabi.newpipe.player.PlayerError
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.math.min

@Parcelize
class ErrorInfo @JvmOverloads constructor(
    val stackTraces: Array<String>,
    val userAction: UserAction,
    val serviceName: String,
    val request: String,
    val messageStringId: Int,
    /** Service or site named by [messageStringId] when it is an anti-bot hint, null if unknown */
    val messageServiceName: String? = null
) : Parcelable {

    /** @return the user-facing message, with any placeholder filled in */
    fun getMessage(context: Context): String =
        if (messageStringId in AntiBotHints.HINT_RES) {
            AntiBotHints.format(context, messageStringId, messageServiceName)
        } else {
            context.getString(messageStringId)
        }

    // no need to store throwable, all data for report is in other variables
    // also, the throwable might not be serializable, see TeamNewPipe/NewPipe#7302
    @IgnoredOnParcel
    var throwable: Throwable? = null

    private constructor(
        throwable: Throwable,
        userAction: UserAction,
        serviceName: String,
        request: String
    ) : this(
        throwableToStringList(throwable),
        userAction,
        serviceName,
        request,
        getMessageStringId(throwable, userAction),
        AntiBotHints.find(throwable)?.let(AntiBotHints::serviceName)
    ) {
        this.throwable = throwable
    }

    private constructor(
        throwable: List<Throwable>,
        userAction: UserAction,
        serviceName: String,
        request: String
    ) : this(
        throwableListToStringList(throwable),
        userAction,
        serviceName,
        request,
        getMessageStringId(throwable.firstOrNull(), userAction),
        AntiBotHints.find(throwable.firstOrNull())?.let(AntiBotHints::serviceName)
    ) {
        this.throwable = throwable.firstOrNull()
    }

    // constructors with single throwable
    constructor(throwable: Throwable, userAction: UserAction, request: String) :
        this(throwable, userAction, SERVICE_NONE, request)
    constructor(throwable: Throwable, userAction: UserAction, request: String, serviceId: Int) :
        this(throwable, userAction, getServiceNameFromId(serviceId), request)
    constructor(throwable: Throwable, userAction: UserAction, request: String, info: Info?) :
        this(throwable, userAction, getInfoServiceName(info), request)

    // constructors with list of throwables
    constructor(throwable: List<Throwable>, userAction: UserAction, request: String) :
        this(throwable, userAction, SERVICE_NONE, request)
    constructor(throwable: List<Throwable>, userAction: UserAction, request: String, serviceId: Int) :
        this(throwable, userAction, getServiceNameFromId(serviceId), request)
    constructor(throwable: List<Throwable>, userAction: UserAction, request: String, info: Info?) :
        this(throwable, userAction, getInfoServiceName(info), request)

    companion object {
        const val SERVICE_NONE = "none"

        private fun getStackTrace(throwable: Throwable): String {
            StringWriter().use { stringWriter ->
                PrintWriter(stringWriter, true).use { printWriter ->
                    throwable.printStackTrace(printWriter)
                    return stringWriter.buffer.toString()
                }
            }
        }

        fun throwableToStringList(throwable: Throwable) = arrayOf(getStackTrace(throwable))

        fun throwableListToStringList(throwable: List<Throwable>) =
            Array(min(throwable.size, 20)) { i -> getStackTrace(throwable[i]) }

        private fun getInfoServiceName(info: Info?) =
            if (info == null) SERVICE_NONE else getServiceNameFromId(info.serviceId)

        private fun getServiceNameFromId(serviceId: Int) = NewPipe.getNameOfService(serviceId) +
                if (serviceId != -1) {
                    " (" + (if (ServiceList.getById(serviceId)?.hasTokens() == true) "Logged in" else "Anonymous") + ")"
                } else {
                    ""
                }

        @StringRes
        private fun getMessageStringId(
            throwable: Throwable?,
            action: UserAction
        ): Int {
            AntiBotHints.find(throwable)?.let { return AntiBotHints.hintRes(it) }
            return when {
                throwable is AccountTerminatedException -> R.string.account_terminated
                throwable is ContentNotAvailableException -> R.string.content_not_available
                throwable != null && throwable.isNetworkRelated -> R.string.network_error
                throwable is ContentNotSupportedException -> R.string.content_not_supported_new
                throwable is DeobfuscateException -> R.string.youtube_signature_deobfuscation_error
                throwable is NeedLoginException -> R.string.need_login_hint
                throwable is WebViewUnavailableException -> R.string.webview_unavailable_youtube_hint
                throwable is ExtractionException -> R.string.parsing_error
                throwable is PlayerError -> {
                    when (throwable.type) {
                        PlayerError.Type.SOURCE -> R.string.player_stream_failure
                        PlayerError.Type.UNEXPECTED -> R.string.player_recoverable_failure
                        PlayerError.Type.RENDERER -> R.string.decoder_init_failure
                        else -> R.string.player_unrecoverable_failure
                    }
                }
                action == UserAction.UI_ERROR -> R.string.app_ui_crash
                action == UserAction.REQUESTED_COMMENTS -> R.string.error_unable_to_load_comments
                action == UserAction.SUBSCRIPTION_CHANGE -> R.string.subscription_change_failed
                action == UserAction.SUBSCRIPTION_UPDATE -> R.string.subscription_update_failed
                action == UserAction.LOAD_IMAGE -> R.string.could_not_load_thumbnails
                action == UserAction.DOWNLOAD_OPEN_DIALOG -> R.string.could_not_setup_download_menu
                else -> R.string.general_error
            }
        }
    }
}
