package com.ccubas.blueconnect.core.model

import android.bluetooth.BluetoothDevice

/**
 * Information about a Bluetooth device discovered during a scan.
 *
 * @param device The platform device handle. Address always reachable via [device.address].
 * @param rssi Signal strength from the last advertisement / inquiry packet, in dBm.
 *             `0` when the source doesn't carry RSSI (e.g. bonded-devices seed).
 * @param resolvedName Friendly name captured from the advertisement payload (BLE
 *                     `ScanRecord.deviceName`) or the inquiry broadcast (Classic
 *                     `EXTRA_NAME`). Prefer this over [BluetoothDevice.getName], which can
 *                     return null until SDP / pairing completes. Null when no source has
 *                     surfaced a name yet — the UI should fall back to `device.address` or
 *                     a placeholder.
 */
data class DeviceInfo(
    val device: BluetoothDevice,
    val rssi: Int,
    val resolvedName: String? = null,
)
