package com.ccubas.blueconnect.internal

import com.ccubas.blueconnect.core.model.ConnectionState
import com.ccubas.blueconnect.core.model.WeightDataRaw

/**
 * Bridge between transport-specific managers and the public coordinator.
 *
 * Each manager (BLE, Classic, Chipsea, Demo) reports lifecycle and data through this
 * listener. The coordinator owns the StateFlows; managers stay stateless on the observable side.
 */
internal interface BluetoothEventListener {

    fun onStateChange(state: ConnectionState)

    fun onWeightData(raw: WeightDataRaw?)
}
