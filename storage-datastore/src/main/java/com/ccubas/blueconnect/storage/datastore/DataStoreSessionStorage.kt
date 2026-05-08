package com.ccubas.blueconnect.storage.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ccubas.blueconnect.core.model.SavedDevice
import com.ccubas.blueconnect.core.storage.BluetoothSessionStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Persistent [BluetoothSessionStorage] backed by Preferences DataStore.
 *
 * Stores four keys (address, name, protocol, timestamp) in a private DataStore file
 * named `cc_blueconnect_session.preferences_pb`. No protobuf schema, no extra setup —
 * just construct it with the application context.
 *
 * If your app needs more than the latest session (e.g. a paired-devices history) implement
 * [BluetoothSessionStorage] directly with whatever store fits.
 */
class DataStoreSessionStorage(context: Context) : BluetoothSessionStorage {

    private val dataStore: DataStore<Preferences> = context.applicationContext.sessionDataStore

    override val session: Flow<SavedDevice?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs -> prefs.toSavedDevice() }

    override suspend fun save(
        deviceAddress: String,
        deviceName: String?,
        successfulProtocol: String,
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.ADDRESS] = deviceAddress
            if (deviceName != null) {
                prefs[Keys.NAME] = deviceName
            } else {
                prefs.remove(Keys.NAME)
            }
            prefs[Keys.PROTOCOL] = successfulProtocol
            prefs[Keys.TIMESTAMP] = System.currentTimeMillis()
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.ADDRESS)
            prefs.remove(Keys.NAME)
            prefs.remove(Keys.PROTOCOL)
            prefs.remove(Keys.TIMESTAMP)
        }
    }

    private fun Preferences.toSavedDevice(): SavedDevice? {
        val address = this[Keys.ADDRESS] ?: return null
        val protocol = this[Keys.PROTOCOL] ?: return null
        val timestamp = this[Keys.TIMESTAMP] ?: return null
        return SavedDevice(
            deviceAddress = address,
            deviceName = this[Keys.NAME],
            successfulProtocol = protocol,
            lastConnectionTimestamp = timestamp,
        )
    }

    private object Keys {
        val ADDRESS = stringPreferencesKey("device_address")
        val NAME = stringPreferencesKey("device_name")
        val PROTOCOL = stringPreferencesKey("protocol")
        val TIMESTAMP = longPreferencesKey("timestamp")
    }
}

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cc_blueconnect_session"
)
