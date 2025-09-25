package io.texne.g1.hub

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.android.AndroidEntryPoint
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.ui.ApplicationFrame
import io.texne.g1.hub.ui.theme.BreathOfFire4Theme
import io.texne.g1.hub.R
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var repository: Repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasBluetoothPermissions()) {
            repository.bindService()
        } else {
            requestBluetoothPermissions()
        }

        enableEdgeToEdge()
        setContent {
            BreathOfFire4Theme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = { Bof4AppBar() },
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val bluetoothPermissionsGranted =
            requestCode == REQUEST_CODE_BLUETOOTH &&
                grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        if (bluetoothPermissionsGranted) {
            repository.bindService()
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        return BLUETOOTH_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, BLUETOOTH_PERMISSIONS, REQUEST_CODE_BLUETOOTH)
        }
    }

    private companion object {
        private const val REQUEST_CODE_BLUETOOTH = 0x42
        private val BLUETOOTH_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    }
}

@Composable
private fun Bof4AppBar() {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            LayoutInflater.from(ctx).inflate(R.layout.bof4_app_bar, null, false) as AppBarLayout
        },
        update = { appBarLayout ->
            val toolbar = appBarLayout.findViewById<MaterialToolbar>(R.id.bof4Toolbar)
            val title = context.getString(R.string.app_name)
            if (toolbar.title != title) {
                toolbar.title = title
            }
        }
    )
}
