package com.ccubas.blueconnect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
import com.ccubas.blueconnect.core.model.BluetoothFrame
import com.ccubas.blueconnect.core.storage.BluetoothSessionStorage
import com.ccubas.blueconnect.internal.BluetoothEventListener
import com.ccubas.blueconnect.internal.IBluetoothManager
import com.ccubas.blueconnect.internal.IBluetoothManagerFactory
import com.ccubas.blueconnect.internal.scan.IScanSource
import com.ccubas.blueconnect.internal.scan.IScannerFactory
import com.ccubas.blueconnect.internal.scan.ScanEvent
import com.ccubas.blueconnect.permission.BluetoothPermissionUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.bluetooth.BluetoothManager as AndroidBluetoothManager

/**
 * Default [BlueConnectClient] implementation. The "wrapper / coordinator" in the architecture:
 *
 * - Owns the single source of truth for scan results and connection state (StateFlows).
 * - Delegates scanning to a list of [com.ccubas.blueconnect.internal.scan.IScanSource]s
 *   produced by [IScannerFactory] (bonded + BLE + Classic in real mode, Demo in demo mode)
 *   and merges their flows. Adding a transport is just adding a source — the coordinator
 *   stays unchanged.
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
    private val scannerFactory: IScannerFactory,
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

    // ==================== SCAN ====================
    //
    // Discovery is fanned out to a list of `IScanSource`s (bonded / BLE / Classic / Demo)
    // built by the injected `IScannerFactory`. The coordinator only:
    //   1. Runs preflight (permissions + adapter checks) once.
    //   2. Merges every source's flow with `flatMapMerge`.
    //   3. Stops the merged collection after `durationMs` — cancellation propagates to each
    //      source's `awaitClose`, so cleanup is structural.
    // Adding a new transport means adding a new `IScanSource`; the coordinator stays the same.

    @SuppressLint("MissingPermission")
    override suspend fun startScan(durationMs: Long) {
        if (_isScanning.value) {
            Log.d(TAG, "Stopping previous scan before starting new one")
            stopScan()
        }

        if (!preflight()) return

        _isScanning.value = true
        clearDevices()

        val sources = scannerFactory.createScanSources(isDemoMode, durationMs)
        Log.d(TAG, "Starting scan with sources: ${sources.joinToString { it.name }}")

        scanJob = scope.launch {
            val collectJob = launch { collectScanSources(sources) }
            try {
                delay(durationMs)
            } finally {
                collectJob.cancel()
                _isScanning.value = false
                Log.d(TAG, "Scan window finished")
            }
        }
    }

    /** Returns true if the scan is allowed to proceed; emits a `ScanError` otherwise. */
    private suspend fun preflight(): Boolean {
        if (isDemoMode) return true

        if (!BluetoothPermissionUtils.hasBluetoothPermissions(context)) {
            Log.e(TAG, "Bluetooth permissions not granted")
            _scanError.emit(ScanError.PermissionDenied("Bluetooth permissions are required"))
            return false
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager)?.adapter
        if (adapter == null) {
            Log.e(TAG, "Bluetooth adapter not available")
            _scanError.emit(ScanError.AdapterNotAvailable("Bluetooth is not available on this device"))
            return false
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            _scanError.emit(ScanError.BluetoothDisabled("Please turn on Bluetooth to scan for devices"))
            return false
        }
        return true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectScanSources(sources: List<IScanSource>) {
        sources.asFlow()
            .flatMapMerge { source -> source.scan() }
            .collect { event ->
                when (event) {
                    is ScanEvent.DeviceFound -> addDevice(event.device, event.rssi, event.name)
                    is ScanEvent.DeviceNameResolved -> updateDeviceName(event.device, event.name)
                    is ScanEvent.Error -> _scanError.emit(event.error)
                }
            }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
        Log.d(TAG, "Scan stopped by user")
    }

    override fun clearDevices() {
        _discoveredDevices.value = emptyMap()
    }

    @SuppressLint("MissingPermission")
    private fun addDevice(device: BluetoothDevice, rssi: Int, name: String?) {
        _discoveredDevices.update { current ->
            val existing = current[device.address]
            val resolvedName = name?.takeIf { it.isNotBlank() } ?: existing?.resolvedName
            current + (device.address to DeviceInfo(device, rssi, resolvedName))
        }
        Log.d(TAG, "Device discovered: ${device.address} | Name: '$name' | Type: ${device.type} | RSSI: $rssi")
    }

    /**
     * Late name resolution from a source (e.g. Classic SDP completing after `ACTION_FOUND`).
     * Merges the name into the existing entry without overwriting RSSI.
     */
    private fun updateDeviceName(device: BluetoothDevice, name: String) {
        _discoveredDevices.update { current ->
            val existing = current[device.address] ?: return@update current
            if (existing.resolvedName == name) current
            else current + (device.address to existing.copy(resolvedName = name))
        }
        Log.d(TAG, "Device name resolved: ${device.address} -> '$name'")
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

    private val _lastFrame = MutableStateFlow<BluetoothFrame?>(null)
    override val lastFrame: StateFlow<BluetoothFrame?> = _lastFrame.asStateFlow()

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
        _lastFrame.value = null

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
                            is ConnectionStrategy.ClassicFirst -> "ClassicFirst"
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

    override fun onFrame(frame: BluetoothFrame?) {
        _lastFrame.value = frame
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
