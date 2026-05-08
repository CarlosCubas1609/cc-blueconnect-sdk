package com.ccubas.blueconnect.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ccubas.blueconnect.core.BlueConnectClient
import com.ccubas.blueconnect.core.model.BluetoothFrame
import com.ccubas.blueconnect.core.model.ConnectionState
import com.ccubas.blueconnect.core.model.DeviceInfo
import com.ccubas.blueconnect.core.model.ScanError
import com.ccubas.blueconnect.ui.permission.rememberBluetoothEnableLauncher
import kotlinx.coroutines.launch

/**
 * Generic frame inspector. Shows the live state of a [BlueConnectClient] without
 * interpreting frame contents — useful as a debug screen for any Bluetooth device,
 * regardless of what protocol it speaks.
 *
 * For domain-specific decoding (e.g. weight scales) layer your own panels on top of
 * [FrameViewerContent], or build your own screen from scratch — this composable is just
 * a convenience.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun FrameViewerScreen(
    client: BlueConnectClient,
    modifier: Modifier = Modifier,
    /**
     * Override the default connect behavior. When null (the default), tapping a device row
     * calls [BlueConnectClient.connect] with auto-selected strategy. Apps that want to ask
     * the user which protocol to use can intercept here — for example by showing a
     * [com.ccubas.blueconnect.ui.dialog.ProtocolPickerDialog].
     */
    onConnectClick: ((BluetoothDevice) -> Unit)? = null,
    extraSections: LazyListScope.(FrameViewerSlots) -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    val isScanning by client.isScanning.collectAsStateWithLifecycle()
    val devicesMap by client.discoveredDevices.collectAsStateWithLifecycle()
    val connectionState by client.connectionState.collectAsStateWithLifecycle()
    val frame by client.lastFrame.collectAsStateWithLifecycle()

    var demoMode by remember { mutableStateOf(false) }
    var scanErrorMessage by remember { mutableStateOf<String?>(null) }

    val requestEnableBluetooth = rememberBluetoothEnableLauncher(
        onEnabled = {
            scanErrorMessage = null
            scope.launch { client.startScan() }
        },
        onDeclined = {
            scanErrorMessage = "Bluetooth must be enabled to scan."
        },
    )

    LaunchedEffect(client) {
        client.scanError.collect { error ->
            when (error) {
                is ScanError.BluetoothDisabled -> requestEnableBluetooth()
                else -> scanErrorMessage = error.message
            }
        }
    }

    val devices = devicesMap.values.toList()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("BlueConnect — Frame viewer") })
        },
    ) { padding ->
        FrameViewerContent(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            isScanning = isScanning,
            demoMode = demoMode,
            connectionState = connectionState,
            frame = frame,
            devices = devices,
            scanErrorMessage = scanErrorMessage,
            onToggleDemo = { enabled ->
                demoMode = enabled
                client.setDemoMode(enabled)
            },
            onStartScan = {
                scanErrorMessage = null
                scope.launch { client.startScan() }
            },
            onStopScan = { client.stopScan() },
            onDisconnect = { client.disconnect() },
            onClearDevices = { client.clearDevices() },
            onConnect = { device ->
                onConnectClick?.invoke(device) ?: client.connect(device)
            },
            onDismissError = { scanErrorMessage = null },
            extraSections = extraSections,
        )
    }
}

/** Slots a caller can target from [FrameViewerScreen.extraSections]. */
data class FrameViewerSlots(
    val frame: BluetoothFrame?,
    val connectionState: ConnectionState,
)

/**
 * Stateless body of [FrameViewerScreen]. Exposed so previews and tests can drive it directly,
 * and so callers that want a custom Scaffold / TopAppBar can reuse the layout.
 */
