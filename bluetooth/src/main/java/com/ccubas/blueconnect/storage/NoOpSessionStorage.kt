package com.ccubas.blueconnect.storage

import com.ccubas.blueconnect.core.model.SavedDevice
import com.ccubas.blueconnect.core.storage.BluetoothSessionStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Throws away every session write. Use it when auto-reconnect is undesirable (e.g. you want
 * the user to pick a device every time the app launches).
 */
class NoOpSessionStorage : BluetoothSessionStorage {

    override val session: Flow<SavedDevice?> = flowOf(null)

    override suspend fun save(
        deviceAddress: String,
        deviceName: String?,
        successfulProtocol: String,
    ) {
        // intentionally blank
    }

    override suspend fun clear() {
        // intentionally blank
    }
}
