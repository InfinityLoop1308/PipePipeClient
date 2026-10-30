package org.schabi.newpipe.cast

import android.content.Context
import android.os.PowerManager
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.util.ThemeHelper

/** Device picker and remote control panel for casting to another PipePipe. */
object CastDialogs {

    /** Menu entry: opens the remote panel if connected, otherwise lets the user pick a receiver. */
    fun open(activity: AppCompatActivity) {
        CastController.init(activity)
        val host = Host(activity)
        val connected = CastController.connectedDevice
        host.show(if (connected != null) RemotePanel(host, connected, connect = false) else PickerScreen(host, null))
    }

    /** Lets the user pick a receiver, then plays [url] on it and opens the remote panel. */
    fun showPicker(activity: AppCompatActivity, serviceId: Int, url: String, posMs: Long) {
        CastController.init(activity)
        val host = Host(activity)
        val play = CastController.playMessage(serviceId, url, posMs)
        val paired = CastController.connectedDevice ?: CastController.lastDevice()
        // A receiver is already paired: let the user choose between controlling it and casting anew.
        host.show(if (paired != null) ChoiceScreen(host, paired, play) else PickerScreen(host, play))
    }

    /**
     * Hosts one dialog at a time. It lives in a ComposeView attached to the activity's content, so the
     * dialogs get the activity's lifecycle and saved-state owners; the view itself draws nothing.
     */
    private class Host(val activity: AppCompatActivity) {
        private var screen by mutableStateOf<Screen?>(null)
        private val isLight = ThemeHelper.isLightThemeSelected(activity)
        private val view = ComposeView(activity).apply {
            setContent {
                MaterialTheme(colorScheme = if (isLight) lightColorScheme() else darkColorScheme()) {
                    screen?.let { s -> androidx.compose.runtime.key(s) { Dialog(onDismissRequest = { dismiss() }) { s.Content() } } }
                }
            }
        }

        fun show(next: Screen) {
            screen?.onClose()
            if (view.parent == null) {
                activity.findViewById<ViewGroup>(android.R.id.content).addView(
                    view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }
            screen = next
            next.onShow()
        }

        fun dismiss() {
            screen?.onClose()
            screen = null
            (view.parent as? ViewGroup)?.removeView(view)
        }
    }

    private abstract class Screen {
        @Composable abstract fun Content()
        open fun onShow() {}
        open fun onClose() {}
    }