@SuppressLint("MissingPermission")
@Composable
fun FrameViewerContent(
    isScanning: Boolean,
    demoMode: Boolean,
    connectionState: ConnectionState,
    frame: BluetoothFrame?,
    devices: List<DeviceInfo>,
    scanErrorMessage: String?,
    onToggleDemo: (Boolean) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onClearDevices: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    extraSections: LazyListScope.(FrameViewerSlots) -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ControlsCard(
                isScanning = isScanning,
                demoMode = demoMode,
                onToggleDemo = onToggleDemo,
                onStartScan = onStartScan,
                onStopScan = onStopScan,
                onDisconnect = onDisconnect,
                onClearDevices = onClearDevices,
            )
        }

        scanErrorMessage?.let { message ->
            item { ErrorBanner(message = message, onDismiss = onDismissError) }
        }

        item { ConnectionStateCard(connectionState = connectionState) }

        item { FrameCard(frame = frame) }

        extraSections(FrameViewerSlots(frame = frame, connectionState = connectionState))

        item {
            Text(
                text = "Discovered devices (${devices.size})",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (devices.isEmpty()) {
            item {
                Text(
                    text = "No devices yet — start a scan.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(devices, key = { it.device.address }) { info ->
                DeviceRow(info = info, onConnect = onConnect)
            }
        }
    }
}

@Composable
private fun ControlsCard(
    isScanning: Boolean,
    demoMode: Boolean,
    onToggleDemo: (Boolean) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onClearDevices: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Demo mode", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = demoMode, onCheckedChange = onToggleDemo)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = if (isScanning) onStopScan else onStartScan,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isScanning) "Stop scan" else "Start scan")
                }

                Button(
                    onClick = onClearDevices,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear list")
                }
            }

            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ConnectionStateCard(connectionState: ConnectionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)

            val description = when (val s = connectionState) {
                is ConnectionState.Idle -> "Idle"
                is ConnectionState.Scanning -> "Scanning (${s.isScanning})"
                is ConnectionState.Connecting -> {
                    val name = s.device.safeName().ifEmpty { s.device.address }
                    "Connecting to $name — ${s.attempt.protocol} (${s.attempt.attemptNumber}/${s.attempt.totalAttempts})"
                }
                is ConnectionState.Connected -> {
                    val name = s.device.safeName().ifEmpty { s.device.address }
                    "Connected: $name"
                }
                is ConnectionState.ConnectionFailed -> {
                    val name = s.device.safeName().ifEmpty { s.device.address }
                    "Failed: $name — ${s.reason}"
                }
                is ConnectionState.Disconnected -> {
                    val name = s.device.safeName().ifEmpty { s.device.address }
                    "Disconnected: $name — ${s.reason}"
                }
            }

            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceRow(info: DeviceInfo, onConnect: (BluetoothDevice) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.device.safeName().ifEmpty { "(no name)" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${info.device.address} · RSSI ${info.rssi} dBm · type ${info.device.type}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = { onConnect(info.device) }) { Text("Connect") }
        }
    }
}

@Composable
private fun FrameCard(frame: BluetoothFrame?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Last frame",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            if (frame == null) {
                Text(
                    text = "No frame received yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                return@Column
            }

            Text(
                text = "Raw text:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = frame.data,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            frame.bytes?.let { bytes ->
                Text(
                    text = "Bytes (${bytes.size}):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = bytes.joinToString(" ") { "%02X".format(it) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.safeName(): String = try {
    name.orEmpty()
} catch (e: SecurityException) {
    ""
}

// ==================== Previews ====================

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun FrameViewerContentPreview_Empty() {
    FrameViewerContent(
        isScanning = false,
        demoMode = false,
        connectionState = ConnectionState.Idle,
        frame = null,
        devices = emptyList(),
        scanErrorMessage = null,
        onToggleDemo = {},
        onStartScan = {},
        onStopScan = {},
        onDisconnect = {},
        onClearDevices = {},
        onConnect = {},
        onDismissError = {},
    )
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun FrameViewerContentPreview_TextFrame() {
    FrameViewerContent(
        isScanning = true,
        demoMode = true,
        connectionState = ConnectionState.Idle,
        frame = BluetoothFrame(data = "ST,GS,+ 12.345kg", bytes = null),
        devices = emptyList(),
        scanErrorMessage = null,
        onToggleDemo = {},
        onStartScan = {},
        onStopScan = {},
        onDisconnect = {},
        onClearDevices = {},
        onConnect = {},
        onDismissError = {},
    )
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun FrameViewerContentPreview_BinaryFrame() {
    val bytes = byteArrayOf(
        0xCA.toByte(), 0x20, 0x0B, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x01, 0xEB.toByte(), 0x03, 0xB6.toByte(), 0x13, 0x8A.toByte(), 0xEC.toByte(),
    )
    FrameViewerContent(
        isScanning = false,
        demoMode = false,
        connectionState = ConnectionState.Idle,
        frame = BluetoothFrame(
            data = bytes.joinToString(" ") { "%02X".format(it) },
            bytes = bytes,
        ),
        devices = emptyList(),
        scanErrorMessage = "Bluetooth permissions are required",
        onToggleDemo = {},
        onStartScan = {},
        onStopScan = {},
        onDisconnect = {},
        onClearDevices = {},
        onConnect = {},
        onDismissError = {},
    )
}
