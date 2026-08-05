package org.schabi.newpipe.youtube

import android.content.Context
import android.util.Log
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.SharedWebViewRuntime
import org.schabi.newpipe.extractor.services.youtube.YoutubePoTokenResult
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrProtocolException
import java.io.Closeable
import java.util.Base64
import java.util.HashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

object LocalDomPoTokenProvider {
    private lateinit var appContext: Context
    private val initializationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "YoutubePoTokenWarmup").apply { isDaemon = true }
    }
    private val initializationLock = Any()
    @Volatile
    private var initializationTask: FutureTask<MintState>? = null

    fun warmUp() {
        ensureInitializationTask()
    }

    fun getPlayerPoToken(videoId: String): YoutubePoTokenResult {
        val state = getState()
        val token = if (state.homeConfig.useContentPoToken) {
            state.session.mint(videoId)
        } else {
            state.sessionPoToken.clone()
        }
        return YoutubePoTokenResult(
            state.homeConfig.visitorData,
            state.homeConfig.clientVersion,
            Base64.getUrlEncoder().withoutPadding().encodeToString(token),
        )
    }

    private fun getState(): MintState {
        while (true) {
            val task = ensureInitializationTask()
            val state = try {
                task.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SabrProtocolException("Global PO token initialization interrupted", error)
            } catch (error: ExecutionException) {
                synchronized(initializationLock) {
                    if (initializationTask === task) {
                        initializationTask = null
                    }
                }
                val cause = error.cause ?: error
                throw SabrProtocolException(
                    "Global PO token initialization failed: ${cause.message}",
                    cause,
                )
            }
            if (!state.session.isExpired()) {
                return state
            }
            synchronized(initializationLock) {
                if (initializationTask === task) {
                    initializationTask = null
                    state.session.close()
                }
            }
        }
    }

    private fun ensureInitializationTask(): FutureTask<MintState> {
        initializationTask?.let { return it }
        synchronized(initializationLock) {
            initializationTask?.let { return it }
            val task = FutureTask {
                val homeConfig = fetchAnonymousHomeConfig()
                val session = PersistentMintSession.create(
                    appContext,
                    homeConfig.visitorData,
                    homeConfig.clientName,
                    homeConfig.clientVersion,
                )
                try {
                    val sessionPoToken = session.mint(homeConfig.visitorData)
                    Log.i(
                        TAG,
                        "Global PO minter ready client=${homeConfig.clientName} " +
                            "version=${homeConfig.clientVersion} " +
                            "contentPot=${homeConfig.useContentPoToken} " +
                            "sessionPot=${homeConfig.useSessionPoToken}",
                    )
                    MintState(homeConfig, session, sessionPoToken)
                } catch (error: Throwable) {
                    session.close()
                    throw error
                }
            }
            initializationTask = task
            initializationExecutor.execute(task)
            return task
        }
    }

    private fun fetchAnonymousHomeConfig(): AnonymousHomeConfig {
        val downloader = DownloaderImpl.getInstance()
            ?: throw SabrProtocolException("DownloaderImpl is not initialized")
        val response = downloader.get(
            YOUTUBE_HOME,
            mapOf(
                "Accept-Language" to listOf("en-US"),
                "Cookie" to listOf(ANONYMOUS_COOKIE),
                "User-Agent" to listOf(SharedWebViewRuntime.USER_AGENT),
            ),
        )
        if (response.responseCode() != 200) {
            throw SabrProtocolException(
                "YouTube home initialization failed: ${response.responseCode()}",
            )
        }
        return parseAnonymousHomeConfig(response.responseBody())
    }

    private data class MintState(
        val homeConfig: AnonymousHomeConfig,
        val session: PersistentMintSession,
        val sessionPoToken: ByteArray,
    )

    @JvmStatic
    fun initialize(context: Context) {
        synchronized(initializationLock) {
            if (!::appContext.isInitialized) {
                appContext = context.applicationContext
            }
        }
        warmUp()
    }

    private const val TAG = "YoutubeGlobalPoToken"
    private const val YOUTUBE_HOME = "https://www.youtube.com"
    private const val ANONYMOUS_COOKIE = "PREF=hl=en&gl=US"
}

