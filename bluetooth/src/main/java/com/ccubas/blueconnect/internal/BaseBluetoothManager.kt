package com.ccubas.blueconnect.internal

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.ccubas.blueconnect.core.model.Attempt
import com.ccubas.blueconnect.core.model.ConnectionState
import com.ccubas.blueconnect.core.model.WeightDataRaw
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

/**
 * Common plumbing for [IBluetoothManager] implementations: listener handling, disconnect-flag
 * tracking, helper builders, hex formatting.
 */
internal abstract class BaseBluetoothManager : IBluetoothManager {

    protected abstract val TAG: String

    private var eventListener: BluetoothEventListener? = null

    /** True when the user requested the disconnect (vs. the device dropping). */
    protected var userDisconnectRequested = false

    // ==================== LIFECYCLE ====================

    internal fun setListener(listener: BluetoothEventListener) {
        this.eventListener = listener
    }

    internal fun clearListener() {
        this.eventListener = null
    }

    protected fun markUserDisconnect() {
        userDisconnectRequested = true
    }

    protected fun resetDisconnectFlag() {
        userDisconnectRequested = false
    }

    // ==================== NOTIFICATIONS ====================

    protected fun notifyStateChange(state: ConnectionState) {
        eventListener?.onStateChange(state)
    }

    protected fun notifyWeightData(data: WeightDataRaw?) {
        eventListener?.onWeightData(data)
    }

    protected fun notifyConnectionFailed(
        device: BluetoothDevice,
        reason: String,
        attempt: Attempt,
    ) {
        Log.e(TAG, "Connection failed: $reason")
        notifyStateChange(ConnectionState.ConnectionFailed(device, reason, attempt))
    }

    protected fun notifyConnected(device: BluetoothDevice) {
        notifyStateChange(ConnectionState.Connected(device))
    }

    protected fun notifyDisconnected(device: BluetoothDevice, reason: String) {
        notifyStateChange(ConnectionState.Disconnected(device, reason))
    }

    protected fun clearWeightData() {
        notifyWeightData(null)
    }

    protected fun getDisconnectReason(): String =
        if (userDisconnectRequested) "User initiated" else "Device disconnected"

    // ==================== LOGGING ====================

    protected fun logConnectionAttempt(protocolName: String, device: BluetoothDevice) {
        Log.i(TAG, "╔═══════════════════════════════════════╗")
        Log.i(TAG, "║   $protocolName")
        Log.i(TAG, "╚═══════════════════════════════════════╝")
        Log.i(TAG, "Device: ${device.address}")
        Log.i(TAG, "Method: $protocolName")
        Log.i(TAG, "═══════════════════════════════════════")
    }

    // ==================== ERROR HANDLING ====================

    protected fun handleConnectionException(
        e: Exception,
        device: BluetoothDevice,
        attempt: Attempt,
        isSecurityException: Boolean = false,
    ): Boolean {
        val prefix = if (isSecurityException) "SecurityException" else "Exception"
        val message = "$prefix: ${e.message}"
        Log.e(TAG, "Error connecting to device", e)
        notifyConnectionFailed(device, message, attempt)
        return false
    }

    // ==================== UTILITIES ====================

    protected fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }

    protected fun createWeightDataRaw(data: ByteArray?): WeightDataRaw? {
        if (data == null) {
            Log.w(TAG, "createRawData: data is NULL")
            return null
        }

        if (data.isEmpty()) {
            Log.w(TAG, "createRawData: data is EMPTY")
            return null
        }

        val rawHex = data.toHexString()
        Log.d(TAG, "Raw data received: $rawHex (${data.size} bytes)")

        return WeightDataRaw(
            data = rawHex,
            bytes = data,
        )
    }

    protected suspend fun Job?.safeCancel() {
        try {
            this?.cancelAndJoin()
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling job", e)
        }
    }
}
