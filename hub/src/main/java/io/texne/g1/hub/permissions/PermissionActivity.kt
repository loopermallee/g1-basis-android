package io.texne.g1.hub.permissions

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class PermissionActivity : ComponentActivity() {

    companion object {
        private const val KEY_REQUEST_LAUNCHED = "permission_launched"
    }

    private var requestLaunched = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            complete(PermissionHelper.hasAllPermissions(this))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        if (PermissionHelper.requiredPermissions().isEmpty()) {
            complete(true)
            return
        }

        if (PermissionHelper.hasAllPermissions(this)) {
            complete(true)
            return
        }

        requestLaunched = savedInstanceState?.getBoolean(KEY_REQUEST_LAUNCHED) ?: false
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) {
            return
        }
        if (!requestLaunched) {
            requestLaunched = true
            permissionLauncher.launch(PermissionHelper.requiredPermissions())
        } else if (PermissionHelper.hasAllPermissions(this)) {
            complete(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_REQUEST_LAUNCHED, requestLaunched)
    }

    private fun complete(granted: Boolean) {
        setResult(if (granted) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }
}
