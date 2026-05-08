package com.ccubas.blueconnect.core

import android.bluetooth.BluetoothDevice
import com.ccubas.blueconnect.core.model.ConnectionState
import com.ccubas.blueconnect.core.model.DeviceInfo
import com.ccubas.blueconnect.core.model.SavedDevice
import com.ccubas.blueconnect.core.model.ScanError
import com.ccubas.blueconnect.core.model.WeightDataRaw
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public API of the BlueConnect SDK. Scan, connect, observe data, persist sessions.
 *
 * Obtain an instance via `com.ccubas.blueconnect.BlueConnect.create(context, storage)`
 * (in the `:bluetooth` module). Apps using Hilt can wrap that factory in a `@Module`.
 */
interface BlueConnectClient {

    // ==================== SCAN ====================

    /** True while a scan is running. */
    val isScanning: StateFlow<Boolean>

    /**
     * Errors raised by the scanning pipeline. Subscribe early — events emitted before
     * a collector is attached are still buffered (replay = 1).
     */
    val scanError: SharedFlow<ScanError>

    /** Devices seen during the current scan, keyed by MAC address. */
    val discoveredDevices: StateFlow<Map<String, DeviceInfo>>

    /** Start a scan that auto-stops after [durationMs]. */
    suspend fun startScan(durationMs: Long = 15_000L)

    /** Stop the active scan immediately. */
    fun stopScan()

    /** Drop all previously discovered devices. */
    fun clearDevices()

    // ==================== CONNECTION & DATA ====================

    /** Lifecycle of the active connection. */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Latest raw frame received from the connected device. The SDK does not parse —
     * use [com.ccubas.blueconnect.core.parser.WeightFrameParser] or roll your own.
     */
    val weightDataRaw: StateFlow<WeightDataRaw?>

    /**
     * Begin connecting to [device]. The wrapper picks a strategy based on device type
     * unless [forcedStrategy] is provided.
     *
     * @return true if the attempt was kicked off, false if it failed synchronously.
     */
    fun connect(device: BluetoothDevice, forcedStrategy: ConnectionStrategy? = null): Boolean

    /** Disconnect the currently-connected device. */
    fun disconnect()

    /** Forget the protocol history for [deviceAddress] so the next [connect] starts fresh. */
    fun resetDeviceConnectionHistory(deviceAddress: String)

    /** Toggle DEMO mode at runtime. Active connections are torn down on switch. */
    fun setDemoMode(enabled: Boolean)

    // ==================== SESSION PERSISTENCE ====================

    /**
     * Persist a successful connection so [reconnectToLastDevice] can restore it.
     *
     * The wrapper calls this automatically on a successful connect; expose it for callers that
     * want to seed the storage manually.
     */
    suspend fun saveSuccessfulConnection(
        deviceAddress: String,
        deviceName: String?,
        protocol: String,
    )

    /** Latest saved session, or null if none. */
    suspend fun getLastSession(): SavedDevice?

    /** Drop the saved session. */
    suspend fun clearSession()

    /**
     * Try to reconnect to the device stored in [com.ccubas.blueconnect.core.storage.BluetoothSessionStorage].
     *
     * @return true if the attempt was kicked off, false if no session is stored or the kick-off failed.
     */
    suspend fun reconnectToLastDevice(): Boolean
}
