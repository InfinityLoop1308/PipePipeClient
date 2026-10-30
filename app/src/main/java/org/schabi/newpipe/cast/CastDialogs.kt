package org.schabi.newpipe.cast

import android.app.Dialog
import android.content.Context
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.schabi.newpipe.R

/** Device picker and remote control panel for casting to another PipePipe. */
object CastDialogs {

    /** Lets the user pick a receiver, then plays [url] on it and opens the remote panel. */
    fun showPicker(activity: AppCompatActivity, serviceId: Int, url: String, posMs: Long) {
        CastController.init(activity)
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

        CastController.listener = object : CastListener {
            override fun onDevicesChanged(devices: List<CastDevice>) {
                shown = devices
                adapter.clear()
                adapter.addAll(devices.map { it.name })
                empty.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        dialog.setOnDismissListener {
            CastController.stopDiscovery()
            CastController.listener = null
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val device = shown.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            connectAndPlay(activity, device, CastController.playMessage(serviceId, url, posMs), null)
        }
        dialog.show()
        CastController.startDiscovery(activity)
    }

    private fun connectAndPlay(activity: AppCompatActivity, device: CastDevice, play: String, pin: String?) {
        CastController.listener = object : CastListener {
            override fun onAuth(ok: Boolean, reason: String?) {
                when {
                    ok -> showRemote(activity, device)
                    reason == CastProtocol.REASON_LOCKED ->
                        AlertDialog.Builder(activity).setMessage(R.string.cast_locked)
                            .setPositiveButton(android.R.string.ok, null).show()
                    else -> askPin(activity, device, play, reason == CastProtocol.REASON_BAD_PIN)
                }
            }
        }
        CastController.connect(device, pin, play)
    }

    private fun askPin(activity: AppCompatActivity, device: CastDevice, play: String, wrong: Boolean) {
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

    /** Remote panel. State updates are requested only while this dialog is visible. */
    fun showRemote(activity: AppCompatActivity, device: CastDevice) {
        val title = TextView(activity).apply { textSize = 16f; setPadding(0, 0, 0, 24) }
        val time = TextView(activity).apply { gravity = Gravity.END }
        val seek = SeekBar(activity)
        var dragging = false
        var duration = 0L

        fun button(label: String, onClick: () -> Unit) = Button(activity).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        var playing = false
        val playPause = button("▶/⏸") { CastController.sendCommand(if (playing) "pause" else "play") }
        val row = LinearLayout(activity).apply {
            addView(button("⏮") { CastController.sendCommand("prev") })
            addView(button("-10s") { CastController.sendCommand("seek", (seek.progress - 10_000.0).coerceAtLeast(0.0)) })
            addView(playPause)
            addView(button("+10s") { CastController.sendCommand("seek", seek.progress + 10_000.0) })
            addView(button("⏭") { CastController.sendCommand("next") })
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
            addView(title); addView(seek); addView(time); addView(row)
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                CastController.sendCommand("seek", s.progress.toDouble())
            }
        })

        val dialog: Dialog = AlertDialog.Builder(activity)
            .setTitle(device.name)
            .setView(root)
            .setNegativeButton(R.string.cast_disconnect) { _, _ -> CastController.disconnect() }
            .setPositiveButton(R.string.close, null)
            .create()

        CastController.listener = object : CastListener {
            override fun onState(state: CastState) {
                title.text = state.title
                playing = state.playing
                duration = state.dur.coerceAtLeast(0)
                seek.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (!dragging) seek.progress = state.pos.coerceIn(0, seek.max.toLong()).toInt()
                time.text = "${fmt(state.pos)} / ${fmt(duration)}"
            }
            override fun onDisconnected() { if (dialog.isShowing) dialog.dismiss() }
        }
        dialog.setOnDismissListener { CastController.setWatching(false) }
        dialog.show()
        CastController.setWatching(true)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
