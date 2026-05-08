package com.ccubas.blueconnect.core.model

/**
 * Raw frame received from a Bluetooth device.
 *
 * The transport-level managers do not interpret the payload; they only forward what arrived.
 * Consumers either feed this into [com.ccubas.blueconnect.core.parser.WeightFrameParser] or
 * decode it manually.
 *
 * @param data Text representation. For text protocols this is the line; for binary protocols
 *             it is a hex dump of [bytes].
 * @param bytes Original binary payload, when the source is binary (BLE GATT, Chipsea adv data).
 *              Null for text-only protocols (Classic SPP, Demo).
 */
data class WeightDataRaw(
    val data: String,
    val bytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WeightDataRaw

        if (data != other.data) return false
        if (bytes != null) {
            if (other.bytes == null) return false
            if (!bytes.contentEquals(other.bytes)) return false
        } else if (other.bytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        return result
    }
}
