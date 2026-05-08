package com.ccubas.blueconnect.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.ccubas.blueconnect.core.model.Attempt
import com.ccubas.blueconnect.core.model.WeightDataRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Bluetooth Classic transport (SPP / RFCOMM). Reads `\n`/`\r\n`-delimited text frames.
 *
 * Tested with the LP7516 weight indicator, which broadcasts lines like
 * `ST,GS,+ 10.5kg`, but works with any device speaking SPP and emitting newline-terminated
 * text. Parsing is left to [com.ccubas.blueconnect.core.parser.WeightFrameParser].
 */
internal class BluetoothClassicManager : BaseBluetoothManager() {

    override val TAG = "BluetoothClassicManager"

    companion object {
        /** Standard Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    override fun connect(
        device: BluetoothDevice,
        attempt: Attempt,
        listener: BluetoothEventListener,
    ): Boolean {
        setListener(listener)

        return try {
            logConnectionAttempt("TRYING CLASSIC (SPP) PROTOCOL", device)
            Log.i(TAG, "Method: RFCOMM Socket")
            Log.i(TAG, "SPP UUID: $SPP_UUID")

            resetDisconnectFlag()

            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)

            if (bluetoothSocket == null) {
                Log.e(TAG, "Failed to create RFCOMM socket")
                notifyConnectionFailed(device, "Failed to create RFCOMM socket", attempt)
                return false
            }

            Log.d(TAG, "RFCOMM socket created, connecting...")

            scope.launch {
                try {
                    Log.d(TAG, "Calling socket.connect()...")
                    bluetoothSocket?.connect()

                    if (bluetoothSocket?.isConnected == true) {
                        Log.d(TAG, "Connected (Classic)")
                        notifyConnected(device)
                        startReading(device)
                    } else {
                        Log.e(TAG, "Socket not connected after connect()")
                        notifyConnectionFailed(device, "Socket not connected after connect()", attempt)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "IOException connecting socket: ${e.message}", e)
                    notifyConnectionFailed(device, "IOException: ${e.message}", attempt)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error connecting: ${e.message}", e)
                    notifyConnectionFailed(device, "Unexpected error: ${e.message}", attempt)
                }
            }

            true
        } catch (e: SecurityException) {
            handleConnectionException(e, device, attempt, isSecurityException = true)
        } catch (e: Exception) {
            handleConnectionException(e, device, attempt)
        }
    }

    private suspend fun startReading(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        readJob?.cancelAndJoin()

        readJob = scope.launch {
            val inputStream: InputStream? = try {
                bluetoothSocket?.inputStream
            } catch (e: IOException) {
                Log.e(TAG, "Error getting input stream", e)
                notifyDisconnected(device, "Error getting input stream: ${e.message}")
                return@launch
            }

            if (inputStream == null) {
                Log.e(TAG, "Input stream is null")
                notifyDisconnected(device, "Input stream is null")
                return@launch
            }

            val buffer = ByteArray(1024)
            val stringBuilder = StringBuilder()

            Log.d(TAG, "Started reading from input stream...")

            while (isActive && bluetoothSocket?.isConnected == true) {
                try {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val data = String(buffer, 0, bytesRead)
                        stringBuilder.append(data)

                        Log.d(TAG, "Received data: $data")

                        val lines = stringBuilder.toString().split("\n", "\r\n")

                        for (i in 0 until lines.size - 1) {
                            val line = lines[i].trim()
                            if (line.isNotEmpty()) {
                                emitRawData(line)
                            }
                        }

                        stringBuilder.clear()
                        stringBuilder.append(lines.last())

                        if (stringBuilder.length > 1000) {
                            stringBuilder.clear()
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error reading from socket", e)
                    notifyDisconnected(device, getDisconnectReason())
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error reading", e)
                }
            }

            Log.d(TAG, "Stopped reading from input stream")
        }
    }

    private fun emitRawData(line: String) {
        try {
            Log.d(TAG, "Received line: $line")
            val rawData = WeightDataRaw(
                data = line,
                bytes = null,
            )
            notifyWeightData(rawData)
        } catch (e: Exception) {
            Log.e(TAG, "Error emitting raw weight data", e)
        }
    }

    override fun disconnect() {
        try {
            Log.d(TAG, "Disconnecting")
            markUserDisconnect()

            scope.launch {
                readJob.safeCancel()
                readJob = null
            }

            val device = bluetoothSocket?.remoteDevice
            bluetoothSocket?.close()
            device?.let { notifyDisconnected(it, "User initiated") }
            bluetoothSocket = null
            clearWeightData()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    override fun close() {
        try {
            Log.d(TAG, "Closing (no events)")
            markUserDisconnect()

            scope.launch {
                readJob.safeCancel()
                readJob = null
            }

            bluetoothSocket?.close()
            bluetoothSocket = null
            clearWeightData()
            clearListener()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing", e)
        }
    }
}
