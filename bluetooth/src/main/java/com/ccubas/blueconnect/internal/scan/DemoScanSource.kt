package com.ccubas.blueconnect.internal.scan

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.ccubas.blueconnect.core.model.ScanError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import android.bluetooth.BluetoothManager as AndroidBluetoothManager

/**
 * Synthetic scan for demos and offline development. Emits a fixed set of fake-address devices
 * spread across [durationMs] so the device-list UI animates as it would with real hardware.
 *
 * Real [android.bluetooth.BluetoothDevice]s are minted via `BluetoothAdapter.getRemoteDevice`
 * so the rest of the SDK — which holds onto `BluetoothDevice` references — works unchanged.
 */
internal class DemoScanSource(
    private val context: Context,
    private val durationMs: Long,
) : IScanSource {

    override val name = "Demo"

    @SuppressLint("MissingPermission")
    override fun scan(): Flow<ScanEvent> = flow {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager
        val adapter = mgr?.adapter

        if (adapter == null) {
            emit(ScanEvent.Error(ScanError.AdapterNotAvailable("Bluetooth is not available on this device")))
            return@flow
        }

        val delayBetween = (durationMs / DEMO_DEVICES.size).coerceAtLeast(100L)

        DEMO_DEVICES.forEach { (name, address, rssi) ->
            delay(delayBetween)
            try {
                val device = adapter.getRemoteDevice(address)
                Log.d(TAG, "DEMO device emitted: $name ($address)")
                emit(ScanEvent.DeviceFound(device, rssi, name))
            } catch (e: Exception) {
                Log.e(TAG, "Error creating DEMO device $address: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "DemoScanSource"

        val DEMO_DEVICES = listOf(
            Triple("Demo Scale 1", "AA:BB:CC:DD:EE:01", -45),
            Triple("Demo Scale 2", "AA:BB:CC:DD:EE:02", -52),
            Triple("Demo Scale 3", "AA:BB:CC:DD:EE:03", -38),
            Triple("Demo Industrial Scale", "AA:BB:CC:DD:EE:04", -68),
            Triple("Demo BT Scale", "AA:BB:CC:DD:EE:05", -75),
            Triple("Demo Test Scale", "AA:BB:CC:DD:EE:06", -60),
        )
    }
}
