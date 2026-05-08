package com.ccubas.blueconnect.core.model

import android.bluetooth.BluetoothDevice

/**
 * Information about a Bluetooth device discovered during a scan.
 *
 * Name and address are not duplicated here — read them from [device].
 */
data class DeviceInfo(
    val device: BluetoothDevice,
    val rssi: Int,
)