private class PersistentMintSession private constructor(
    context: Context,
    private val initialization: InitWaiter,
    private val visitorData: String,
    private val clientName: String,
    private val clientVersion: String,
) : Closeable {
    private val runtime = SharedWebViewRuntime.get(context.applicationContext)
    private val sessionId = runtime.registerSabrLocalDomCallbacks(Callbacks())
    private val tokenWaiters = mutableMapOf<String, TokenWaiter>()
    @Volatile
    private var closed = false
    @Volatile
    private var expiresAtMs = Long.MAX_VALUE

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

    fun isExpired(): Boolean {
        return closed || System.currentTimeMillis() >= expiresAtMs - EXPIRY_MARGIN_MS
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
            buildAttestationBody(visitorData, clientName, clientVersion),
            contentType = "application/json",
            extraHeaders = buildAttestationHeaders(
                visitorData,
                clientName,
                clientVersion,
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
                    val integrityTokenData = parseSabrIntegrityTokenData(body)
                    val integrityToken = integrityTokenData.first
                    expiresAtMs = System.currentTimeMillis() +
                        TimeUnit.SECONDS.toMillis(integrityTokenData.second)
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
            this@PersistentMintSession.onRunBotguardResult(botguardResponse)
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
        val session = AtomicReference<PersistentMintSession>()
        val error = AtomicReference<Throwable>()
    }

    companion object {
        private const val ASSET = "sabr_po_token.js"
        private const val TOKEN_TIMEOUT_MS = 30_000L
        private const val INIT_TIMEOUT_MS = 60_000L
        private const val EXPIRY_MARGIN_MS = 60_000L
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"

        @Throws(SabrProtocolException::class)
        fun create(
            context: Context,
            visitorData: String,
            clientName: String,
            clientVersion: String,
        ): PersistentMintSession {
            val initialization = InitWaiter()
            val session = PersistentMintSession(
                context,
                initialization,
                visitorData,
                clientName,
                clientVersion,
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

private data class AnonymousHomeConfig(
    val visitorData: String,
    val clientName: String,
    val clientVersion: String,
    val useContentPoToken: Boolean,
    val useSessionPoToken: Boolean,
)

private fun parseAnonymousHomeConfig(homeHtml: String): AnonymousHomeConfig {
    val matcher = YTCFG_PATTERN.matcher(homeHtml)
    var rawClientConfig: String? = null
    while (matcher.find()) {
        val candidate = matcher.group(1) ?: continue
        if (candidate.indexOf("INNERTUBE_CONTEXT") > 0) {
            rawClientConfig = candidate
        }
    }
    val clientConfig = rawClientConfig?.let { JsonParser.`object`().from(it) }
        ?: throw SabrProtocolException("YouTube home has no client context")
    val visitorData = (
        clientConfig.getString("EOM_VISITOR_DATA")
            ?.takeIf { it.isNotEmpty() }
            ?: clientConfig.getString("VISITOR_DATA")?.takeIf { it.isNotEmpty() }
        )?.replace("%3D", "=")
        ?: throw SabrProtocolException("YouTube home has no anonymous visitor data")
    val client = clientConfig.getObject("INNERTUBE_CONTEXT")?.getObject("client")
        ?: throw SabrProtocolException("YouTube home has no Innertube client context")
    val clientName = client.getString("clientName")?.takeIf { it.isNotEmpty() }
        ?: throw SabrProtocolException("YouTube home has no client name")
    val clientVersion = client.getString("clientVersion")?.takeIf { it.isNotEmpty() }
        ?: throw SabrProtocolException("YouTube home has no client version")
    val watchConfig = clientConfig.getObject("WEB_PLAYER_CONTEXT_CONFIGS")
        ?.getObject("WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH")
    val serializedFlags = watchConfig?.getString("serializedExperimentFlags")
    val experimentFlags = serializedFlags?.let(::parseExperimentFlags).orEmpty()
    val useContentPoToken = if (watchConfig == null) {
        true
    } else {
        experimentFlags["html5_generate_content_po_token"] == "true"
    }
    val useSessionPoToken =
        experimentFlags["html5_generate_session_po_token"] == "true"
    return AnonymousHomeConfig(
        visitorData,
        clientName,
        clientVersion,
        useContentPoToken,
        useSessionPoToken,
    )
}

private fun parseExperimentFlags(serializedFlags: String): Map<String, String> {
    return serializedFlags.split('&').associate { part ->
        val separator = part.indexOf('=')
        if (separator < 0) {
            part to "true"
        } else {
            part.substring(0, separator) to part.substring(separator + 1)
        }
    }
}

private val YTCFG_PATTERN = Pattern.compile("ytcfg\\.set\\((.*?)\\);", Pattern.DOTALL)

private fun buildAttestationBody(
    visitorData: String,
    clientName: String,
    clientVersion: String,
): String {
    return """{"context":{"client":{"clientName":${jsonString(clientName)},"clientVersion":${jsonString(clientVersion)},"hl":"en","gl":"US","utcOffsetMinutes":0,"visitorData":${jsonString(visitorData)}}},"engagementType":"ENGAGEMENT_TYPE_UNBOUND"}"""
}

private fun buildAttestationHeaders(
    visitorData: String,
    clientName: String,
    clientVersion: String,
): Map<String, List<String>> {
    require(clientName == "WEB") { "Unsupported PO token client: $clientName" }
    return HashMap<String, List<String>>().apply {
        put("User-Agent", listOf(SharedWebViewRuntime.USER_AGENT))
        put("Accept", listOf("application/json"))
        put("Content-Type", listOf("application/json"))
        put("Cookie", listOf("PREF=hl=en&gl=US"))
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

private data class SabrAttChallengeData(
    val program: String,
    val globalName: String,
    val interpreterJavascript: String?,
    val interpreterUrl: String?,
)

private fun parseSabrAttChallengeData(rawAttestationData: String): SabrAttChallengeData {
    val challenge = JsonParser.`object`().from(rawAttestationData).getObject("bgChallenge")
    val interpreterJavascript = challenge.getObject("interpreterJavascript")
        ?.getString("privateDoNotAccessOrElseSafeScriptWrappedValue")
        ?.takeIf { it.isNotEmpty() }
    val rawInterpreterUrl = challenge.getObject("interpreterUrl")
        ?.getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
        ?.takeIf { it.isNotEmpty() }
    val interpreterUrl = rawInterpreterUrl?.let {
        if (it.startsWith("//")) "https:$it" else it
    }
    require(interpreterJavascript != null || interpreterUrl != null) {
        "Attestation challenge has no interpreter script or URL"
    }
    return SabrAttChallengeData(
        program = challenge.getString("program"),
        globalName = challenge.getString("globalName"),
        interpreterJavascript = interpreterJavascript,
        interpreterUrl = interpreterUrl,
    )
}

private fun buildSabrAttChallengeData(
    challengeData: SabrAttChallengeData,
    interpreterJavascript: String,
): String {
    return JsonWriter.string(
        JsonObject.builder()
            .`object`("interpreterJavascript")
            .value(
                "privateDoNotAccessOrElseSafeScriptWrappedValue",
                interpreterJavascript,
            )
            .end()
            .value("program", challengeData.program)
            .value("globalName", challengeData.globalName)
            .done(),
    )
}

private fun parseSabrIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JsonParser.array().from(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData.getString(0)) to integrityTokenData.getLong(1)
}

private fun stringToSabrU8(value: String): String {
    return newUint8Array(value.toByteArray())
}

private fun csvU8ToByteArray(value: String): ByteArray {
    if (value.isBlank()) {
        return ByteArray(0)
    }
    return value.split(",").map { it.toUByte().toByte() }.toByteArray()
}

private fun base64ToU8(base64: String): String {
    return newUint8Array(base64ToByteArray(base64))
}

private fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" + contents.joinToString(separator = ",") {
        it.toUByte().toString()
    } + "])"
}

private fun base64ToByteArray(base64: String): ByteArray {
    val normalized = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')
    return Base64.getDecoder().decode(normalized)
}