    @Composable
    private fun Card(
        title: String?,
        buttons: @Composable () -> Unit,
        content: @Composable () -> Unit
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp)) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
                }
                content()
                androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { buttons() }
            }
        }
    }

    private class ChoiceScreen(
        private val host: Host,
        private val paired: CastDevice,
        private val play: String
    ) : Screen() {
        @Composable
        override fun Content() {
            val activity = host.activity
            Card(
                title = stringResource(R.string.cast_to_pipepipe),
                buttons = { TextButton({ host.dismiss() }) { Text(stringResource(android.R.string.cancel)) } }
            ) {
                Column {
                    ChoiceRow(stringResource(R.string.cast_resume_device, paired.name)) {
                        host.show(RemotePanel(host, paired, connect = CastController.connectedDevice?.id != paired.id))
                    }
                    ChoiceRow(stringResource(R.string.cast_to_pipepipe)) { host.show(PickerScreen(host, play)) }
                }
            }
        }
    }

    @Composable
    private fun ChoiceRow(text: String, onClick: () -> Unit) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)
        )
    }

    private class PickerScreen(private val host: Host, private val play: String?) : Screen() {
        private val activity = host.activity
        // Without something to play, offer the last receiver so a dropped session can be resumed.
        private val last = if (play == null) CastController.lastDevice() else null
        private var found by mutableStateOf<List<CastDevice>>(emptyList())
        private val listener = object : CastListener {
            override fun onDevicesChanged(devices: List<CastDevice>) { found = devices }
        }

        override fun onShow() {
            CastController.listener = listener
            CastController.startDiscovery(activity)
        }

        override fun onClose() {
            CastController.stopDiscovery()
            if (CastController.listener === listener) CastController.listener = null
        }

        @Composable
        override fun Content() {
            val resume = last?.takeIf { l -> found.none { it.id == l.id } }
            val shown = listOfNotNull(resume) + found
            Card(
                title = stringResource(R.string.cast_to_pipepipe),
                buttons = { TextButton({ host.dismiss() }) { Text(stringResource(android.R.string.cancel)) } }
            ) {
                Column {
                    if (found.isEmpty()) {
                        Text(stringResource(R.string.cast_searching), color = MaterialTheme.colorScheme.onSurface)
                    }
                    LazyColumn {
                        itemsIndexed(shown) { _, device ->
                            val label = if (device === resume) {
                                stringResource(R.string.cast_resume_device, device.name)
                            } else {
                                device.name
                            }
                            ChoiceRow(label) { pick(device) }
                        }
                    }
                }
            }
        }

        private fun pick(device: CastDevice) {
            host.dismiss()
            if (play != null) connectAndPlay(host, device, play, null) else host.show(RemotePanel(host, device, true))
        }
    }

    private fun connectAndPlay(host: Host, device: CastDevice, play: String?, pin: String?) {
        val activity = host.activity
        CastController.listener = object : CastListener {
            override fun onAuth(ok: Boolean, reason: String?) {
                when {
                    ok -> host.show(RemotePanel(host, device, connect = false))
                    reason == CastProtocol.REASON_LOCKED -> host.show(LockedScreen(host))
                    else -> host.show(PinScreen(host, device, play, reason == CastProtocol.REASON_BAD_PIN))
                }
            }
            override fun onDisconnected() {
                Toast.makeText(activity, activity.getString(R.string.cast_connect_failed, device.name), Toast.LENGTH_LONG)
                    .show()
            }
        }
        CastController.connect(device, pin, play)
    }

    private class LockedScreen(private val host: Host) : Screen() {
        @Composable
        override fun Content() {
            Card(
                title = null,
                buttons = { TextButton({ host.dismiss() }) { Text(stringResource(android.R.string.ok)) } }
            ) { Text(stringResource(R.string.cast_locked), color = MaterialTheme.colorScheme.onSurface) }
        }
    }

    private class PinScreen(
        private val host: Host,
        private val device: CastDevice,
        private val play: String?,
        private val wrong: Boolean
    ) : Screen() {
        @Composable
        override fun Content() {
            var pin by remember { mutableStateOf("") }
            Card(
                title = stringResource(R.string.cast_enter_pin, device.name),
                buttons = {
                    TextButton({ host.dismiss(); CastController.disconnect() }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton({
                        host.dismiss()
                        CastController.disconnect()
                        connectAndPlay(host, device, play, pin)
                    }) { Text(stringResource(android.R.string.ok)) }
                }
            ) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    singleLine = true,
                    isError = wrong,
                    supportingText = if (wrong) ({ Text(stringResource(R.string.cast_wrong_pin)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }

    /**
     * Remote panel. State pushes are requested only while it is visible. When the connection drops it
     * stays open and reconnects once directly, then once after re-locating the receiver, and otherwise
     * offers a button. It also retries when the app comes back to the foreground (e.g. after unlock).
     */
    private class RemotePanel(
        private val host: Host,
        private var device: CastDevice,
        private val connect: Boolean
    ) : Screen(), DefaultLifecycleObserver {
        private val activity = host.activity
        private var closed = false
        private var busy = false
        private var attempt = 0
        private var dragging = false
        private var playing by mutableStateOf(false)
        private var titleText by mutableStateOf("")
        private var posMs by mutableStateOf(0L)
        private var durMs by mutableStateOf(0L)
        private var sliderValue by mutableFloatStateOf(0f)
        private var statusRes by mutableStateOf<Int?>(null)
        private var showRetry by mutableStateOf(false)

        private fun seekStepMs(): Double {
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val value = prefs.getString(
                activity.getString(R.string.seek_duration_key),
                activity.getString(R.string.seek_duration_default_value)
            )
            return value?.toDoubleOrNull() ?: 10_000.0
        }

        private fun seekBy(direction: Int) {
            val target = posMs + direction * seekStepMs()
            CastController.sendCommand("seek", target.coerceIn(0.0, durMs.toDouble().coerceAtLeast(0.0)))
        }

        private val listener = object : CastListener {
            override fun onState(state: CastState) {
                titleText = state.title
                playing = state.playing
                durMs = state.dur.coerceAtLeast(0)
                if (!dragging) {
                    posMs = state.pos.coerceIn(0, durMs)
                    sliderValue = posMs.toFloat()
                }
            }

            override fun onAuth(ok: Boolean, reason: String?) {
                if (closed) return
                busy = false
                if (ok) {
                    statusRes = null
                    showRetry = false
                    return
                }
                if (reason == CastProtocol.REASON_LOCKED) {
                    host.show(LockedScreen(host))
                } else {
                    host.show(PinScreen(host, device, null, reason == CastProtocol.REASON_BAD_PIN))
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

        override fun onShow() {
            CastController.listener = listener
            CastController.setWatching(true)
            if (connect) startReconnect()
            // Reports the current state immediately, and again on every return to the foreground.
            activity.lifecycle.addObserver(this)
        }

        override fun onClose() {
            closed = true
            activity.lifecycle.removeObserver(this)
            if (CastController.listener === listener) {
                CastController.listener = null
                CastController.cancelLocate()
                CastController.setWatching(false)
            }
        }

        override fun onResume(owner: LifecycleOwner) {
            if (!closed && !busy && CastController.connectedDevice == null) startReconnect()
        }

        private fun startReconnect() {
            if (closed || busy) return
            busy = true
            attempt = 1
            showRetry = false
            statusRes = R.string.cast_reconnecting
            CastController.connect(device)
        }

        private fun showDisconnected() {
            busy = false
            statusRes = R.string.cast_disconnected
            showRetry = true
        }

        private fun isInteractive(): Boolean =
            (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

        @Composable
        override fun Content() {
            Card(
                title = device.name,
                buttons = {
                    TextButton({ host.dismiss(); CastController.disconnect() }) {
                        Text(stringResource(R.string.cast_disconnect))
                    }
                    TextButton({ host.dismiss() }) { Text(stringResource(R.string.close)) }
                }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        titleText,
                        fontSize = 16.sp,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    statusRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.onSurface) }
                    if (showRetry) {
                        TextButton({ startReconnect() }) { Text(stringResource(R.string.cast_reconnect)) }
                    }
                    val max = durMs.toFloat().coerceAtLeast(1f)
                    Slider(
                        value = sliderValue.coerceIn(0f, max),
                        onValueChange = { dragging = true; sliderValue = it },
                        onValueChangeFinished = {
                            dragging = false
                            posMs = sliderValue.toLong()
                            CastController.sendCommand("seek", sliderValue.toDouble())
                        },
                        valueRange = 0f..max
                    )
                    Text(
                        "${fmt(if (dragging) sliderValue.toLong() else posMs)} / ${fmt(durMs)}",
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ControlButton(Icons.Filled.SkipPrevious, R.string.previous) { CastController.sendCommand("prev") }
                        ControlButton(Icons.Filled.FastRewind, null) { seekBy(-1) }
                        IconButton(
                            onClick = { CastController.sendCommand(if (playing) "pause" else "play") },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Crossfade(targetState = playing, label = "playPause") { isPlaying ->
                                Icon(
                                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        ControlButton(Icons.Filled.FastForward, null) { seekBy(1) }
                        ControlButton(Icons.Filled.SkipNext, null) { CastController.sendCommand("next") }
                    }
                }
            }
        }

        @Composable
        private fun ControlButton(icon: ImageVector, desc: Int?, onClick: () -> Unit) {
            IconButton(onClick = onClick) {
                Icon(icon, contentDescription = desc?.let { stringResource(it) }, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
