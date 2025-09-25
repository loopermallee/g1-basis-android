package io.texne.g1.hub

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.databinding.ActivityMainBinding
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.permissions.PermissionHelper
import io.texne.g1.hub.ui.ApplicationViewModel
import io.texne.g1.hub.ui.GlassesAdapter
import io.texne.g1.hub.ui.TelemetryEntryAdapter
import io.texne.g1.hub.ui.TelemetryLogAdapter
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var repository: Repository

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ApplicationViewModel by viewModels()

    private val glassesAdapter = GlassesAdapter(object : GlassesAdapter.GlassesActionListener {
        override fun onConnect(id: String) {
            runWithPermissions { viewModel.connect(id) }
        }

        override fun onDisconnect(id: String) {
            viewModel.disconnect(id)
        }

        override fun onCancelRetry(id: String) {
            viewModel.cancelAutoRetry(id)
        }

        override fun onRetryNow(id: String) {
            runWithPermissions { viewModel.retryNow(id) }
        }
    })

    private val telemetryEntryAdapter = TelemetryEntryAdapter()
    private val telemetryLogAdapter = TelemetryLogAdapter()

    private var pendingPermissionAction: (() -> Unit)? = null
    private var autoPromptShown = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val action = pendingPermissionAction
            pendingPermissionAction = null
            if (result.resultCode == Activity.RESULT_OK) {
                action?.invoke()
            } else {
                viewModel.onPermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasBluetoothPermissions()) {
            repository.bindService()
        } else {
            requestBluetoothPermissions()
        }

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerGlasses.layoutManager = LinearLayoutManager(this)
        binding.recyclerGlasses.adapter = glassesAdapter
        binding.recyclerTelemetryEntries.layoutManager = LinearLayoutManager(this)
        binding.recyclerTelemetryEntries.adapter = telemetryEntryAdapter
        binding.recyclerTelemetryLogs.layoutManager = LinearLayoutManager(this)
        binding.recyclerTelemetryLogs.adapter = telemetryLogAdapter

        binding.buttonScan.setOnClickListener {
            runWithPermissions { viewModel.scan() }
        }
        binding.buttonBondedConnect.setOnClickListener {
            runWithPermissions { viewModel.tryBondedConnect() }
        }

        viewModel.glasses.observe(this@MainActivity) { name ->
            val label = if (name.isBlank()) {
                getString(R.string.live_data_glasses_unknown)
            } else {
                getString(R.string.live_data_glasses_label, name)
            }
            binding.textLiveGlasses.text = label
        }

        viewModel.status.observe(this@MainActivity) { statusText ->
            val label = if (statusText.isBlank()) {
                getString(R.string.live_data_status_unknown)
            } else {
                getString(R.string.live_data_status_label, statusText)
            }
            binding.textLiveStatus.text = label
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderState(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiMessages.collect { message ->
                    val text = when (message) {
                        is ApplicationViewModel.UiMessage.AutoConnectTriggered ->
                            getString(R.string.auto_connect_triggered, message.glassesName)
                        is ApplicationViewModel.UiMessage.AutoConnectFailed ->
                            getString(R.string.auto_connect_failed, message.glassesName)
                        is ApplicationViewModel.UiMessage.Snackbar -> message.text
                    }
                    Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
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

    private fun renderState(state: ApplicationViewModel.State) {
        val serviceStatusText = getString(
            R.string.service_status_format,
            formatServiceStatus(state.serviceStatus)
        )
        binding.textServiceStatus.text = serviceStatusText
        binding.textServiceStatus.setTextColor(
            ContextCompat.getColor(this, serviceStatusColor(state.serviceStatus))
        )

        binding.progressScanning.isVisible = state.scanning

        val errorMessage = state.errorMessage
        binding.textErrorMessage.isVisible = !errorMessage.isNullOrBlank()
        binding.textErrorMessage.text = errorMessage

        val items = state.glasses.orEmpty().map { snapshot ->
            GlassesAdapter.GlassesItem(
                snapshot = snapshot,
                retryCountdown = state.retryCountdowns[snapshot.id],
                retryCount = state.retryCounts[snapshot.id] ?: 0
            )
        }
        glassesAdapter.submitList(items)
        binding.recyclerGlasses.isVisible = items.isNotEmpty()
        binding.textGlassesEmpty.isVisible = items.isEmpty()

        telemetryEntryAdapter.submitList(state.telemetryEntries)
        binding.recyclerTelemetryEntries.isVisible = state.telemetryEntries.isNotEmpty()
        binding.textTelemetryEmpty.isVisible = state.telemetryEntries.isEmpty()

        telemetryLogAdapter.submitList(state.telemetryLogs)
        binding.recyclerTelemetryLogs.isVisible = state.telemetryLogs.isNotEmpty()
        binding.textTelemetryLogsEmpty.isVisible = state.telemetryLogs.isEmpty()

        if (state.serviceStatus == G1ServiceCommon.ServiceStatus.PERMISSION_REQUIRED) {
            if (!autoPromptShown) {
                autoPromptShown = true
                runWithPermissions { }
            }
        } else {
            autoPromptShown = false
        }
    }

    private fun formatServiceStatus(status: G1ServiceCommon.ServiceStatus): String {
        return when (status) {
            G1ServiceCommon.ServiceStatus.READY -> getString(R.string.status_ready)
            G1ServiceCommon.ServiceStatus.LOOKING -> getString(R.string.status_looking)
            G1ServiceCommon.ServiceStatus.LOOKED -> getString(R.string.status_looked)
            G1ServiceCommon.ServiceStatus.PERMISSION_REQUIRED -> getString(R.string.status_permission_required)
            G1ServiceCommon.ServiceStatus.ERROR -> getString(R.string.status_error)
        }
    }

    private fun serviceStatusColor(status: G1ServiceCommon.ServiceStatus): Int {
        return when (status) {
            G1ServiceCommon.ServiceStatus.ERROR -> R.color.telemetry_error
            G1ServiceCommon.ServiceStatus.READY,
            G1ServiceCommon.ServiceStatus.LOOKED -> R.color.telemetry_success
            else -> R.color.telemetry_info
        }
    }

    private fun runWithPermissions(action: () -> Unit) {
        val intent = PermissionHelper.createPermissionIntent(this)
        if (intent == null) {
            action()
            return
        }
        if (pendingPermissionAction == null) {
            pendingPermissionAction = action
            permissionLauncher.launch(intent)
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
