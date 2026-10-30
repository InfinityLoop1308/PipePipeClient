package org.schabi.newpipe.cast

import android.content.Context
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.schabi.newpipe.R

/** Device picker and remote control panel for casting to another PipePipe. */
object CastDialogs {

    /** Menu entry: opens the remote panel if connected, otherwise lets the user pick a receiver. */
    fun open(activity: AppCompatActivity) {
        CastController.init(activity)
        val connected = CastController.connectedDevice
        if (connected != null) RemotePanel(activity, connected).show(connect = false) else pick(activity, null)
    }

    /** Lets the user pick a receiver, then plays [url] on it and opens the remote panel. */
    fun showPicker(activity: AppCompatActivity, serviceId: Int, url: String, posMs: Long) {
        CastController.init(activity)
        pick(activity, CastController.playMessage(serviceId, url, posMs))
    }

    private fun pick(activity: AppCompatActivity, play: String?) {
        // Without something to play, offer the last receiver so a dropped session can be resumed.
        val last = if (play == null) CastController.lastDevice() else null
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        val list = ListView(activity).also { it.adapter = adapter }
        val empty = TextView(activity).apply {
            setText(R.string.cast_searching)
            setPadding(48, 32, 48, 32)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(empty)
            addView(list)
        }
        var shown: List<CastDevice> = emptyList()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.cast_to_pipepipe)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun refresh(found: List<CastDevice>) {
            val resume = last?.takeIf { l -> found.none { it.id == l.id } }
            shown = listOfNotNull(resume) + found
            adapter.clear()
            adapter.addAll(
                shown.map {
                    if (it === resume) activity.getString(R.string.cast_resume_device, it.name) else it.name
                }
            )
            empty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
        }
        refresh(emptyList())

