package com.ccubas.blueconnect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.ccubas.blueconnect.core.BlueConnectClient
import com.ccubas.blueconnect.core.ConnectionStrategy
import com.ccubas.blueconnect.core.ManagerType
import com.ccubas.blueconnect.core.model.Attempt
import com.ccubas.blueconnect.core.model.ConnectionState
import com.ccubas.blueconnect.core.model.DeviceInfo
import com.ccubas.blueconnect.core.model.SavedDevice
import com.ccubas.blueconnect.core.model.ScanError
import com.ccubas.blueconnect.core.model.WeightDataRaw
import com.ccubas.blueconnect.core.storage.BluetoothSessionStorage
import com.ccubas.blueconnect.internal.BluetoothEventListener
import com.ccubas.blueconnect.internal.IBluetoothManager
import com.ccubas.blueconnect.internal.IBluetoothManagerFactory
import com.ccubas.blueconnect.permission.BluetoothPermissionUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import android.bluetooth.BluetoothManager as AndroidBluetoothManager

/**
 * Default [BlueConnectClient] implementation. The "wrapper / coordinator" in the architecture:
 *
 * - Owns the single source of truth for scan results and connection state (StateFlows).
 * - Delegates scanning to the platform BLE scanner (or to the Demo path when in demo mode).
 * - Picks a [ConnectionStrategy] based on device type, then routes connect/disconnect to the
 *   matching transport manager via [IBluetoothManagerFactory]. Falls back across protocols
 *   when a strategy lists more than one.
 * - Persists successful connections through the [BluetoothSessionStorage] dependency, so apps
 *   can restore state without coupling to a specific persistence library.
 *
 * Use the [com.ccubas.blueconnect.BlueConnect] factory to obtain instances; constructor is
 * internal because it takes internal types.
 */
