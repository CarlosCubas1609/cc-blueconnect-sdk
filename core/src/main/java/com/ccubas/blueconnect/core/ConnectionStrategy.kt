package com.ccubas.blueconnect.core

/**
 * Available Bluetooth manager backends. Each maps to a transport implementation
 * in the `:bluetooth` module.
 */
sealed class ManagerType {
    data object BLE : ManagerType()
    data object Classic : ManagerType()
    data object Demo : ManagerType()
    data object Chipsea : ManagerType()
}

/**
 * Ordered list of protocols to try when connecting to a device.
 *
 * Lets you trade off speed (single-protocol strategies like [BleOnly]) against robustness
 * (multi-protocol strategies like [BleFirst] that fall back automatically).
 */
sealed class ConnectionStrategy {
    abstract val protocols: List<ManagerType>
    abstract val description: String

    /** BLE only — for devices that advertise GATT services. */
    data object BleOnly : ConnectionStrategy() {
        override val protocols = listOf(ManagerType.BLE)
        override val description = "Will use: BLE (GATT) connection"
    }

    /** Bluetooth Classic only — for devices speaking SPP/RFCOMM. */
    data object ClassicOnly : ConnectionStrategy() {
        override val protocols = listOf(ManagerType.Classic)
        override val description = "Will use: Classic (SPP) connection"
    }

    /** BLE first, fall back to Classic, then Chipsea. */
    data object BleFirst : ConnectionStrategy() {
        override val protocols = listOf(ManagerType.BLE, ManagerType.Classic, ManagerType.Chipsea)
        override val description = "Will try: BLE → Classic → Chipsea"
    }

    /** Chipsea first, fall back to BLE, then Classic — for devices of unknown type. */
    data object ChipseaFirst : ConnectionStrategy() {
        override val protocols = listOf(ManagerType.Chipsea, ManagerType.BLE, ManagerType.Classic)
        override val description = "Will try: Chipsea → BLE → Classic"
    }

    /** Chipsea only — for advertised-data scales (no persistent connection). */
    data object ChipseaOnly : ConnectionStrategy() {
        override val protocols = listOf(ManagerType.Chipsea)
        override val description = "Will use: Chipsea (Advertisement) connection"
    }

    /** Demo / simulated mode. No real Bluetooth involved. */
    data object DemoOnly : ConnectionStrategy() {
        override val protocols = listOf(ManagerType.Demo)
        override val description = "DEMO MODE"
    }

    /**
     * Returns the next protocol to try given the previously attempted one.
     * If [lastType] is null, returns the first protocol; if [lastType] was the last in the list,
     * cycles back to the first.
     */
    fun getNextProtocol(lastType: ManagerType?): ManagerType {
        if (lastType == null) return protocols.first()
        val currentIndex = protocols.indexOf(lastType)
        return if (currentIndex == -1 || currentIndex == protocols.size - 1) {
            protocols.first()
        } else {
            protocols[currentIndex + 1]
        }
    }

    /** Friendly name for logging / UI. */
    fun getProtocolName(managerType: ManagerType): String = when (managerType) {
        ManagerType.BLE -> "BLE (GATT Connection)"
        ManagerType.Classic -> "CLASSIC (SPP/RFCOMM)"
        ManagerType.Chipsea -> "CHIPSEA (Advertisement Scan)"
        ManagerType.Demo -> "DEMO"
    }

    companion object {
        /**
         * Resolves a strategy from a stored protocol string (e.g. coming from a [com.ccubas.blueconnect.core.model.SavedDevice]).
         * Accepts short names ("BLE"), full names ("BLE (GATT Connection)"), and multi-protocol
         * names ("BleFirst"). Returns null if the input is unrecognized.
         */
        fun fromProtocolString(protocolString: String): ConnectionStrategy? = when (protocolString) {
            "BleFirst" -> BleFirst
            "ChipseaFirst" -> ChipseaFirst

            "BLE" -> BleOnly
            "Classic" -> ClassicOnly
            "Chipsea" -> ChipseaOnly
            "Demo" -> DemoOnly

            "BLE (GATT Connection)" -> BleOnly
            "CLASSIC (SPP/RFCOMM)" -> ClassicOnly
            "CHIPSEA (Advertisement Scan)" -> ChipseaOnly
            "DEMO" -> DemoOnly

            else -> when {
                protocolString.contains("BleFirst", ignoreCase = true) -> BleFirst
                protocolString.contains("ChipseaFirst", ignoreCase = true) -> ChipseaFirst
                protocolString.contains("BLE", ignoreCase = true) -> BleOnly
                protocolString.contains("CLASSIC", ignoreCase = true) -> ClassicOnly
                protocolString.contains("CHIPSEA", ignoreCase = true) -> ChipseaOnly
                protocolString.contains("DEMO", ignoreCase = true) -> DemoOnly
                else -> null
            }
        }
    }
}
