package org.schabi.newpipe.cast

import org.json.JSONObject

/**
 * Wire format of the PipePipe-to-PipePipe remote control. One JSON object per WebSocket text frame.
 *
 * controller -> receiver
 *   hello {id, name, token?, pin?}   first message; token wins over pin
 *   play  {service, url, pos, plugin?, pname?}
 *         resolve and play a stream on the receiver. Plugin service ids differ between installs,
 *         so plugin streams are addressed by plugin id and `service` is ignored for them.
 *   cmd   {a, v?}                    a = play|pause|next|prev|seek|speed
 *   watch {on}                       ask for 1 Hz state pushes only while a UI is visible
 *
 * receiver -> controller
 *   auth  {ok, reason?, token?, name}  reason = need_pin|bad_pin|locked
 *   state {title, playing, pos, dur, speed, idx, size}
 *   playres {ok, reason?, name?}       reason = no_plugin|no_service|failed; name = plugin name
 */
object CastProtocol {
    const val SERVICE_TYPE = "_pipepipe-cast._tcp."
    const val SERVICE_NAME_PREFIX = "PipePipe-"
    const val TXT_ID = "id"
    const val TXT_NAME = "name"

    const val T_HELLO = "hello"
    const val T_AUTH = "auth"
    const val T_PLAY = "play"
    const val T_CMD = "cmd"
    const val T_WATCH = "watch"
    const val T_STATE = "state"
    const val T_PLAY_RESULT = "playres"

    const val REASON_NEED_PIN = "need_pin"
    const val REASON_BAD_PIN = "bad_pin"
    const val REASON_LOCKED = "locked"
    const val REASON_NO_PLUGIN = "no_plugin"
    const val REASON_NO_SERVICE = "no_service"
    const val REASON_FAILED = "failed"

    fun msg(type: String, build: JSONObject.() -> Unit = {}): String =
        JSONObject().put("t", type).apply(build).toString()
}
