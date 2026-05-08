package com.ccubas.blueconnect.core.model

import android.bluetooth.BluetoothDevice

/**
 * Lifecycle states of a Bluetooth connection.
 *
 * - [Idle]: Initial / final state. Nothing in flight.
 * - [Scanning]: Looking for devices.
 * - [Connecting]: Trying to establish a connection. Carries the active [Attempt].
 * - [Connected]: Connection is live.
 * - [ConnectionFailed]: A connect attempt failed.
 * - [Disconnected]: A previously live connection was lost.
 */
sealed class ConnectionState {

    object Idle : ConnectionState()

    data class Scanning(val isScanning: Boolean) : ConnectionState()

    data class Connecting(val device: BluetoothDevice, val attempt: Attempt) : ConnectionState()

    data class Connected(val device: BluetoothDevice) : ConnectionState()

    data class ConnectionFailed(
        val device: BluetoothDevice,
        val reason: String,
        val attempt: Attempt,
    ) : ConnectionState()

    data class Disconnected(val device: BluetoothDevice, val reason: String) : ConnectionState()
}

/**
 * Snapshot of a single connection attempt within a [com.ccubas.blueconnect.core.ConnectionStrategy].
 *
 * @param protocol Human readable protocol name (e.g. "BLE", "Classic", "Chipsea").
 * @param attemptNumber 1-based attempt index.
 * @param totalAttempts Number of protocols the active strategy will try.
 */
data class Attempt(
    val protocol: String,
    val attemptNumber: Int,
    val totalAttempts: Int,
)
