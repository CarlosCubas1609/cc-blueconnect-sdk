package com.ccubas.blueconnect.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import com.ccubas.blueconnect.core.model.Attempt
import com.ccubas.blueconnect.core.model.ConnectionState
import com.ccubas.blueconnect.core.model.WeightDataRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Synthetic transport for demos and offline development. No real Bluetooth involved.
 *
 * Emits LP7516-style frames (`ST,GS,+ 12.345kg`) so the same parser path the production
 * code uses can be exercised without hardware.
 */
internal class DemoBluetoothManager : IBluetoothManager {

    companion object {
        private const val TAG = "DemoBluetoothManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var weightSimulationJob: Job? = null
    private var currentSimulatedDevice: BluetoothDevice? = null
    private var userDisconnectRequested = false
    private var isConnected = false

    private var eventListener: BluetoothEventListener? = null

    @SuppressLint("MissingPermission")
    override fun connect(
        device: BluetoothDevice,
        attempt: Attempt,
        listener: BluetoothEventListener,
    ): Boolean {
        this.eventListener = listener

        Log.d(
            TAG,
            "Connecting to simulated device: ${device.address} (Attempt ${attempt.attemptNumber}/${attempt.totalAttempts})",
        )

        currentSimulatedDevice = device
        userDisconnectRequested = false
        isConnected = false

        scope.launch {
            delay(1500L)
            isConnected = true
            eventListener?.onStateChange(ConnectionState.Connected(device))
            startWeightSimulation()
        }

        return true
    }

    override fun disconnect() {
        Log.d(TAG, "Disconnecting")
        userDisconnectRequested = true
        isConnected = false

        weightSimulationJob?.cancel()
        if (currentSimulatedDevice != null) {
            eventListener?.onStateChange(
                ConnectionState.Disconnected(currentSimulatedDevice!!, "User initiated"),
            )
        } else {
            eventListener?.onStateChange(ConnectionState.Idle)
        }
        eventListener?.onWeightData(null)
        currentSimulatedDevice = null
    }

    override fun close() {
        Log.d(TAG, "Closing (no events)")
        userDisconnectRequested = true
        isConnected = false

        weightSimulationJob?.cancel()
        eventListener?.onWeightData(null)
        currentSimulatedDevice = null

        eventListener = null
    }

    private fun startWeightSimulation() {
        weightSimulationJob?.cancel()
        weightSimulationJob = scope.launch {
            var baseWeight = Random.nextDouble(50.0, 100.0)
            var isStable = false
            var stabilityCounter = 0

            while (isActive && isConnected) {
                val variation = if (isStable) {
                    Random.nextDouble(-0.01, 0.01)
                } else {
                    Random.nextDouble(-0.5, 0.5)
                }

                val currentWeight = (baseWeight + variation).coerceIn(0.0, 200.0)

                if (!isStable) {
                    stabilityCounter++
                    if (stabilityCounter >= 5) {
                        isStable = true
                        baseWeight = currentWeight
                    }
                } else {
                    if (Random.nextDouble() < 0.05) {
                        isStable = false
                        stabilityCounter = 0
                        baseWeight = currentWeight + Random.nextDouble(-5.0, 5.0)
                    }
                }

                val rawDataString = simulateRawData(currentWeight, isStable)

                val rawData = WeightDataRaw(
                    data = rawDataString,
                    bytes = null,
                )

                eventListener?.onWeightData(rawData)
                delay(1000L)
            }
        }
    }

    /** Mimics the LP7516 wire format: `ST,GS,+ 10.5kg`. */
    private fun simulateRawData(weight: Double, isStable: Boolean): String {
        val state = if (isStable) "ST" else "US"
        val type = if (Random.nextDouble() < 0.85) "GS" else "NT"
        val polarity = if (weight >= 0) "+" else "-"
        val absoluteValue = kotlin.math.abs(weight)
        return "$state,$type,$polarity ${String.format("%.3f", absoluteValue)}kg"
    }
}
