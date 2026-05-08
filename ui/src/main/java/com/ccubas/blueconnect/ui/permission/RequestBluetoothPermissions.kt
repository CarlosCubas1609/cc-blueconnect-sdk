package com.ccubas.blueconnect.ui.permission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.ccubas.blueconnect.permission.BluetoothPermissionUtils

/**
 * Compose helper that requests the Bluetooth permissions returned by
 * [BluetoothPermissionUtils.getRequiredPermissions] on first composition.
 *
 * Calls [onPermissionsGranted] immediately if every permission is already granted; otherwise
 * launches the system dialog and reports the outcome via [onPermissionsGranted] /
 * [onPermissionsDenied].
 */
@Composable
fun RequestBluetoothPermissions(
    onPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit,
) {
    val context = LocalContext.current
    val permissions = BluetoothPermissionUtils.getRequiredPermissions()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionsResult ->
        val allGranted = permissionsResult.values.all { it }
        if (allGranted) {
            onPermissionsGranted()
        } else {
            onPermissionsDenied()
        }
    }

    LaunchedEffect(Unit) {
        if (BluetoothPermissionUtils.hasBluetoothPermissions(context)) {
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(permissions)
        }
    }
}