internal class BlueConnectClientImpl internal constructor(
    private val context: Context,
    private val managerFactory: IBluetoothManagerFactory,
    private val sessionStorage: BluetoothSessionStorage,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BlueConnectClient, BluetoothEventListener {

    companion object {
        private const val TAG = "BlueConnectClient"
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    /** When true, use the demo manager. Off by default. */
    private var isDemoMode: Boolean = false

    // ==================== DEVICE LIST (single source of truth) ====================

    private val _discoveredDevices = MutableStateFlow<Map<String, DeviceInfo>>(emptyMap())
    override val discoveredDevices: StateFlow<Map<String, DeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanError = MutableSharedFlow<ScanError>(replay = 1)
    override val scanError: SharedFlow<ScanError> = _scanError.asSharedFlow()

    private var scanJob: Job? = null
    private var currentScanCallback: ScanCallback? = null
    private var currentBluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner? = null

    // ==================== SCAN ====================

    @SuppressLint("MissingPermission")
    override suspend fun startScan(durationMs: Long) {
        if (_isScanning.value) {
            Log.d(TAG, "Stopping previous scan before starting new one")
            stopScan()
        }

        _isScanning.value = true
        clearDevices()

        if (isDemoMode) {
            startDemoScan(durationMs)
        } else {
            startRealScan(durationMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDemoScan(durationMs: Long) {
        scanJob = scope.launch {
            try {
                val androidBluetoothManager =
                    context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager
                val bluetoothAdapter = androidBluetoothManager?.adapter

                if (bluetoothAdapter == null) {
                    Log.e(TAG, "Cannot get BluetoothAdapter for DEMO mode")
                    _isScanning.value = false
                    _scanError.emit(ScanError.AdapterNotAvailable("Bluetooth is not available on this device"))
                    return@launch
                }

                val demoDevices = listOf(
                    Triple("Demo Scale 1", "AA:BB:CC:DD:EE:01", -45),
                    Triple("Demo Scale 2", "AA:BB:CC:DD:EE:02", -52),
                    Triple("Demo Scale 3", "AA:BB:CC:DD:EE:03", -38),
                    Triple("Demo Industrial Scale", "AA:BB:CC:DD:EE:04", -68),
                    Triple("Demo BT Scale", "AA:BB:CC:DD:EE:05", -75),
                    Triple("Demo Test Scale", "AA:BB:CC:DD:EE:06", -60),
                )

                val delayBetweenDevices = (durationMs / demoDevices.size).coerceAtLeast(100L)

                demoDevices.forEachIndexed { _, (name, address, rssi) ->
                    if (!_isScanning.value) return@launch

                    delay(delayBetweenDevices)

                    try {
                        val device = try {
                            bluetoothAdapter.bondedDevices?.find { it.address == address }
                        } catch (_: SecurityException) {
                            Log.w(TAG, "Permission not granted for bonded devices, creating remote device")
                            null
                        } ?: bluetoothAdapter.getRemoteDevice(address)

                        addDevice(device, rssi)
                        Log.d(TAG, "DEMO device added: $name ($address)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating DEMO device: ${e.message}")
                    }
                }

                Log.d(TAG, "DEMO scan completed — ${_discoveredDevices.value.size} devices found")
            } catch (e: Exception) {
                Log.e(TAG, "Error during DEMO scan: ${e.message}", e)
            } finally {
                _isScanning.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRealScan(durationMs: Long) {
        if (!BluetoothPermissionUtils.hasBluetoothPermissions(context)) {
            Log.e(TAG, "Bluetooth permissions not granted")
            _isScanning.value = false
            scope.launch {
                _scanError.emit(ScanError.PermissionDenied("Bluetooth permissions are required"))
            }
            return
        }

        val androidBluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager
        val bluetoothAdapter = androidBluetoothManager?.adapter
        val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter not available")
            _isScanning.value = false
            scope.launch {
                _scanError.emit(ScanError.AdapterNotAvailable("Bluetooth is not available on this device"))
            }
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            _isScanning.value = false
            scope.launch {
                _scanError.emit(ScanError.BluetoothDisabled("Please turn on Bluetooth to scan for devices"))
            }
            return
        }

        try {
            bluetoothAdapter.bondedDevices?.forEach { device ->
                addDevice(device, rssi = 0)
                Log.d(TAG, "Bonded device added: ${device.address}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission not granted to access bonded devices: ${e.message}")
            _isScanning.value = false
            scope.launch {
                _scanError.emit(ScanError.PermissionDenied("Bluetooth permissions are required"))
            }
            return
        }

        if (bluetoothLeScanner != null) {
            currentBluetoothLeScanner = bluetoothLeScanner
            currentScanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.let { scanResult ->
                        addDevice(scanResult.device, scanResult.rssi)
                    }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.forEach { scanResult ->
                        addDevice(scanResult.device, scanResult.rssi)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Scan failed with error code: $errorCode")
                    _isScanning.value = false
                    scope.launch {
                        _scanError.emit(ScanError.ScanFailed("Failed to scan for devices", errorCode))
                    }
                }
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                bluetoothLeScanner.startScan(null, scanSettings, currentScanCallback)
                Log.d(TAG, "BLE scan started")

                scanJob = scope.launch {
                    delay(durationMs)
                    try {
                        currentBluetoothLeScanner?.stopScan(currentScanCallback)
                        Log.d(TAG, "BLE scan stopped")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping scan", e)
                    } finally {
                        _isScanning.value = false
                        currentScanCallback = null
                        currentBluetoothLeScanner = null
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: missing Bluetooth permissions", e)
                _isScanning.value = false
                currentScanCallback = null
                currentBluetoothLeScanner = null
                scope.launch {
                    _scanError.emit(ScanError.PermissionDenied("Bluetooth permissions are required"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting scan", e)
                _isScanning.value = false
                currentScanCallback = null
                currentBluetoothLeScanner = null
            }
        } else {
            Log.w(TAG, "BLE scanner not available")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        try {
            currentScanCallback?.let { callback ->
                currentBluetoothLeScanner?.stopScan(callback)
                Log.d(TAG, "BLE scan stopped manually")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scan", e)
        }

        scanJob?.cancel()
        scanJob = null

        currentScanCallback = null
        currentBluetoothLeScanner = null

        _isScanning.value = false
    }

    override fun clearDevices() {
        _discoveredDevices.value = emptyMap()
    }

    @SuppressLint("MissingPermission")
    private fun addDevice(device: BluetoothDevice, rssi: Int) {
        val currentDevices = _discoveredDevices.value.toMutableMap()
        currentDevices[device.address] = DeviceInfo(device = device, rssi = rssi)
        _discoveredDevices.value = currentDevices
        Log.d(TAG, "Device discovered: ${device.address} | Name: '${device.name}' | Type: ${device.type} | RSSI: $rssi")
    }

    // ==================== CONNECTION (delegated dynamically) ====================

    private val managerInstances = mutableMapOf<ManagerType, IBluetoothManager>()

    private fun getManagerInstance(type: ManagerType): IBluetoothManager =
        managerInstances.getOrPut(type) {
            when (type) {
                is ManagerType.BLE -> managerFactory.createGattManager()
                is ManagerType.Classic -> managerFactory.createClassicManager()
                is ManagerType.Demo -> managerFactory.createDemoManager()
                is ManagerType.Chipsea -> managerFactory.createChipseaManager()
            }
        }

    private data class DeviceConnectionState(
        val strategy: ConnectionStrategy,
        val lastManagerType: ManagerType? = null,
        val attemptCount: Int = 0,
    )

    private val deviceConnectionStates = mutableMapOf<String, DeviceConnectionState>()

    private var currentManagerType: ManagerType? = null
    private var currentDeviceAddress: String? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _weightDataRaw = MutableStateFlow<WeightDataRaw?>(null)
    override val weightDataRaw: StateFlow<WeightDataRaw?> = _weightDataRaw.asStateFlow()

    @SuppressLint("MissingPermission")
    private fun getConnectionStrategy(device: BluetoothDevice): ConnectionStrategy = when {
        isDemoMode -> {
            Log.d(TAG, "→ DEMO mode enabled")
            ConnectionStrategy.DemoOnly
        }

        isLikelyChipseaDevice(device) -> {
            Log.d(TAG, "→ Device name matches Chipsea pattern")
            ConnectionStrategy.ChipseaOnly
        }

        device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC -> {
            Log.d(TAG, "→ Device is CLASSIC type")
            ConnectionStrategy.ClassicOnly
        }

        device.type == BluetoothDevice.DEVICE_TYPE_LE ||
            device.type == BluetoothDevice.DEVICE_TYPE_DUAL -> {
            Log.d(TAG, "→ Device is BLE/DUAL type")
            ConnectionStrategy.BleFirst
        }

        else -> {
            if (device.type == BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
                Log.d(TAG, "→ Device is UNKNOWN type, using ChipseaFirst")
            } else {
                Log.w(TAG, "→ Unexpected device type: ${device.type}, using ChipseaFirst")
            }
            ConnectionStrategy.ChipseaFirst
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(device: BluetoothDevice, forcedStrategy: ConnectionStrategy?): Boolean {
        Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║       CONNECT REQUEST                                      ║")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")
        Log.d(TAG, "Device: ${device.address} | Name: '${device.name}' | Type: ${device.type}")

        var currentState = deviceConnectionStates[device.address]
        if ((currentState?.attemptCount ?: 0) >= (currentState?.strategy?.protocols?.size ?: 0)) {
            currentState = null
        }

        val strategy = if (forcedStrategy != null) {
            Log.d(TAG, "Using FORCED strategy: ${forcedStrategy.description}")
            deviceConnectionStates.remove(device.address)
            forcedStrategy
        } else {
            currentState?.strategy ?: getConnectionStrategy(device)
        }

        currentState = deviceConnectionStates[device.address]

        Log.d(TAG, "────────────────────────────────────────────────────────────")
        Log.d(TAG, "Connection Strategy: ${strategy.description}")
        Log.d(TAG, "Last Manager Type: ${currentState?.lastManagerType}")
        Log.d(TAG, "────────────────────────────────────────────────────────────")

        val nextManagerType = strategy.getNextProtocol(currentState?.lastManagerType)
        val protocolName = strategy.getProtocolName(nextManagerType)

        val newAttemptCount = (currentState?.attemptCount ?: 0) + 1

        Log.d(TAG, "Next Protocol: $protocolName (Attempt #$newAttemptCount)")
        Log.d(TAG, "════════════════════════════════════════════════════════════")

        if (currentManagerType != null && currentManagerType != nextManagerType) {
            Log.d(TAG, "Closing previous manager: $currentManagerType")
            getManagerInstance(currentManagerType!!).close()
        }

        currentManagerType = nextManagerType
        currentDeviceAddress = device.address

        deviceConnectionStates[device.address] = DeviceConnectionState(
            strategy = strategy,
            lastManagerType = currentManagerType,
            attemptCount = newAttemptCount,
        )

        val manager = getManagerInstance(currentManagerType!!)

        val attempt = Attempt(
            protocol = protocolName,
            attemptNumber = newAttemptCount,
            totalAttempts = strategy.protocols.size,
        )
        _connectionState.value = ConnectionState.Connecting(device, attempt)

        return manager.connect(
            device = device,
            attempt = attempt,
            listener = this,
        )
    }

    override fun disconnect() {
        val currentState = _connectionState.value
        _connectionState.value = when (currentState) {
            is ConnectionState.Connected -> ConnectionState.Disconnected(currentState.device, "User initiated")
            else -> ConnectionState.Idle
        }
        _weightDataRaw.value = null

        currentDeviceAddress?.let { address ->
            deviceConnectionStates[address] = deviceConnectionStates[address]?.copy(
                attemptCount = 0,
                lastManagerType = null,
            ) ?: return@let
        }

        currentManagerType?.let { getManagerInstance(it).disconnect() }
        currentManagerType = null
        currentDeviceAddress = null
    }

    override fun resetDeviceConnectionHistory(deviceAddress: String) {
        deviceConnectionStates.remove(deviceAddress)
    }

    override fun setDemoMode(enabled: Boolean) {
        if (isDemoMode == enabled) return

        isDemoMode = enabled
        Log.d(TAG, "Demo mode changed to: $enabled")
        cleanupAllConnections()
    }

    private fun cleanupAllConnections() {
        clearDevices()
        deviceConnectionStates.clear()
        currentManagerType?.let { getManagerInstance(it).disconnect() }
        currentManagerType = null
        currentDeviceAddress = null
    }

    @SuppressLint("MissingPermission")
    private fun isLikelyChipseaDevice(device: BluetoothDevice): Boolean {
        val deviceName = device.name

        Log.d(TAG, "Checking if device is Chipsea: ${device.address} | Name: '$deviceName'")

        if (deviceName.isNullOrBlank()) {
            Log.d(TAG, "  → No name, not Chipsea")
            return false
        }

        val chipseaNames = listOf(
            "ADV",
            "Chipsea-BLE",
            "Chipsea",
            "OKOK",
            "BodyFat",
            "Body Scale",
            "Smart Scale",
            "JS02",
        )

        val isChipsea = chipseaNames.any { chipseaName ->
            deviceName.contains(chipseaName, ignoreCase = true)
        }

        Log.d(TAG, "  → ${if (isChipsea) "yes" else "no"}")
        return isChipsea
    }

    // ==================== BluetoothEventListener ====================

    @SuppressLint("MissingPermission")
    override fun onStateChange(state: ConnectionState) {
        Log.d(TAG, "Received state from manager: $state")
        _connectionState.value = state

        if (state is ConnectionState.Connected) {
            scope.launch {
                currentDeviceAddress?.let { address ->
                    val deviceState = deviceConnectionStates[address]
                    val strategy = deviceState?.strategy

                    currentManagerType?.let { managerType ->
                        val protocolToSave = when (strategy) {
                            is ConnectionStrategy.BleFirst -> "BleFirst"
                            is ConnectionStrategy.ChipseaFirst -> "ChipseaFirst"
                            else -> when (managerType) {
                                is ManagerType.BLE -> "BLE"
                                is ManagerType.Classic -> "Classic"
                                is ManagerType.Chipsea -> "Chipsea"
                                is ManagerType.Demo -> "Demo"
                            }
                        }

                        Log.d(TAG, "Saving session — Protocol/Strategy: $protocolToSave")
                        saveSuccessfulConnection(
                            deviceAddress = state.device.address,
                            deviceName = state.device.name,
                            protocol = protocolToSave,
                        )
                    }
                }
            }

            currentDeviceAddress?.let { address ->
                deviceConnectionStates[address]?.let { deviceState ->
                    deviceConnectionStates[address] = deviceState.copy(attemptCount = 0)
                }
            }
        }
    }

    override fun onWeightData(raw: WeightDataRaw?) {
        _weightDataRaw.value = raw
    }

    // ==================== SESSION ====================

    override suspend fun saveSuccessfulConnection(
        deviceAddress: String,
        deviceName: String?,
        protocol: String,
    ) {
        sessionStorage.save(deviceAddress, deviceName, protocol)
        Log.d(TAG, "Session saved: $deviceAddress ($deviceName) — Protocol: $protocol")
    }

    override suspend fun getLastSession(): SavedDevice? = try {
        sessionStorage.session.firstOrNull()
    } catch (e: Exception) {
        Log.e(TAG, "Error getting last session: ${e.message}")
        null
    }

    override suspend fun clearSession() {
        sessionStorage.clear()
        Log.d(TAG, "Session cleared")
    }

    @SuppressLint("MissingPermission")
    override suspend fun reconnectToLastDevice(): Boolean {
        if (!BluetoothPermissionUtils.hasBluetoothPermissions(context)) {
            Log.w(TAG, "Cannot reconnect: Bluetooth permissions not granted")
            scope.launch {
                _scanError.emit(ScanError.PermissionDenied("Bluetooth permissions are required"))
            }
            return false
        }

        val androidBluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager
        val bluetoothAdapter = androidBluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Cannot reconnect: Bluetooth adapter not available")
            return false
        }

        val lastSession = getLastSession()

        if (lastSession == null) {
            Log.d(TAG, "No saved session for auto-reconnect")
            return false
        }

        Log.d(TAG, "Attempting auto-reconnection to: ${lastSession.deviceName} (${lastSession.deviceAddress})")
        Log.d(TAG, "Using saved protocol: ${lastSession.successfulProtocol}")

        return try {
            val device = bluetoothAdapter.getRemoteDevice(lastSession.deviceAddress)
            val strategy = ConnectionStrategy.fromProtocolString(lastSession.successfulProtocol)

            if (strategy != null) {
                Log.d(TAG, "Reconnecting with strategy: ${strategy.description}")
                connect(device, strategy)
            } else {
                Log.w(TAG, "Unknown protocol '${lastSession.successfulProtocol}', using auto-detection")
                connect(device)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconnect to saved device: ${e.message}")
            false
        }
    }
}
