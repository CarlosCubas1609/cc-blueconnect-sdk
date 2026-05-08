package com.ccubas.blueconnect.core.parser

import android.util.Log
import com.ccubas.blueconnect.core.model.WeightData
import com.ccubas.blueconnect.core.model.WeightDataRaw

/**
 * Optional frame parser for weight scales speaking the protocols supported out of the box:
 *
 * - **LP7516 (Classic SPP)** — text frames like `ST,GS,+ 10.5kg`.
 * - **BLE Weight Scale Service (GATT)** — standard binary format (flags + UINT16 weight).
 * - **Chipsea v2.0 / v1.1** — manufacturer advertisement payloads (15+ bytes, magic `0xCA`).
 * - **Custom Chipsea (0x??C0)** — reverse-engineered variant; weight in bytes 0–1 big-endian / 100.
 * - **Plain text** — `12.34 kg`, `12.34kg`, `12.34`.
 *
 * Format detection is automatic: callers just pass the [WeightDataRaw] they received from the
 * client and get back a [WeightData] or null if no parser matched.
 */
object WeightFrameParser {

    private const val TAG = "WeightFrameParser"

    /** Try every known format; return the first match. */
    fun parse(raw: WeightDataRaw): WeightData? {
        Log.d(TAG, "Attempting to parse raw data: ${raw.data}")

        raw.bytes?.let { bytes ->
            if (bytes.isNotEmpty() && bytes[0] == 0xCA.toByte()) {
                Log.d(TAG, "Detected Chipsea format (magic byte 0xCA)")
                parseChipseaData(bytes)?.let { return it }
            }

            if (bytes.size >= 6) {
                parseCustomChipseaData(bytes)?.let {
                    Log.d(TAG, "Detected Custom Chipsea format")
                    return it
                }
            }

            if (bytes.size >= 3) {
                parseBLEData(bytes)?.let {
                    Log.d(TAG, "Detected BLE Weight Scale format")
                    return it
                }
            }

            parseSimpleBinary(bytes)?.let {
                Log.d(TAG, "Detected simple binary format")
                return it
            }
        }

        parseTextData(raw.data)?.let {
            Log.d(TAG, "Detected text format: ${it.protocol}")
            return it
        }

        Log.w(TAG, "Could not parse data in any known format")
        return null
    }

    // ==================== TEXT PARSERS ====================

