package com.ccubas.blueconnect.core.model

/**
 * Parsed weight reading.
 *
 * Output of [com.ccubas.blueconnect.core.parser.WeightFrameParser.parse]. Pure domain type with no Android dependencies.
 */
data class WeightData(
    val weight: Double,
    val unit: String,
    val isStable: Boolean,
    /** Detected protocol (e.g. "LP7516", "BLE", "Chipsea", "CustomChipsea"). */
    val protocol: String? = null,
)
