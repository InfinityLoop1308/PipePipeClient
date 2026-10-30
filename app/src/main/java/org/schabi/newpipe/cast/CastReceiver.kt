package org.schabi.newpipe.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.plugin.PluginManager
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.NavigationHelper
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID

/** Accepts remote control connections from other PipePipe instances on the local network. */
class CastReceiver private constructor(private val context: Context) :
    WebSocketServer(InetSocketAddress(0)) {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val random = SecureRandom()

    private var pin = newPin()
    private var failures = 0
    private var lastPinToast = 0L
    private var registration: NsdManager.RegistrationListener? = null
    private var loading: Disposable? = null

    private val watchers = HashSet<WebSocket>()
    private val ticker = object : Runnable {
        override fun run() {
            if (watchers.isEmpty()) return
            pushState()
            main.postDelayed(this, 1000)
        }
    }

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
    }

    override fun onStart() {
        val serverPort = this.port
        val info = NsdServiceInfo().apply {
            serviceName = CastProtocol.SERVICE_NAME_PREFIX + id().take(8)
            serviceType = CastProtocol.SERVICE_TYPE
            port = serverPort
            setAttribute(CastProtocol.TXT_ID, id())
            setAttribute(CastProtocol.TXT_NAME, deviceName())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                Log.w(TAG, "NSD registration failed: $code")
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        main.post { watchers.remove(conn) }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w(TAG, "websocket error", ex)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val json = try {
            JSONObject(message)
        } catch (e: Exception) {
            return
        }
        val type = json.optString("t")
        if (type == CastProtocol.T_HELLO) {
            main.post { authenticate(conn, json) }
            return
        }
        if (conn.getAttachment<Boolean>() != true) return
        main.post { handle(conn, type, json) }
    }

    private fun authenticate(conn: WebSocket, json: JSONObject) {
        val token = json.optString("token")
        val given = json.optString("pin")
        val name = json.optString("name", "?")
        val tokens = prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty()

        fun reply(ok: Boolean, reason: String? = null, newToken: String? = null) {
            conn.send(
                CastProtocol.msg(CastProtocol.T_AUTH) {
                    put("ok", ok)
                    put("name", deviceName())
                    reason?.let { put("reason", it) }
                    newToken?.let { put("token", it) }
                }
            )
        }

        when {
            token.isNotEmpty() && token in tokens -> {
                conn.setAttachment(true)
                reply(true)
            }
            failures >= MAX_FAILURES -> reply(false, CastProtocol.REASON_LOCKED)
            given.isEmpty() -> {
                reply(false, CastProtocol.REASON_NEED_PIN)
                showPin(name)
            }
            given == pin -> {
                val fresh = UUID.randomUUID().toString()
                prefs.edit { putStringSet(KEY_TOKENS, tokens + fresh) }
                failures = 0
                pin = newPin()
                conn.setAttachment(true)
                reply(true, newToken = fresh)
            }
            else -> {
                failures++
                reply(false, CastProtocol.REASON_BAD_PIN)
            }
        }
    }

    private fun showPin(clientName: String) {
        val now = System.currentTimeMillis()
        if (now - lastPinToast < 5000) return
        lastPinToast = now
        Toast.makeText(
            context,
            context.getString(R.string.cast_pairing_code_toast, clientName, pin),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun handle(conn: WebSocket, type: String, json: JSONObject) {
        when (type) {
            CastProtocol.T_PLAY -> play(conn, json)
            CastProtocol.T_CMD -> command(json.optString("a"), json.optDouble("v", 0.0))
            CastProtocol.T_WATCH -> {
                if (json.optBoolean("on")) {
                    val wasEmpty = watchers.isEmpty()
                    watchers.add(conn)
                    pushState()
                    if (wasEmpty) main.postDelayed(ticker, 1000)
                } else {
                    watchers.remove(conn)
                }
            }
        }
    }

    private fun play(conn: WebSocket, json: JSONObject) {
        val url = json.optString("url")
        val pos = json.optLong("pos")
        val pluginId = json.optString("plugin")
        val pluginName = json.optString("pname").ifEmpty { pluginId }

        fun reply(ok: Boolean, reason: String? = null) {
            if (!conn.isOpen) return
            conn.send(
                CastProtocol.msg(CastProtocol.T_PLAY_RESULT) {
                    put("ok", ok)
                    reason?.let { put("reason", it) }
                    if (pluginName.isNotEmpty()) put("name", pluginName)
                }
            )
        }

        // Plugin service ids are assigned per install, so look the plugin up by its stable id.
        val serviceId = if (pluginId.isNotEmpty()) {
            PluginManager.getServiceIdByPluginId(pluginId)
        } else {
            json.optInt("service").takeIf { id -> ServiceList.getById(id) != null }
        }
        if (url.isEmpty() || serviceId == null) {
            reply(
                false,
                if (pluginId.isNotEmpty()) CastProtocol.REASON_NO_PLUGIN else CastProtocol.REASON_NO_SERVICE
            )
            return
        }

        loading?.dispose()
        loading = ExtractorHelper.getStreamInfo(serviceId, url, false)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ info ->
                NavigationHelper.playOnMainPlayer(context, SinglePlayQueue(info, pos), false)
                reply(true)
            }, {
                Log.w(TAG, "cast play failed", it)
                reply(false, CastProtocol.REASON_FAILED)
            })
    }

    private fun command(action: String, value: Double) {
        val player = PlayerHolder.getInstance().player ?: return
        when (action) {
            "play" -> player.play()
            "pause" -> player.pause()
            "next" -> player.playNext()
            "prev" -> player.playPrevious()
            "seek" -> player.seekTo(value.toLong())
            "speed" -> player.setPlaybackSpeed(value.toFloat())
        }
        pushState()
    }

    private fun pushState() {
        if (watchers.isEmpty()) return
        val player = PlayerHolder.getInstance().player
        val text = CastProtocol.msg(CastProtocol.T_STATE) {
            if (player != null) {
                put("title", player.currentStreamInfo.map { it.name }.orElse(""))
                put("playing", player.isPlaying)
                put("pos", player.currentPosition)
                put("dur", player.duration)
                put("speed", player.playbackSpeed.toDouble())
                put("idx", player.playQueue?.index ?: 0)
                put("size", player.playQueue?.size() ?: 0)
            } else {
                put("title", "")
            }
        }
        watchers.forEach { if (it.isOpen) it.send(text) }
    }

    private fun shutdown() {
        main.removeCallbacks(ticker)
        watchers.clear()
        loading?.dispose()
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        runCatching { stop(500) }
    }

    private fun newPin() = "%04d".format(random.nextInt(10000))

    private fun id(): String = deviceId(context)

    companion object {
        private const val TAG = "CastReceiver"
        private const val KEY_TOKENS = "cast_receiver_tokens"
        private const val KEY_ID = "cast_device_id"
        private const val MAX_FAILURES = 5

        private var instance: CastReceiver? = null

        fun deviceId(context: Context): String {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
                prefs.edit { putString(KEY_ID, it) }
            }
        }

        fun deviceName(): String = Build.MODEL ?: "Android"

        @Synchronized
        fun setEnabled(context: Context, enabled: Boolean) {
            val app = context.applicationContext
            if (enabled && instance == null) {
                instance = CastReceiver(app).also { it.start() }
            } else if (!enabled) {
                instance?.shutdown()
                instance = null
            }
        }

        /** Phones opt in; TVs are receivers by default. */
        fun isEnabledByUser(context: Context): Boolean {
            val key = context.getString(R.string.cast_receiver_enabled_key)
            val default = org.schabi.newpipe.util.DeviceUtils.isTv(context)
            return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key, default)
        }
    }
}