    private fun parseTextData(textData: String): WeightData? = try {
        when {
            textData.matches(Regex("(ST|US),(GS|NT),([+-])\\s*([\\d.]+)(kg|g|lb|oz)", RegexOption.IGNORE_CASE)) ->
                parseLP7516Format(textData)

            textData.matches(Regex(".*([+-])?\\s*([\\d.]+)\\s*(kg|g|lb|oz).*", RegexOption.IGNORE_CASE)) ->
                parseSimpleTextFormat(textData)

            textData.matches(Regex("([+-])?\\s*([\\d.]+)")) ->
                parseNumberOnlyFormat(textData)

            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error parsing text data", e)
        null
    }

    private fun parseLP7516Format(line: String): WeightData? {
        val match = Regex("(ST|US),(GS|NT),([+-])\\s*([\\d.]+)(kg|g|lb|oz)", RegexOption.IGNORE_CASE)
            .find(line) ?: return null

        val state = match.groupValues[1]
        val polarity = match.groupValues[3]
        val value = match.groupValues[4]
        val unit = match.groupValues[5]

        val isStable = state.equals("ST", ignoreCase = true)
        val weightValue = value.toDoubleOrNull() ?: 0.0
        val finalWeight = if (polarity == "-") -weightValue else weightValue

        return WeightData(
            weight = finalWeight,
            unit = unit.lowercase(),
            isStable = isStable,
            protocol = "LP7516",
        )
    }

    private fun parseSimpleTextFormat(line: String): WeightData? {
        val match = Regex("([+-])?\\s*([\\d.]+)\\s*(kg|g|lb|oz)", RegexOption.IGNORE_CASE).find(line)
            ?: return null

        val polarity = match.groupValues[1].ifEmpty { "+" }
        val value = match.groupValues[2]
        val unit = match.groupValues[3]

        val weightValue = value.toDoubleOrNull() ?: 0.0
        val finalWeight = if (polarity == "-") -weightValue else weightValue

        return WeightData(
            weight = finalWeight,
            unit = unit.lowercase(),
            isStable = true,
            protocol = "Simple",
        )
    }

    private fun parseNumberOnlyFormat(line: String): WeightData? {
        val match = Regex("([+-])?\\s*([\\d.]+)").find(line) ?: return null

        val polarity = match.groupValues[1].ifEmpty { "+" }
        val value = match.groupValues[2]

        val weightValue = value.toDoubleOrNull() ?: 0.0
        val finalWeight = if (polarity == "-") -weightValue else weightValue

        return WeightData(
            weight = finalWeight,
            unit = "kg",
            isStable = true,
            protocol = "NumberOnly",
        )
    }

    // ==================== BINARY PARSERS ====================

    /** Standard BLE Weight Scale Service: flags byte + UINT16 weight (LE). */
    private fun parseBLEData(data: ByteArray): WeightData? = try {
        if (data.size < 3) {
            null
        } else {
            val flags = data[0].toInt()
            val isKg = (flags and 0x01) == 0
            val weightValue = ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val weight = if (isKg) weightValue * 0.005 else weightValue * 0.01
            val unit = if (isKg) "kg" else "lb"
            val isStable = (flags and 0x04) != 0

            WeightData(
                weight = weight,
                unit = unit,
                isStable = isStable,
                protocol = "BLE",
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error parsing BLE format", e)
        null
    }

    /**
     * Chipsea v2.0 / v1.1 advertisement payload.
     * Layout: 0=0xCA, 1=version, 2=length, 3..6=productId, 7=scaleProperty (unit nibble),
     * 8=measurementStatus, 9=sequence, 10..11=weight (LE / 10), 12..13=impedance, 14=xor checksum.
     */
    private fun parseChipseaData(data: ByteArray): WeightData? {
        if (data.size < 15) return null
        if (data[0] != 0xCA.toByte()) return null

        return try {
            val scaleProperty = data[7].toInt() and 0xFF
            val unit = when (scaleProperty and 0x0F) {
                0x00 -> "kg"
                0x01 -> "lb"
                0x02 -> "st"
                else -> "kg"
            }

            val measurementStatus = data[8].toInt() and 0xFF
            val isStable = measurementStatus == 0x01

            val weightRaw = ((data[11].toInt() and 0xFF) shl 8) or (data[10].toInt() and 0xFF)
            val weight = weightRaw / 10.0

            WeightData(
                weight = weight,
                unit = unit,
                isStable = isStable,
                protocol = "Chipsea",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Chipsea data", e)
            null
        }
    }

    /**
     * Custom Chipsea (manufacturer ID with low byte 0xC0). Reverse-engineered:
     * bytes 0..1 = weight (BE / 100), byte 4 = unit (0x0A=kg, 0x0B=lb, 0x0C=jin).
     * Sanity-checks the weight to avoid false positives on unrelated payloads.
     */
    private fun parseCustomChipseaData(data: ByteArray): WeightData? {
        if (data.size < 6) return null

        return try {
            val weightRaw = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val weight = weightRaw / 100.0

            if (weight < 0.0 || weight > 500.0) return null

            val unitByte = if (data.size > 4) data[4].toInt() and 0xFF else 0x0A
            val unit = when (unitByte) {
                0x0A -> "kg"
                0x0B -> "lb"
                0x0C -> "jin"
                else -> "kg"
            }

            val isStable = weight > 0.0

            WeightData(
                weight = weight,
                unit = unit,
                isStable = isStable,
                protocol = "CustomChipsea",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Custom Chipsea data", e)
            null
        }
    }

    private fun parseSimpleBinary(data: ByteArray): WeightData? = when (data.size) {
        2 -> parseSimpleBinary2Bytes(data)
        4 -> parseSimpleBinary4Bytes(data)
        else -> null
    }

    private fun parseSimpleBinary2Bytes(data: ByteArray): WeightData? = try {
        val weight = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
        WeightData(
            weight = weight / 100.0,
            unit = "kg",
            isStable = true,
            protocol = "Simple2B",
        )
    } catch (e: Exception) {
        null
    }

    private fun parseSimpleBinary4Bytes(data: ByteArray): WeightData? = try {
        val weight = java.nio.ByteBuffer.wrap(data)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .float.toDouble()

        WeightData(
            weight = weight,
            unit = "kg",
            isStable = true,
            protocol = "Simple4B",
        )
    } catch (e: Exception) {
        null
    }
}
