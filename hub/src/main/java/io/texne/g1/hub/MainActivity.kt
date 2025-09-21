package io.texne.g1.hub

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.ui.ApplicationFrame
import io.texne.g1.hub.ui.theme.G1HubTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject
    lateinit var repository: Repository

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.BLUETOOTH_CONNECT] == false) {
                Log.w(TAG, "PERM_MISSING=CONNECT")
            }
            val scanDenied = results[Manifest.permission.BLUETOOTH_SCAN] == false ||
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                    results[Manifest.permission.ACCESS_FINE_LOCATION] == false)
            if (scanDenied) {
                Log.w(TAG, "PERM_MISSING=SCAN")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository.bindService()
        requestBluetoothPermissions()

        enableEdgeToEdge()
        setContent {
            G1HubTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    Box(Modifier.padding(innerPadding).fillMaxSize()) {
                        ApplicationFrame(snackbarHostState)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.unbindService()
    }

    private fun requestBluetoothPermissions() {
        val required = requiredPermissions()
        if (required.isEmpty()) {
            return
        }
        val missing = required.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
