package com.ccubas.blueconnect.internal

import android.bluetooth.BluetoothDevice
import com.ccubas.blueconnect.core.model.Attempt

/**
 * Internal contract for transport-specific Bluetooth managers (BLE, Classic, Chipsea, Demo).
 *
 * Scope:
 * - Connect / disconnect / close
 * - Stream raw data via [BluetoothEventListener]
 *
 * Scanning lives in the coordinator (`BlueConnectClientImpl`); managers don't own a device list.
 *
 * Managers MUST NOT emit `ConnectionState.Connecting` themselves — the coordinator does that
 * synchronously before delegating, so UI sees one state per transition.
 */
internal interface IBluetoothManager {

    /**
     * Begin connecting to [device]. Returns immediately; success / failure arrives via [listener].
     *
     * @return true if the attempt was kicked off, false on synchronous failure (no events emitted).
     */
    fun connect(
        device: BluetoothDevice,
        attempt: Attempt,
        listener: BluetoothEventListener,
    ): Boolean

    /** Disconnect and notify the listener with `Disconnected`. */
    fun disconnect()

    /** Tear down without emitting events. Used by the coordinator when switching protocols. */
    fun close()
}
