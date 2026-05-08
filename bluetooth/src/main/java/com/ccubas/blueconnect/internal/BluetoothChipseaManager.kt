package com.ccubas.blueconnect.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import androidx.core.util.isEmpty
import androidx.core.util.size
import com.ccubas.blueconnect.core.model.Attempt
import com.ccubas.blueconnect.core.model.WeightDataRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.bluetooth.BluetoothManager as AndroidBluetoothManager

/**
 * Chipsea / OKOK / many Chinese bioimpedance scales transport.
 *
 * These devices do NOT use a GATT connection — they broadcast measurements continuously
 * inside BLE advertisement packets. So "connecting" here means starting a filtered scan.
 *
 * Manufacturer IDs handled:
 * - 0x20CA (Chipsea v2.0) and 0x11CA (v1.1): magic byte `0xCA`, 15+ bytes payload.
 * - Any ID with low byte `0xC0` (e.g. 0x34C0, 0xD5C0): reverse-engineered "custom" variant.
 *
 * Layout (v2.0):
 * 0=0xCA, 1=version, 2=length, 3..6=productId, 7=scaleProperty, 8=measurementStatus,
 * 9=sequence, 10..11=weight (LE / 10), 12..13=impedance, 14=xor checksum.
 */
internal class BluetoothChipseaManager(private val context: Context) : BaseBluetoothManager() {

    override val TAG = "BluetoothChipseaManager"

    companion object {
        private const val MANUFACTURER_ID_CHIPSEA_V2 = 0x20CA
        private const val MANUFACTURER_ID_CHIPSEA_V1 = 0x11CA

        private const val MAGIC_BYTE = 0xCA.toByte()
        private const val VERSION_2_0 = 0x20.toByte()
        private const val VERSION_1_1 = 0x11.toByte()
    }

    private val androidBluetoothManager: AndroidBluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as AndroidBluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        androidBluetoothManager.adapter
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var scanJob: Job? = null
    private var currentScanCallback: ScanCallback? = null
    private var targetDeviceAddress: String? = null
    private var connectionTimeoutJob: Job? = null
    private var isConnected = false

