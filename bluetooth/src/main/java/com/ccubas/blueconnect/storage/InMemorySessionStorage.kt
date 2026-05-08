package com.ccubas.blueconnect.storage

import com.ccubas.blueconnect.core.model.SavedDevice
import com.ccubas.blueconnect.core.storage.BluetoothSessionStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default storage that keeps the last session in memory only. Survives the lifetime of the
 * process — handy for tests and quick spikes, but loses state when the app is killed.
 *
 * Wire up [com.ccubas.blueconnect.storage.datastore.DataStoreSessionStorage] (in the
 * `:storage-datastore` module) or your own implementation if you need persistence.
 */
class InMemorySessionStorage : BluetoothSessionStorage {

    private val _session = MutableStateFlow<SavedDevice?>(null)
    override val session: Flow<SavedDevice?> = _session.asStateFlow()

    override suspend fun save(
        deviceAddress: String,
        deviceName: String?,
        successfulProtocol: String,
    ) {
        _session.value = SavedDevice(
            deviceAddress = deviceAddress,
            deviceName = deviceName,
            successfulProtocol = successfulProtocol,
            lastConnectionTimestamp = System.currentTimeMillis(),
        )
    }

    override suspend fun clear() {
        _session.value = null
    }
}
