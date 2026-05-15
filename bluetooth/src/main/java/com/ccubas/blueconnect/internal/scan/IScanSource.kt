package com.ccubas.blueconnect.internal.scan

import android.bluetooth.BluetoothDevice
import com.ccubas.blueconnect.core.model.ScanError
import kotlinx.coroutines.flow.Flow

/**
 * One transport-specific source of scan results (bonded devices, BLE advertisements,
 * Classic inquiry, demo, …).
 *
 * Sources are independent: each one knows how to start its own discovery, emit results
 * through a cold [Flow], and tear down in [kotlinx.coroutines.flow.callbackFlow]'s
 * `awaitClose` when the collector cancels. The coordinator merges them with `flatMapMerge`
 * so adding a new transport is just dropping a new implementation into the factory.
 */
internal interface IScanSource {

    /** Stable identifier — used for logging and error attribution. */
    val name: String

    /**
     * Cold flow. Collecting begins the scan; cancelling the collecting coroutine stops it
     * (each implementation cleans up in `awaitClose`).
     */
    fun scan(): Flow<ScanEvent>
}

/** Result of one scan source emitting through its [IScanSource.scan] flow. */
internal sealed interface ScanEvent {

    /**
     * A device was discovered (or re-discovered with a fresher RSSI).
     *
     * @param name Friendly name from the advertisement payload, when carried. Sources should
     *             populate this from the broadcast/scan-record (not from `device.name`, which
     *             is cache-dependent and frequently null at first-sight).
     */
    data class DeviceFound(
        val device: BluetoothDevice,
        val rssi: Int,
        val name: String? = null,
    ) : ScanEvent

    /**
     * A device's name was resolved after first discovery. Emitted by sources that observe
     * late name resolution (e.g. Classic `ACTION_NAME_CHANGED` after SDP completes).
     *
     * The coordinator merges this into the existing entry without touching RSSI.
     */
    data class DeviceNameResolved(val device: BluetoothDevice, val name: String) : ScanEvent

    /** The source hit a non-fatal error. Other sources keep running. */
    data class Error(val error: ScanError) : ScanEvent
}
