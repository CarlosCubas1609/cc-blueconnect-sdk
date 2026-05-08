package com.ccubas.blueconnect.core.storage

import com.ccubas.blueconnect.core.model.SavedDevice
import kotlinx.coroutines.flow.Flow

/**
 * Storage contract for the last successfully-connected device.
 *
 * The SDK ships with two in-memory implementations and an optional Preferences DataStore
 * adapter in the `:storage-datastore` module. Apps that already have their own persistence
 * (Room, custom DataStore proto, …) can implement this interface directly.
 */
interface BluetoothSessionStorage {

    /** Latest saved device, or null when nothing is saved. Hot stream. */
    val session: Flow<SavedDevice?>

    /**
     * Persist a successful connection.
     *
     * @param deviceAddress MAC address.
     * @param deviceName Friendly name (may be null).
     * @param successfulProtocol Either a single-protocol name ("BLE", "Classic", "Chipsea",
     *                           "Demo") or a multi-protocol strategy ("BleFirst",
     *                           "ChipseaFirst").
     */
    suspend fun save(
        deviceAddress: String,
        deviceName: String?,
        successfulProtocol: String,
    )

    /** Clear any saved session. */
    suspend fun clear()
}
