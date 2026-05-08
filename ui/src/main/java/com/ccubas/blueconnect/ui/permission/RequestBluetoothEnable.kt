package com.ccubas.blueconnect.ui.permission

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Compose helper that produces a callback for showing the system "Turn on Bluetooth?" dialog
 * via [BluetoothAdapter.ACTION_REQUEST_ENABLE].
 *
 * Typical use: subscribe to [com.ccubas.blueconnect.core.BlueConnectClient.scanError] and
 * call the returned lambda when you observe [com.ccubas.blueconnect.core.model.ScanError.BluetoothDisabled].
 *
 * Requires `BLUETOOTH_CONNECT` (API 31+) — request it via [RequestBluetoothPermissions]
 * before triggering this.
 *
 * @param onEnabled Invoked after the user accepts the prompt. Good place to retry the scan.
 * @param onDeclined Invoked if the user dismisses or denies the prompt.
 */
@Composable
fun rememberBluetoothEnableLauncher(
    onEnabled: () -> Unit = {},
    onDeclined: () -> Unit = {},
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onEnabled() else onDeclined()
    }

    return remember(launcher) {
        {
            launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
}
