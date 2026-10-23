// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import chromahub.rhythm.app.R
import kotlinx.coroutines.delay

/** Scan is explicit and bounded; paired phones can be selected without rediscovering them. */
@SuppressLint("MissingPermission")
@Composable
internal fun BluetoothDiscovery(onPermission: (() -> Unit) -> Unit, enabled: Boolean, onConnect: (String) -> Unit) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(emptyMap<String, String>()) }
    var scanning by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) scanning = false
                if (intent.action == BluetoothDevice.ACTION_FOUND) {
                    @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    runCatching { device?.let { if (devices.size < 32) devices = devices + (it.address to StreamingProtocol.name(it.name.orEmpty().ifBlank { it.address })) } }
                }
            }
        }
        // Discovery broadcasts originate in privileged Bluetooth processes, not only the system UID.
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver); runCatching { BluetoothStreaming.adapter()?.cancelDiscovery() } }
    }
    LaunchedEffect(scanning) { if (scanning) { delay(20_000); runCatching { BluetoothStreaming.adapter()?.cancelDiscovery() }; scanning = false } }
    OutlinedButton({ onPermission {
        runCatching {
            val adapter = BluetoothStreaming.adapter() ?: error("No Bluetooth")
            check(adapter.isEnabled)
            failed = false
            if (scanning) { adapter.cancelDiscovery(); scanning = false }
            else {
                devices = adapter.bondedDevices.take(32).associate { it.address to StreamingProtocol.name(it.name.orEmpty().ifBlank { it.address }) }
                scanning = adapter.startDiscovery(); failed = !scanning
            }
        }.onFailure { failed = true; scanning = false }
    } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (scanning) R.string.nearby_search_stop else R.string.nearby_bluetooth_search))
    }
    if (failed) Text(stringResource(R.string.nearby_bluetooth_error))
    if (devices.isNotEmpty()) Text(stringResource(R.string.nearby_bluetooth_devices), style = MaterialTheme.typography.bodySmall)
    devices.forEach { (address, name) ->
        TextButton({ onPermission { runCatching { BluetoothStreaming.adapter()?.cancelDiscovery() }; scanning = false; onConnect(address) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(name)
        }
    }
}