        val pickerListener = object : CastListener {
            override fun onDevicesChanged(devices: List<CastDevice>) = refresh(devices)
        }
        CastController.listener = pickerListener
        dialog.setOnDismissListener {
            CastController.stopDiscovery()
            // Dismiss is delivered asynchronously, after a picked device may have installed its own listener.
            if (CastController.listener === pickerListener) CastController.listener = null
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val device = shown.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            if (play != null) connectAndPlay(activity, device, play, null) else RemotePanel(activity, device).show(true)
        }
        dialog.show()
        CastController.startDiscovery(activity)
    }

    private fun connectAndPlay(activity: AppCompatActivity, device: CastDevice, play: String?, pin: String?) {
        CastController.listener = object : CastListener {
            override fun onAuth(ok: Boolean, reason: String?) {
                when {
                    ok -> RemotePanel(activity, device).show(connect = false)
                    reason == CastProtocol.REASON_LOCKED -> showLocked(activity)
                    else -> askPin(activity, device, play, reason == CastProtocol.REASON_BAD_PIN)
                }
            }
            override fun onDisconnected() {
                Toast.makeText(activity, activity.getString(R.string.cast_connect_failed, device.name), Toast.LENGTH_LONG)
                    .show()
            }
        }
        CastController.connect(device, pin, play)
    }

    private fun showLocked(activity: AppCompatActivity) {
        AlertDialog.Builder(activity).setMessage(R.string.cast_locked)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun askPin(activity: AppCompatActivity, device: CastDevice, play: String?, wrong: Boolean) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            if (wrong) error = activity.getString(R.string.cast_wrong_pin)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.cast_enter_pin, device.name))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                CastController.disconnect()
                connectAndPlay(activity, device, play, input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> CastController.disconnect() }
            .show()
    }

    /**
     * Remote panel. State pushes are requested only while it is visible. When the connection drops it
     * stays open and reconnects once directly, then once after re-locating the receiver, and otherwise
     * offers a button. It also retries when the app comes back to the foreground (e.g. after unlock).
     */
    private class RemotePanel(
        private val activity: AppCompatActivity,
        private var device: CastDevice
    ) : DefaultLifecycleObserver {
        private var closed = false
        private var busy = false
        private var attempt = 0
        private var dragging = false
        private var playing = false

        private val title = TextView(activity).apply { textSize = 16f; setPadding(0, 0, 0, 24) }
        private val status = TextView(activity).apply { visibility = View.GONE; setPadding(0, 0, 0, 16) }
        private val retry = Button(activity).apply {
            setText(R.string.cast_reconnect)
            visibility = View.GONE
            setOnClickListener { startReconnect() }
        }
        private val time = TextView(activity).apply { gravity = Gravity.END }
        private val seek = SeekBar(activity)
        private lateinit var dialog: AlertDialog

        private fun button(label: String, onClick: () -> Unit) = Button(activity).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        private val listener = object : CastListener {
            override fun onState(state: CastState) {
                title.text = state.title
                playing = state.playing
                val duration = state.dur.coerceAtLeast(0)
                seek.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (!dragging) seek.progress = state.pos.coerceIn(0, seek.max.toLong()).toInt()
                time.text = "${fmt(state.pos)} / ${fmt(duration)}"
            }

            override fun onAuth(ok: Boolean, reason: String?) {
                if (closed) return
                busy = false
                if (ok) {
                    status.visibility = View.GONE
                    retry.visibility = View.GONE
                    return
                }
                closePanel()
                if (reason == CastProtocol.REASON_LOCKED) {
                    showLocked(activity)
                } else {
                    askPin(activity, device, null, reason == CastProtocol.REASON_BAD_PIN)
                }
            }

            override fun onPlayResult(ok: Boolean, reason: String?, name: String?) {
                if (ok) return
                val message = when (reason) {
                    CastProtocol.REASON_NO_PLUGIN ->
                        activity.getString(R.string.cast_plugin_missing, name ?: "", device.name)
                    CastProtocol.REASON_NO_SERVICE -> activity.getString(R.string.cast_service_unsupported, device.name)
                    else -> activity.getString(R.string.cast_play_failed, device.name)
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }

            override fun onDisconnected() {
                if (closed) return
                if (!busy) {
                    // A live session dropped.
                    if (isInteractive()) startReconnect() else showDisconnected()
                } else if (attempt == 1) {
                    attempt = 2
                    // The receiver's port changes on restart, so look up where it is now.
                    CastController.locate(activity, device.id) { found ->
                        if (closed) return@locate
                        if (found == null) {
                            showDisconnected()
                        } else {
                            device = found
                            CastController.connect(found)
                        }
                    }
                } else {
                    showDisconnected()
                }
            }
        }

        init {
            val row = LinearLayout(activity).apply {
                addView(button("⏮") { CastController.sendCommand("prev") })
                addView(button("-10s") { CastController.sendCommand("seek", (seek.progress - 10_000.0).coerceAtLeast(0.0)) })
                addView(button("▶/⏸") { CastController.sendCommand(if (playing) "pause" else "play") })
                addView(button("+10s") { CastController.sendCommand("seek", seek.progress + 10_000.0) })
                addView(button("⏭") { CastController.sendCommand("next") })
            }
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 0)
                addView(title); addView(status); addView(retry); addView(seek); addView(time); addView(row)
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar) {
                    dragging = false
                    CastController.sendCommand("seek", s.progress.toDouble())
                }
            })
            dialog = AlertDialog.Builder(activity)
                .setTitle(device.name)
                .setView(root)
                .setNegativeButton(R.string.cast_disconnect) { _, _ -> CastController.disconnect() }
                .setPositiveButton(R.string.close, null)
                .create()
            dialog.setOnDismissListener {
                closed = true
                activity.lifecycle.removeObserver(this)
                // Dismiss is asynchronous: do not touch state a newer panel or dialog now owns.
                if (CastController.listener === listener) {
                    CastController.listener = null
                    CastController.cancelLocate()
                    CastController.setWatching(false)
                }
            }
        }

        fun show(connect: Boolean) {
            CastController.listener = listener
            dialog.show()
            CastController.setWatching(true)
            if (connect) startReconnect()
            // Reports the current state immediately, and again on every return to the foreground.
            activity.lifecycle.addObserver(this)
        }

        override fun onResume(owner: LifecycleOwner) {
            if (!closed && !busy && CastController.connectedDevice == null) startReconnect()
        }

        private fun startReconnect() {
            if (closed || busy) return
            busy = true
            attempt = 1
            retry.visibility = View.GONE
            status.setText(R.string.cast_reconnecting)
            status.visibility = View.VISIBLE
            CastController.connect(device)
        }

        private fun showDisconnected() {
            busy = false
            status.setText(R.string.cast_disconnected)
            status.visibility = View.VISIBLE
            retry.visibility = View.VISIBLE
        }

        private fun closePanel() {
            closed = true
            dialog.dismiss()
        }

        private fun isInteractive(): Boolean =
            (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
