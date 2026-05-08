package com.ccubas.blueconnect.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.ccubas.blueconnect.core.model.Attempt
import java.util.UUID

/**
 * BLE GATT transport. Connects via [BluetoothDevice.connectGatt], discovers services,
 * subscribes to the standard Weight Scale Service and falls back to any notify-capable
 * characteristic when the service isn't advertised.
 *
 * Forwards lifecycle and raw payloads through [BluetoothEventListener] — no internal Flows.
 */
internal class BluetoothGattManager(private val context: Context) : BaseBluetoothManager() {

    override val TAG = "BluetoothGattManager"

    companion object {
        val WEIGHT_SCALE_SERVICE_UUID: UUID = UUID.fromString("0000181D-0000-1000-8000-00805f9b34fb")
        val WEIGHT_MEASUREMENT_UUID: UUID = UUID.fromString("00002A9D-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null

    private fun gattCallback(device: BluetoothDevice, attempt: Attempt): BluetoothGattCallback =
        @SuppressLint("MissingPermission")
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    val errorMsg = when (status) {
                        133 -> "Generic connection error (133) — device out of range or unavailable"
                        62 -> "Error (62) — device disconnected unexpectedly"
                        8 -> "Error (8) — connection timeout"
                        19 -> "Error (19) — disconnected by remote host"
                        22 -> "Error (22) — LMP response timeout"
                        132 -> "Error (132) — could not start connection"
                        147 -> "Error (147) — device does not support BLE or requires bonding first"
                        else -> "Unknown connection error ($status)"
                    }
                    Log.e(TAG, errorMsg)
                    notifyConnectionFailed(device, errorMsg, attempt)
                    clearWeightData()
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to GATT server, discovering services...")
                        notifyConnected(device)
                        gatt?.discoverServices()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from GATT server")
                        notifyDisconnected(device, getDisconnectReason())
                        clearWeightData()
                        resetDisconnectFlag()
                    }

                    BluetoothProfile.STATE_CONNECTING -> {
                        Log.d(TAG, "Connecting to GATT server...")
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "═══════════════════════════════════════")
                    Log.d(TAG, "Services discovered:")

                    gatt?.services?.forEach { service ->
                        Log.d(TAG, "Service UUID: ${service.uuid}")
                        service.characteristics.forEach { characteristic ->
                            val props = StringBuilder()
                            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) props.append("READ ")
                            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) props.append("WRITE ")
                            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) props.append("NOTIFY ")
                            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) props.append("INDICATE ")

                            Log.d(TAG, "└─ Characteristic: ${characteristic.uuid}")
                            Log.d(TAG, "   Properties: $props")
                        }
                    }
                    Log.d(TAG, "═══════════════════════════════════════")

                    val service = gatt?.getService(WEIGHT_SCALE_SERVICE_UUID)
                    if (service != null) {
                        Log.d(TAG, "Weight Scale Service found")
                        val characteristic = service.getCharacteristic(WEIGHT_MEASUREMENT_UUID)
                        if (characteristic != null) {
                            enableNotifications(gatt, characteristic)
                        } else {
                            Log.w(TAG, "Weight Measurement Characteristic not found")
                            findAndEnableWeightCharacteristic(gatt)
                        }
                    } else {
                        Log.w(TAG, "Weight Scale Service not found, looking for alternatives...")
                        findAndEnableWeightCharacteristic(gatt)
                    }
                } else {
                    Log.w(TAG, "Service discovery failed with status: $status")
                }
            }

            @SuppressLint("MissingPermission")
            private fun findAndEnableWeightCharacteristic(gatt: BluetoothGatt?) {
                gatt?.services?.forEach { service ->
                    Log.d(TAG, "Service: ${service.uuid}")
                    service.characteristics.forEach { characteristic ->
                        Log.d(TAG, "  Characteristic: ${characteristic.uuid}, properties: ${characteristic.properties}")

                        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        ) {
                            Log.d(TAG, "  Found notifiable characteristic, enabling...")
                            enableNotifications(gatt, characteristic)
                            return
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            private fun enableNotifications(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic,
            ) {
                Log.d(TAG, "═══════════════════════════════════════")
                Log.d(TAG, "Enabling notifications for characteristic: ${characteristic.uuid}")

                val notificationSet = gatt?.setCharacteristicNotification(characteristic, true)
                Log.d(TAG, "setCharacteristicNotification result: $notificationSet")

                val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                if (descriptor != null) {
                    Log.d(TAG, "Descriptor found: $CLIENT_CHARACTERISTIC_CONFIG_UUID")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Log.d(TAG, "Using Android 13+ API for descriptor write")
                        val writeResult = gatt?.writeDescriptor(
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                        )
                        Log.d(TAG, "writeDescriptor result: $writeResult")
                    } else {
                        Log.d(TAG, "Using legacy API for descriptor write")
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        val writeResult = gatt?.writeDescriptor(descriptor)
                        Log.d(TAG, "writeDescriptor result: $writeResult")
                    }
                    Log.d(TAG, "Waiting for onDescriptorWrite callback...")
                } else {
                    Log.e(TAG, "Descriptor NOT found — notifications will NOT work.")
                }
                Log.d(TAG, "═══════════════════════════════════════")
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                Log.d(TAG, "onCharacteristicChanged (Android 13+)")
                Log.d(TAG, "Characteristic UUID: ${characteristic.uuid}")
                Log.d(TAG, "Value size: ${value.size} bytes")

                val rawData = createWeightDataRaw(value)
                if (rawData != null) {
                    notifyWeightData(rawData)
                    Log.d(TAG, "Raw weight data emitted to listener")
                } else {
                    Log.w(TAG, "createRawData returned null, no data emitted")
                }
            }

            @Deprecated("Deprecated in Java")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Log.d(TAG, "onCharacteristicChanged (legacy)")
                    Log.d(TAG, "Characteristic UUID: ${characteristic.uuid}")

                    @Suppress("DEPRECATION")
                    val value = characteristic.value
                    Log.d(TAG, "Value size: ${value?.size ?: 0} bytes")

                    val rawData = createWeightDataRaw(value)
                    if (rawData != null) {
                        notifyWeightData(rawData)
                        Log.d(TAG, "Raw weight data emitted to listener (legacy)")
                    } else {
                        Log.w(TAG, "createRawData returned null, no data emitted (legacy)")
                    }
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Descriptor write completed for ${descriptor.characteristic.uuid}")
                    Log.d(TAG, "Notifications enabled. Waiting for weight data...")
                } else {
                    Log.e(TAG, "Descriptor write FAILED with status: $status")
                }
            }
        }

    @SuppressLint("MissingPermission")
    override fun connect(
        device: BluetoothDevice,
        attempt: Attempt,
        listener: BluetoothEventListener,
    ): Boolean {
        setListener(listener)

        return try {
            logConnectionAttempt("TRYING BLE (GATT) PROTOCOL", device)

            resetDisconnectFlag()

            bluetoothGatt = device.connectGatt(context, false, gattCallback(device, attempt))

            if (bluetoothGatt == null) {
                Log.e(TAG, "connectGatt returned null")
                notifyConnectionFailed(device, "connectGatt returned null", attempt)
                return false
            }

            Log.d(TAG, "connectGatt called successfully")
            true
        } catch (e: SecurityException) {
            handleConnectionException(e, device, attempt, isSecurityException = true)
        } catch (e: Exception) {
            handleConnectionException(e, device, attempt)
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        Log.d(TAG, "User requested disconnect")
        markUserDisconnect()
        bluetoothGatt?.device?.let { notifyDisconnected(it, "User initiated") }
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        clearWeightData()
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        Log.d(TAG, "Closing (no events)")
        markUserDisconnect()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        clearWeightData()
        clearListener()
    }
}
