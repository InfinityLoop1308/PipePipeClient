package org.schabi.newpipe.player.datasource

import android.content.Context
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.SharedWebViewRuntime
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import java.io.Closeable
import java.util.HashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LocalDomPoTokenProvider(context: Context) {
    private val appContext = context.applicationContext

    fun getPoToken(info: YoutubeSabrInfo): ByteArray {
        val visitorData = info.visitorData
            ?: throw SabrProtocolException("Missing visitorData in YouTube player response")
        val session = OneShotMintSession.create(
            appContext,
            visitorData,
            YoutubeParsingHelper.getClientVersion(),
            createCredentialHeaders(),
        )
        return try {
            session.mint(info.videoId)
        } finally {
            session.close()
        }
    }

    private fun createCredentialHeaders(): Map<String, List<String>> {
        return HashMap<String, List<String>>().apply {
            if (ServiceList.YouTube.hasTokens()) {
                YoutubeParsingHelper.addLoggedInHeaders(this)
            } else {
                YoutubeParsingHelper.addCookieHeader(this)
            }
        }
    }
}

private class OneShotMintSession private constructor(
    context: Context,
    private val initialization: InitWaiter,
    private val visitorData: String,
    private val clientVersion: String,
    private val credentialHeaders: Map<String, List<String>>,
) : Closeable {
    private val runtime = SharedWebViewRuntime.get(context.applicationContext)
    private val sessionId = runtime.registerSabrLocalDomCallbacks(Callbacks())
    private val tokenWaiters = mutableMapOf<String, TokenWaiter>()
    @Volatile
    private var closed = false

    private fun loadScriptAndInitialize() {
        try {
            runtime.ensureReady(INIT_TIMEOUT_MS, "Local DOM PO token initialization")
            runtime.evaluateJavascriptBlocking(
                runtime.loadAsset(ASSET) + "\ntrue",
                INIT_TIMEOUT_MS,
                "Local DOM BotGuard helper injection",
            )
            downloadAndRunBotguard()
        } catch (error: Throwable) {
            failInitialization(error)
        }
    }

    @Synchronized
    @Throws(SabrProtocolException::class)
    fun mint(identifier: String): ByteArray {
        if (closed) {
            throw SabrProtocolException("Local DOM PO token session is closed")
        }
        val waiter = TokenWaiter()
        synchronized(tokenWaiters) {
            tokenWaiters[identifier] = waiter
        }
        val posted = runtime.evaluateJavascript(
            "pipepipeSabrObtainPoToken(" + jsonString(sessionId) + ", " +
                jsonString(identifier) + ", " + stringToSabrU8(identifier) + ");",
            null,
        ) { error -> onTokenError(identifier, error) }
        if (!posted) {
            synchronized(tokenWaiters) {
                tokenWaiters.remove(identifier)
            }
            throw SabrProtocolException("Could not post Local DOM PO token generation")
        }
        try {
            if (!waiter.latch.await(TOKEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                synchronized(tokenWaiters) {
                    tokenWaiters.remove(identifier)
                }
                throw SabrProtocolException("Local DOM PO token generation timed out")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            synchronized(tokenWaiters) {
                tokenWaiters.remove(identifier)
            }
            throw SabrProtocolException("Local DOM PO token generation interrupted", error)
        }
        waiter.error.get()?.let {
            throw SabrProtocolException("Local DOM PO token generation failed: ${it.message}", it)
        }
        val token = waiter.token.get()
        if (token == null || token.isEmpty()) {
            throw SabrProtocolException("Local DOM PO token generation returned no token")
        }
        return token
    }

    override fun close() {
        closed = true
        runtime.unregisterSabrLocalDomCallbacks(sessionId)
        synchronized(tokenWaiters) {
            tokenWaiters.values.forEach {
                it.error.set(SabrProtocolException("Local DOM PO token session closed"))
                it.latch.countDown()
            }
            tokenWaiters.clear()
        }
        runtime.evaluateJavascript(
            "pipepipeSabrDeleteSession(" + jsonString(sessionId) + ");",
            null,
            null,
        )
    }

    private fun makeBotguardServiceRequest(
        url: String,
        data: String,
        contentType: String = "application/json+protobuf",
        extraHeaders: Map<String, List<String>> = emptyMap(),
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        Thread({
            try {
                val downloader = DownloaderImpl.getInstance()
                    ?: throw SabrProtocolException("DownloaderImpl is not initialized")
                val response = downloader.post(
                    url,
                    mapOf(
                        "User-Agent" to listOf(SharedWebViewRuntime.USER_AGENT),
                        "Accept" to listOf("application/json"),
                        "Content-Type" to listOf(contentType),
                        "x-goog-api-key" to listOf(LOCAL_DOM_GOOGLE_API_KEY),
                        "x-user-agent" to listOf("grpc-web-javascript/0.1"),
                    ) + extraHeaders,
                    data.toByteArray(),
                )
                if (response.responseCode() != 200) {
                    throw SabrProtocolException(
                        "Local DOM BotGuard request failed: ${response.responseCode()}",
                    )
                }
                onSuccess(response.responseBody())
            } catch (error: Throwable) {
                onError(error)
            }
        }, "SabrLocalDomPoTokenJnn").start()
    }

    private fun makeBotguardGetRequest(
        url: String,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        Thread({
            try {
                val downloader = DownloaderImpl.getInstance()
                    ?: throw SabrProtocolException("DownloaderImpl is not initialized")
                val response = downloader.get(
                    url,
                    mapOf(
                        "User-Agent" to listOf(SharedWebViewRuntime.USER_AGENT),
                        "Accept" to listOf("*/*"),
                    ),
                )
                if (response.responseCode() != 200) {
                    throw SabrProtocolException(
                        "Local DOM BotGuard GET failed: ${response.responseCode()}",
                    )
                }
                onSuccess(response.responseBody())
            } catch (error: Throwable) {
                onError(error)
            }
        }, "SabrLocalDomPoTokenJnnGet").start()
    }

    private fun failInitialization(error: Throwable) {
        initialization.error.compareAndSet(null, error)
        initialization.latch.countDown()
        close()
    }

    private fun completeInitialization() {
        initialization.session.compareAndSet(null, this)
        initialization.latch.countDown()
    }

    private fun onTokenResult(identifier: String, poTokenU8: String) {
        val waiter = synchronized(tokenWaiters) {
            tokenWaiters.remove(identifier)
        } ?: return
        try {
            waiter.token.set(csvU8ToByteArray(poTokenU8))
        } catch (error: Throwable) {
            waiter.error.set(error)
        } finally {
            waiter.latch.countDown()
        }
    }

    private fun onTokenError(identifier: String, error: Throwable) {
        val waiter = synchronized(tokenWaiters) {
            tokenWaiters.remove(identifier)
        } ?: return
        waiter.error.set(error)
        waiter.latch.countDown()
    }

    private fun downloadAndRunBotguard() {
        makeBotguardServiceRequest(
            "https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false",
            buildAttestationBody(visitorData, clientVersion),
            contentType = "application/json",
            extraHeaders = buildAttestationHeaders(
                visitorData,
                clientVersion,
                credentialHeaders,
            ),
            onSuccess = { body ->
                try {
                    val challenge = parseSabrAttChallengeData(body)
                    val inlineInterpreter = challenge.interpreterJavascript
                    if (inlineInterpreter != null) {
                        runBotguard(challenge, inlineInterpreter)
                    } else {
                        makeBotguardGetRequest(
                            requireNotNull(challenge.interpreterUrl),
                            onSuccess = { runBotguard(challenge, it) },
                            onError = ::failInitialization,
                        )
                    }
                } catch (error: Throwable) {
                    failInitialization(error)
                }
            },
            onError = ::failInitialization,
        )
    }

    private fun runBotguard(
        challenge: SabrAttChallengeData,
        interpreterJavascript: String,
    ) {
        runtime.evaluateJavascript(
            "pipepipeSabrRunBotguard(" + jsonString(sessionId) + ", " +
                buildSabrAttChallengeData(challenge, interpreterJavascript) + ");",
            null,
        ) { error -> failInitialization(error) }
    }

    private fun onRunBotguardResult(botguardResponse: String) {
        makeBotguardServiceRequest(
            "https://jnn-pa.googleapis.com/\$rpc/google.internal.waa.v1.Waa/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
            onSuccess = { body ->
                try {
                    val integrityToken = parseSabrIntegrityTokenData(body).first
                    runtime.evaluateJavascript(
                        "pipepipeSabrCreateMinter(" + jsonString(sessionId) + ", " +
                            integrityToken + ");",
                        null,
                    ) { error -> failInitialization(error) }
                } catch (error: Throwable) {
                    failInitialization(error)
                }
            },
            onError = ::failInitialization,
        )
    }

    private inner class Callbacks : SharedWebViewRuntime.SabrLocalDomCallbacks {
        override fun onJsInitializationError(error: String) {
            failInitialization(SabrProtocolException(error))
        }

        override fun onRunBotguardResult(botguardResponse: String) {
            this@OneShotMintSession.onRunBotguardResult(botguardResponse)
        }

        override fun onMinterReady() {
            completeInitialization()
        }

        override fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
            onTokenResult(identifier, poTokenU8)
        }

        override fun onObtainPoTokenError(identifier: String, error: String) {
            onTokenError(identifier, SabrProtocolException(error))
        }
    }

    private class TokenWaiter {
        val latch = CountDownLatch(1)
        val token = AtomicReference<ByteArray>()
        val error = AtomicReference<Throwable>()
    }

    private class InitWaiter {
        val latch = CountDownLatch(1)
        val session = AtomicReference<OneShotMintSession>()
        val error = AtomicReference<Throwable>()
    }

    companion object {
        private const val ASSET = "sabr_po_token.js"
        private const val TOKEN_TIMEOUT_MS = 30_000L
        private const val INIT_TIMEOUT_MS = 60_000L
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"

        @Throws(SabrProtocolException::class)
        fun create(
            context: Context,
            visitorData: String,
            clientVersion: String,
            credentialHeaders: Map<String, List<String>>,
        ): OneShotMintSession {
            val initialization = InitWaiter()
            val session = OneShotMintSession(
                context,
                initialization,
                visitorData,
                clientVersion,
                credentialHeaders,
            )
            session.loadScriptAndInitialize()
            try {
                if (!initialization.latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    session.close()
                    throw SabrProtocolException("Local DOM PO token initialization timed out")
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                session.close()
                throw SabrProtocolException("Local DOM PO token initialization interrupted", error)
            }
            initialization.error.get()?.let {
                throw SabrProtocolException(
                    "Local DOM PO token initialization failed: ${it.message}",
                    it,
                )
            }
            return initialization.session.get()
                ?: throw SabrProtocolException(
                    "Local DOM PO token initialization returned no result",
                )
        }
    }
}

private fun buildAttestationBody(visitorData: String, clientVersion: String): String {
    return """{"context":{"client":{"clientName":"WEB","clientVersion":${jsonString(clientVersion)},"hl":"en","gl":"US","utcOffsetMinutes":0,"visitorData":${jsonString(visitorData)}}},"engagementType":"ENGAGEMENT_TYPE_UNBOUND"}"""
}

private fun buildAttestationHeaders(
    visitorData: String,
    clientVersion: String,
    credentialHeaders: Map<String, List<String>>,
): Map<String, List<String>> {
    return HashMap(credentialHeaders).apply {
        put("User-Agent", listOf(SharedWebViewRuntime.USER_AGENT))
        put("Accept", listOf("application/json"))
        put("Content-Type", listOf("application/json"))
        put("Origin", listOf("https://www.youtube.com"))
        put("Referer", listOf("https://www.youtube.com/"))
        put("X-Goog-Visitor-Id", listOf(visitorData))
        put("X-YouTube-Client-Name", listOf("1"))
        put("X-YouTube-Client-Version", listOf(clientVersion))
        put("x-goog-api-key", listOf(LOCAL_DOM_GOOGLE_API_KEY))
        put("x-user-agent", listOf("grpc-web-javascript/0.1"))
    }
}

private const val LOCAL_DOM_GOOGLE_API_KEY =
    "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"

private fun jsonString(value: String): String {
    return buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}
