package io.texne.g1.hub.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {

    fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
        }
        return permissions.toTypedArray()
    }

    fun hasAllPermissions(context: Context): Boolean =
        requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    fun createPermissionIntent(context: Context): Intent? {
        val required = requiredPermissions()
        if (required.isEmpty()) {
            return null
        }
        if (hasAllPermissions(context)) {
            return null
        }
        return Intent(context, PermissionActivity::class.java)
    }
}
