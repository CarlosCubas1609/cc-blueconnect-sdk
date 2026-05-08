package com.ccubas.blueconnect.core.model

/**
 * Errors emitted by the scanning pipeline.
 *
 * Consumers can branch on the subtype to decide what UI affordance to show
 * (e.g. open the system Bluetooth settings, request permissions, surface a generic message).
 */
sealed class ScanError {

    abstract val message: String

    /** Bluetooth is supported but the user has it turned off. */
    data class BluetoothDisabled(override val message: String) : ScanError()

    /** The host device does not have a Bluetooth adapter. */
    data class AdapterNotAvailable(override val message: String) : ScanError()

    /** The Bluetooth scan API returned a failure (see [errorCode]). */
    data class ScanFailed(override val message: String, val errorCode: Int? = null) : ScanError()

    /** Required runtime permissions are missing. */
    data class PermissionDenied(override val message: String) : ScanError()
}
