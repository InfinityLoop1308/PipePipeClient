package org.schabi.newpipe.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.schabi.newpipe.plugin.PluginManager
import java.net.URI
import java.util.ArrayDeque

data class CastDevice(val id: String, val name: String, val host: String, val port: Int)

data class CastState(
    val title: String,
    val playing: Boolean,
    val pos: Long,
    val dur: Long,
    val speed: Float,
    val index: Int,
    val size: Int
)

interface CastListener {
    fun onDevicesChanged(devices: List<CastDevice>) {}
    fun onAuth(ok: Boolean, reason: String?) {}
    fun onState(state: CastState) {}
    fun onPlayResult(ok: Boolean, reason: String?, name: String?) {}
    fun onDisconnected() {}
}

/**
 * Controller side. To save battery nothing runs in the background: discovery runs only while the
 * device picker is shown, state pushes only while the remote panel is visible, and the socket is
 * closed shortly after the last UI goes away.
 */
object CastController {
    private const val TAG = "CastController"
    private const val KEY_TOKENS = "cast_controller_tokens"
    private const val IDLE_CLOSE_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, CastDevice>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discovery: NsdManager.DiscoveryListener? = null
    private var nsd: NsdManager? = null

    private var client: WebSocketClient? = null
    private var current: CastDevice? = null
    private var pendingPin: String? = null
    private var authed = false
    private var pendingPlay: String? = null
    private var watching = false
    var listener: CastListener? = null

    private val idleClose = Runnable { disconnect() }

    val connectedDevice: CastDevice? get() = if (authed) current else null

    fun startDiscovery(context: Context) {
        if (discovery != null) return
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {
                Log.w(TAG, "discovery failed $code")
                discovery = null
            }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { resolveQueue.add(info); resolveNext() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    val gone = devices.values.firstOrNull {
                        CastProtocol.SERVICE_NAME_PREFIX + it.id.take(8) == info.serviceName
                    }
                    if (gone != null) {
                        devices.remove(gone.id)
                        listener?.onDevicesChanged(devices.values.toList())
                    }
                }
            }
        }
        discovery = l
        manager.discoverServices(CastProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stopDiscovery() {
        val l = discovery ?: return
        discovery = null
        resolveQueue.clear()
        runCatching { nsd?.stopServiceDiscovery(l) }
    }

    fun devices(): List<CastDevice> = devices.values.toList()

    // NsdManager.resolveService rejects concurrent calls, so resolve one at a time.
    private fun resolveNext() {
        if (resolving) return
        val info = resolveQueue.poll() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd?.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                main.post { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                main.post {
                    resolving = false
                    val id = i.attributes[CastProtocol.TXT_ID]?.decodeToString()
                    val name = i.attributes[CastProtocol.TXT_NAME]?.decodeToString()
                    @Suppress("DEPRECATION")
                    val host = i.host?.hostAddress
                    if (id != null && host != null && id != ownId) {
                        devices[id] = CastDevice(id, name ?: i.serviceName, host, i.port)
                        listener?.onDevicesChanged(devices.values.toList())
                    }
                    resolveNext()
                }
            }
        })
    }

    private var ownId: String? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        ownId = CastReceiver.deviceId(context)
    }

    /** Connects (reusing an open session) and optionally sends a play request once authenticated. */
    fun connect(device: CastDevice, pin: String? = null, thenPlay: String? = null) {
        main.removeCallbacks(idleClose)
        if (authed && current?.id == device.id && client?.isOpen == true) {
            thenPlay?.let { client?.send(it) }
            return
        }
        disconnect()
        current = device
        pendingPin = pin
        pendingPlay = thenPlay
        val c = object : WebSocketClient(URI("ws://${device.host}:${device.port}")) {
            override fun onOpen(h: ServerHandshake) {
                val token = tokens().optString(device.id)
                send(
                    CastProtocol.msg(CastProtocol.T_HELLO) {
                        put("id", ownId)
                        put("name", CastReceiver.deviceName())
                        if (token.isNotEmpty()) put("token", token)
                        pendingPin?.let { put("pin", it) }
                    }
                )
            }
            override fun onMessage(message: String) {
                main.post { onFrame(message) }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                main.post { if (client === this) { clear(); listener?.onDisconnected() } }
            }
            override fun onError(ex: Exception) {
                Log.w(TAG, "client error", ex)
            }
        }
        c.connectionLostTimeout = 0 // no periodic pings: they would keep the radio awake
        client = c
        c.connect()
    }

    private fun onFrame(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("t")) {
            CastProtocol.T_AUTH -> {
                val ok = json.optBoolean("ok")
                authed = ok
                if (ok) {
                    json.optString("token").takeIf { it.isNotEmpty() }?.let { saveToken(current!!.id, it) }
                    pendingPlay?.let { client?.send(it) }
                    pendingPlay = null
                    if (watching) client?.send(CastProtocol.msg(CastProtocol.T_WATCH) { put("on", true) })
                } else if (json.optString("reason") != CastProtocol.REASON_NEED_PIN) {
                    // A stale token or wrong PIN: forget the token so the next attempt asks for a PIN.
                    current?.let { saveToken(it.id, "") }
                }
                listener?.onAuth(ok, json.optString("reason").takeIf { it.isNotEmpty() })
            }
            CastProtocol.T_PLAY_RESULT -> listener?.onPlayResult(
                json.optBoolean("ok"),
                json.optString("reason").takeIf { it.isNotEmpty() },
                json.optString("name").takeIf { it.isNotEmpty() }
            )
            CastProtocol.T_STATE -> listener?.onState(
                CastState(
                    json.optString("title"), json.optBoolean("playing"), json.optLong("pos"),
                    json.optLong("dur"), json.optDouble("speed", 1.0).toFloat(),
                    json.optInt("idx"), json.optInt("size")
                )
            )
        }
    }

    fun sendCommand(action: String, value: Double? = null) {
        if (!authed) return
        client?.send(CastProtocol.msg(CastProtocol.T_CMD) { put("a", action); value?.let { put("v", it) } })
    }

    fun playMessage(serviceId: Int, url: String, posMs: Long): String {
        val plugin = PluginManager.getLoadedManifest(serviceId)
        return CastProtocol.msg(CastProtocol.T_PLAY) {
            put("service", serviceId)
            put("url", url)
            put("pos", posMs)
            if (plugin != null) {
                put("plugin", plugin.pluginId)
                put("pname", plugin.name)
            }
        }
    }

    /** Call with true when a remote panel becomes visible and false when it is hidden. */
    fun setWatching(on: Boolean) {
        watching = on
        if (authed) client?.send(CastProtocol.msg(CastProtocol.T_WATCH) { put("on", on) })
        main.removeCallbacks(idleClose)
        if (!on && client != null) main.postDelayed(idleClose, IDLE_CLOSE_MS)
    }

    fun disconnect() {
        main.removeCallbacks(idleClose)
        val c = client
        clear()
        runCatching { c?.close() }
    }

    private fun clear() {
        client = null
        authed = false
        current = null
        pendingPin = null
        pendingPlay = null
    }

    private fun tokens(): JSONObject {
        val ctx = appContext ?: return JSONObject()
        val raw = PreferenceManager.getDefaultSharedPreferences(ctx).getString(KEY_TOKENS, "{}")
        return runCatching { JSONObject(raw!!) }.getOrDefault(JSONObject())
    }

    private fun saveToken(deviceId: String, token: String) {
        val ctx = appContext ?: return
        val all = tokens().put(deviceId, token)
        PreferenceManager.getDefaultSharedPreferences(ctx).edit { putString(KEY_TOKENS, all.toString()) }
    }
}
