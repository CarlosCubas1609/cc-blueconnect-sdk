package com.ccubas.blueconnect.core.model

/**
 * Persisted record of a previously successful connection. Allows auto-reconnect.
 *
 * @param deviceAddress MAC address ("AA:BB:CC:DD:EE:FF").
 * @param deviceName Friendly name, may be null when the device does not advertise one.
 * @param successfulProtocol Protocol that worked. Either a single-protocol value
 *                           ("BLE", "Classic", "Chipsea", "Demo") or a multi-protocol
 *                           strategy name ("BleFirst", "ChipseaFirst").
 * @param lastConnectionTimestamp Epoch millis of the last successful connect.
 */
data class SavedDevice(
    val deviceAddress: String,
    val deviceName: String?,
    val successfulProtocol: String,
    val lastConnectionTimestamp: Long,
)
