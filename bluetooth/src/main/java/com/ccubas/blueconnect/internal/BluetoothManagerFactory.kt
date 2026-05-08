package com.ccubas.blueconnect.internal

import android.content.Context

/**
 * Factory that produces transport-specific managers. Lets the coordinator stay decoupled
 * from concrete classes and lets tests inject fakes.
 */
internal interface IBluetoothManagerFactory {
    fun createGattManager(): IBluetoothManager
    fun createClassicManager(): IBluetoothManager
    fun createDemoManager(): IBluetoothManager
    fun createChipseaManager(): IBluetoothManager
}

internal class BluetoothManagerFactory(
    private val context: Context,
) : IBluetoothManagerFactory {

    override fun createGattManager(): IBluetoothManager = BluetoothGattManager(context)

    override fun createClassicManager(): IBluetoothManager = BluetoothClassicManager()

    override fun createDemoManager(): IBluetoothManager = DemoBluetoothManager()

    override fun createChipseaManager(): IBluetoothManager = BluetoothChipseaManager(context)
}
