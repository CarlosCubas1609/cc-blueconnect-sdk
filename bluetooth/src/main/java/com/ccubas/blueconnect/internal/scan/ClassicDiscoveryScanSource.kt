package com.ccubas.blueconnect.internal.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ccubas.blueconnect.core.model.ScanError
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import android.bluetooth.BluetoothManager as AndroidBluetoothManager

/**
 * Bluetooth Classic inquiry. Required to surface non-paired Classic-only devices like
 * weight indicators (LP7516), POS printers and old SPP peripherals — these don't advertise
 * over BLE, so [BleScanSource] never sees them.
 *
 * The platform inquiry runs ~12s and then emits `ACTION_DISCOVERY_FINISHED`. We restart it
 * automatically until the collector cancels, so callers can scan for any duration.
 *
 * Permissions: BLUETOOTH_SCAN on API 31+, BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION on older.
 * The coordinator checks these before starting any source.
 */
internal class ClassicDiscoveryScanSource(private val context: Context) : IScanSource {

    override val name = "Classic"

    @SuppressLint("MissingPermission")
    override fun scan(): Flow<ScanEvent> = callbackFlow {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager
        val adapter = mgr?.adapter

        if (adapter == null) {
            Log.w(TAG, "Bluetooth adapter unavailable")
            close()
            return@callbackFlow
        }

        val active = AtomicBoolean(true)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = extractDevice(intent)
                        val rssi = intent
                            .getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                            .toInt()
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                        device?.let {
                            Log.d(TAG, "Classic device found: ${it.address} name='$name' RSSI=$rssi")
                            trySend(ScanEvent.DeviceFound(it, rssi, name))
                        }
                    }

                    BluetoothDevice.ACTION_NAME_CHANGED -> {
                        val device = extractDevice(intent)
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                        if (device != null && !name.isNullOrBlank()) {
                            Log.d(TAG, "Classic name resolved: ${device.address} -> '$name'")
                            trySend(ScanEvent.DeviceNameResolved(device, name))
                        }
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (active.get()) {
                            Log.d(TAG, "Inquiry cycle finished; restarting")
                            startInquiry(adapter)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        startInquiry(adapter)?.let {
            trySend(ScanEvent.Error(it))
        }

        awaitClose {
            active.set(false)
            try {
                adapter.cancelDiscovery()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling discovery", e)
            }
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered — ignore.
            }
            Log.d(TAG, "Classic discovery stopped")
        }
    }

    /** Returns null on success, or a [ScanError] when the platform refused to start the inquiry. */
    @SuppressLint("MissingPermission")
    private fun startInquiry(adapter: BluetoothAdapter): ScanError? = try {
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        val started = adapter.startDiscovery()
        if (started) {
            Log.d(TAG, "Classic inquiry started")
            null
        } else {
            Log.w(TAG, "startDiscovery returned false")
            ScanError.ScanFailed("Failed to start Classic discovery")
        }
    } catch (e: SecurityException) {
        Log.e(TAG, "SecurityException starting Classic discovery", e)
        ScanError.PermissionDenied("Bluetooth permissions are required")
    } catch (e: Exception) {
        Log.e(TAG, "Error starting Classic discovery", e)
        ScanError.ScanFailed("Error starting Classic discovery: ${e.message}")
    }

    private fun extractDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val TAG = "ClassicScanSource"
    }
}
