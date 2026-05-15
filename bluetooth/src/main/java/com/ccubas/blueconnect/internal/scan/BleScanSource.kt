package com.ccubas.blueconnect.internal.scan

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.ccubas.blueconnect.core.model.ScanError
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import android.bluetooth.BluetoothManager as AndroidBluetoothManager

/**
 * BLE advertisement scan via [android.bluetooth.le.BluetoothLeScanner].
 *
 * Picks up anything advertising — Eddystone, iBeacon, weight scales speaking GATT,
 * Chipsea-style broadcasters. Classic-only devices (SPP/RFCOMM) are invisible here;
 * pair them up with [ClassicDiscoveryScanSource].
 */
internal class BleScanSource(private val context: Context) : IScanSource {

    override val name = "BLE"

    @SuppressLint("MissingPermission")
    override fun scan(): Flow<ScanEvent> = callbackFlow {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager
        val adapter = mgr?.adapter
        val leScanner = adapter?.bluetoothLeScanner

        if (leScanner == null) {
            Log.w(TAG, "BLE scanner unavailable")
            trySend(ScanEvent.Error(ScanError.ScanFailed("BLE scanner unavailable")))
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { trySend(ScanEvent.DeviceFound(it.device, it.rssi, nameOf(it))) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { trySend(ScanEvent.DeviceFound(it.device, it.rssi, nameOf(it))) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with code: $errorCode")
                trySend(ScanEvent.Error(ScanError.ScanFailed("Failed to scan for devices", errorCode)))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            leScanner.startScan(null, settings, callback)
            Log.d(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting BLE scan", e)
            trySend(ScanEvent.Error(ScanError.PermissionDenied("Bluetooth permissions are required")))
            close()
            return@callbackFlow
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan", e)
            trySend(ScanEvent.Error(ScanError.ScanFailed("Error starting BLE scan: ${e.message}")))
            close()
            return@callbackFlow
        }

        awaitClose {
            try {
                leScanner.stopScan(callback)
                Log.d(TAG, "BLE scan stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping BLE scan", e)
            }
        }
    }

    /**
     * Prefer the name carried inside the advertisement payload (no extra permissions, no
     * cache dependency). Fall back to the platform cache via `device.name` only if the AD
     * didn't include the LocalName element.
     */
    @SuppressLint("MissingPermission")
    private fun nameOf(result: ScanResult): String? =
        result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
            ?: runCatching { result.device.name }.getOrNull()

    private companion object {
        const val TAG = "BleScanSource"
    }
}
