package org.schabi.newpipe.player.datasource

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrPoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class LocalDomPoTokenProvider(context: Context) : SabrPoTokenProvider {
    private data class CachedToken(val token: ByteArray, val mintedAtMs: Long)

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, CachedToken>()
    private val mintLocks = ConcurrentHashMap<String, Any>()
    private val generatorLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generatorVisitorData: String? = null
    private var generator: LocalDomPoTokenGenerator? = null

    override fun getPoToken(
        info: YoutubeSabrInfo,
        streamState: YoutubeSabrStreamState,
    ): ByteArray? = getPoToken(info, streamState, false)

    override fun getPoToken(
        info: YoutubeSabrInfo,
        streamState: YoutubeSabrStreamState,
        forceRefresh: Boolean,
    ): ByteArray? {
        val videoId = info.videoId
        if (forceRefresh) {
            cache.remove(videoId)
            prefs.edit().remove(videoId).apply()
        }
        synchronized(mintLocks.computeIfAbsent(videoId) { Any() }) {
            val now = System.currentTimeMillis()
            val cached = cache[videoId] ?: diskLoad(videoId)?.also { cache[videoId] = it }
            if (cached != null && now - cached.mintedAtMs < TOKEN_TTL_MS) {
                Log.i(TAG, "cache hit video=$videoId bytes=${cached.token.size}")
                return cached.token
            }
            val visitorData = info.visitorData
                ?: throw SabrProtocolException("Missing visitorData for Local DOM PO token")
            val token = ensureGenerator(visitorData).generateRawPoToken(videoId)
            cache[videoId] = CachedToken(token, now)
            diskSave(videoId, token, now)
            Log.i(TAG, "mint complete video=$videoId bytes=${token.size}")
            return token
        }
    }

    fun hasCachedToken(videoId: String): Boolean {
        val mem = cache[videoId]
        if (mem != null && System.currentTimeMillis() - mem.mintedAtMs < TOKEN_TTL_MS) {
            return true
        }
        return diskLoad(videoId) != null
    }

    private fun ensureGenerator(visitorData: String): LocalDomPoTokenGenerator {
        synchronized(generatorLock) {
            val current = generator
            if (current != null && !current.isExpired() && generatorVisitorData == visitorData) {
                return current
            }
            current?.let { mainHandler.post { it.close() } }
            val fresh = LocalDomPoTokenGenerator.create(appContext)
            // Match Web client behavior: mint visitorData-bound streaming POT once before video POTs.
            fresh.generateRawPoToken(visitorData)
            generator = fresh
            generatorVisitorData = visitorData
            return fresh
        }
    }

    private fun diskLoad(videoId: String): CachedToken? {
        val value = prefs.getString(videoId, null) ?: return null
        val sep = value.indexOf('|')
        if (sep <= 0) {
            return null
        }
        return try {
            val mintedAt = value.substring(0, sep).toLong()
            if (System.currentTimeMillis() - mintedAt >= TOKEN_TTL_MS) {
                prefs.edit().remove(videoId).apply()
                null
            } else {
                CachedToken(Base64.getUrlDecoder().decode(value.substring(sep + 1)), mintedAt)
            }
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    private fun diskSave(videoId: String, token: ByteArray, mintedAt: Long) {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(token)
        prefs.edit().putString(videoId, "$mintedAt|$encoded").commit()
    }

    companion object {
        private const val TAG = "SabrLocalDomPoToken"
        private const val PREFS = "sabr_local_dom_video_token_cache"
        private const val TOKEN_TTL_MS = 6L * 60L * 60L * 1000L
    }
}