    @SuppressLint("MissingPermission")
    override fun connect(
        device: BluetoothDevice,
        attempt: Attempt,
        listener: BluetoothEventListener,
    ): Boolean {
        setListener(listener)

        return try {
            Log.i(TAG, "╔═══════════════════════════════════════╗")
            Log.i(TAG, "║       TRYING CHIPSEA PROTOCOL         ║")
            Log.i(TAG, "╚═══════════════════════════════════════╝")
            Log.i(TAG, "Device: ${device.address}")
            Log.i(TAG, "Name: ${device.name ?: "Unknown"}")
            Log.i(TAG, "Method: Advertisement Packet Scanning")
            Log.i(TAG, "═══════════════════════════════════════")

            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                Log.e(TAG, "Bluetooth adapter not available or disabled")
                notifyConnectionFailed(device, "Bluetooth adapter not available or disabled", attempt)
                return false
            }

            resetDisconnectFlag()
            targetDeviceAddress = device.address

            startAdvertisementScan(device, attempt)

            connectionTimeoutJob = scope.launch {
                delay(30_000)
                if (!isConnected) {
                    Log.e(TAG, "╔═══════════════════════════════════════╗")
                    Log.e(TAG, "║       CHIPSEA PROTOCOL TIMEOUT        ║")
                    Log.e(TAG, "╚═══════════════════════════════════════╝")
                    Log.e(TAG, "No advertisement packets received in 30s")
                    Log.e(TAG, "Possible causes:")
                    Log.e(TAG, "  1. Scale is OFF or in sleep mode")
                    Log.e(TAG, "  2. Not stepping on the scale")
                    Log.e(TAG, "  3. Scale doesn't use Chipsea protocol")
                    Log.e(TAG, "  4. Scale not transmitting manufacturer data")
                    notifyConnectionFailed(
                        device,
                        "Connection timeout — no advertisement packets received",
                        attempt,
                    )
                    stopAdvertisementScan()
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Chipsea connection", e)
            notifyConnectionFailed(device, "Error starting Chipsea connection: ${e.message}", attempt)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisementScan(device: BluetoothDevice, attempt: Attempt) {
        val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BLE Scanner not available")
            notifyConnectionFailed(device, "BLE Scanner not available", attempt)
            return
        }

        currentScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { scanResult ->
                    Log.d(TAG, "Advertisement from: ${scanResult.device.address} (target: ${device.address})")

                    if (scanResult.device.address == device.address) {
                        Log.d(TAG, "  → Processing target device!")
                        processAdvertisementData(device, scanResult)
                    } else {
                        Log.d(TAG, "  → Ignoring (not target)")
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { scanResult ->
                    Log.d(TAG, "Batch advertisement from: ${scanResult.device.address} (target: ${device.address})")

                    if (scanResult.device.address == device.address) {
                        Log.d(TAG, "  → Processing target device!")
                        processAdvertisementData(device, scanResult)
                    } else {
                        Log.d(TAG, "  → Ignoring (not target)")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
                notifyConnectionFailed(device, "Scan failed with error code: $errorCode", attempt)
            }
        }

        try {
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bluetoothLeScanner.startScan(null, scanSettings, currentScanCallback)
            Log.d(TAG, "Advertisement scan started for ${device.address}")
            Log.d(TAG, "Waiting for advertisement packets... (timeout: 30s)")
            Log.d(TAG, "Make sure to STEP ON THE SCALE to activate it!")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting scan", e)
            notifyConnectionFailed(device, "SecurityException starting scan: ${e.message}", attempt)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan", e)
            notifyConnectionFailed(device, "Error starting scan: ${e.message}", attempt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisementScan() {
        try {
            currentScanCallback?.let { callback ->
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
                Log.d(TAG, "Advertisement scan stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
        currentScanCallback = null
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        Log.d(TAG, "Disconnecting")

        markUserDisconnect()
        isConnected = false

        scope.launch {
            scanJob?.cancelAndJoin()
            scanJob = null
        }

        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null

        stopAdvertisementScan()
        targetDeviceAddress?.let { address ->
            bluetoothAdapter?.getRemoteDevice(address)?.let {
                notifyDisconnected(it, "User initiated")
            }
        }
        targetDeviceAddress = null

        clearWeightData()
    }

    override fun close() {
        Log.d(TAG, "Closing (no events)")

        markUserDisconnect()
        isConnected = false

        scope.launch {
            scanJob?.cancelAndJoin()
            scanJob = null
        }

        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null

        stopAdvertisementScan()
        targetDeviceAddress = null
        clearWeightData()

        clearListener()
    }

    // ==================== DATA PROCESSING ====================

    @SuppressLint("MissingPermission")
    private fun processAdvertisementData(device: BluetoothDevice, scanResult: ScanResult) {
        try {
            val scanRecord = scanResult.scanRecord ?: return

            Log.d(TAG, "─────────────────────────────────────")
            Log.d(TAG, "Advertisement data received:")
            Log.d(TAG, "Device: ${scanResult.device.address}")
            Log.d(TAG, "RSSI: ${scanResult.rssi} dBm")
            Log.d(TAG, "Name: ${scanRecord.deviceName ?: "Unknown"}")

            val manufacturerData = scanRecord.manufacturerSpecificData

            if (manufacturerData.isEmpty()) {
                Log.d(TAG, "No manufacturer data found")
                return
            }

            for (i in 0 until manufacturerData.size) {
                val manufacturerId = manufacturerData.keyAt(i)
                val data = manufacturerData.get(manufacturerId)

                Log.d(TAG, "  Manufacturer ID: 0x${manufacturerId.toString(16).uppercase()}")
                Log.d(TAG, "  Data: ${data.joinToString(" ") { byte -> "%02X".format(byte) }}")

                when {
                    manufacturerId == MANUFACTURER_ID_CHIPSEA_V2 || manufacturerId == MANUFACTURER_ID_CHIPSEA_V1 -> {
                        Log.d(TAG, "Chipsea manufacturer ID detected")
                        emitChipseaRawData(device, data)
                    }

                    (manufacturerId and 0x00FF) == 0xC0 -> {
                        Log.d(
                            TAG,
                            "Custom Chipsea ID detected (0x${manufacturerId.toString(16).uppercase()}, low byte = 0xC0)",
                        )
                        emitCustomChipseaRawData(device, data)
                    }

                    data.isNotEmpty() && data[0] == MAGIC_BYTE -> {
                        Log.d(TAG, "Chipsea magic byte detected")
                        emitChipseaRawData(device, data)
                    }

                    else -> {
                        Log.d(TAG, "  Unknown manufacturer ID: 0x${manufacturerId.toString(16)}")
                    }
                }
            }
            Log.d(TAG, "─────────────────────────────────────")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing advertisement data", e)
        }
    }

    private fun emitChipseaRawData(device: BluetoothDevice, data: ByteArray) {
        try {
            if (data.isEmpty()) {
                Log.w(TAG, "Empty manufacturer data")
                return
            }

            if (data.size < 15) {
                Log.w(TAG, "Data too short: ${data.size} bytes (expected at least 15)")
                return
            }

            if (data[0] != MAGIC_BYTE) {
                Log.w(TAG, "Invalid magic byte: 0x${data[0].toString(16)} (expected 0xCA)")
                return
            }

            val version = data[1]
            if (version != VERSION_2_0 && version != VERSION_1_1) {
                Log.w(TAG, "Unknown version: 0x${version.toString(16)}")
            }

            Log.d(TAG, "═══════════════════════════════════════")
            Log.d(TAG, "Received Chipsea data (v${if (version == VERSION_2_0) "2.0" else "1.1"})")

            if (!isConnected) {
                isConnected = true
                notifyConnected(device)
                connectionTimeoutJob?.cancel()
                connectionTimeoutJob = null
                Log.d(TAG, "Connected (receiving Chipsea data)")
            }

            val rawHex = data.toHexString()
            Log.d(TAG, "Raw: $rawHex")
            Log.d(TAG, "═══════════════════════════════════════")

            val rawData = WeightDataRaw(
                data = rawHex,
                bytes = data,
            )

            notifyWeightData(rawData)
        } catch (e: Exception) {
            Log.e(TAG, "Error emitting Chipsea raw data", e)
        }
    }

    private fun emitCustomChipseaRawData(device: BluetoothDevice, data: ByteArray) {
        try {
            Log.i(TAG, "╔═══════════════════════════════════════╗")
            Log.i(TAG, "║   RECEIVED Chipsea/CUSTOM PROTOCOL    ║")
            Log.i(TAG, "╚═══════════════════════════════════════╝")

            if (data.size < 6) {
                Log.w(TAG, "Data too short: ${data.size} bytes (expected at least 6)")
                return
            }

            val rawHex = data.toHexString()
            Log.d(TAG, "Raw: $rawHex")

            if (!isConnected) {
                isConnected = true
                notifyConnected(device)
                connectionTimeoutJob?.cancel()
                connectionTimeoutJob = null
                Log.d(TAG, "Connected (receiving Custom Chipsea data)")
            }

            val rawData = WeightDataRaw(
                data = rawHex,
                bytes = data,
            )

            notifyWeightData(rawData)
        } catch (e: Exception) {
            Log.e(TAG, "Error emitting Custom Chipsea raw data", e)
        }
    }
}
