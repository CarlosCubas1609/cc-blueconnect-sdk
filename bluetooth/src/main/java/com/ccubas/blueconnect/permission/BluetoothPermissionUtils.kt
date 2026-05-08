package com.ccubas.blueconnect.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime-permission helpers. The set of permissions the SDK actually needs differs by API
 * level, so consumers ask this utility instead of hardcoding the array themselves.
 */
object BluetoothPermissionUtils {

    /**
     * Returns the permissions the SDK needs at runtime:
     * - Android 12+ (API 31+): BLUETOOTH_SCAN, BLUETOOTH_CONNECT.
     * - Older: BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION.
     */
    fun getRequiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    /** True when every permission returned by [getRequiredPermissions] is granted. */
    fun hasBluetoothPermissions(context: Context): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
