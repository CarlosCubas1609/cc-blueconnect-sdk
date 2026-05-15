package com.ccubas.blueconnect.internal.scan

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.ccubas.blueconnect.core.model.ScanError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import android.bluetooth.BluetoothManager as AndroidBluetoothManager

/**
 * Seeds the scan with devices already paired with the phone. Cheap, synchronous, single-shot —
 * the flow emits all bonded devices and then completes.
 *
 * Without this, a paired Classic device that isn't currently broadcasting (e.g. the LP7516
 * sitting idle on a desk) wouldn't show up until the user re-pairs it.
 */
internal class BondedDevicesScanSource(private val context: Context) : IScanSource {

    override val name = "Bonded"

    @SuppressLint("MissingPermission")
    override fun scan(): Flow<ScanEvent> = flow {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager
        val adapter = mgr?.adapter ?: return@flow

        try {
            adapter.bondedDevices?.forEach { device ->
                val name = runCatching { device.name }.getOrNull()
                Log.d(TAG, "Bonded device: ${device.address} name='$name'")
                emit(ScanEvent.DeviceFound(device, rssi = 0, name = name))
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied accessing bonded devices", e)
            emit(ScanEvent.Error(ScanError.PermissionDenied("Bluetooth permissions are required")))
        }
    }

    private companion object {
        const val TAG = "BondedScanSource"
    }
}
