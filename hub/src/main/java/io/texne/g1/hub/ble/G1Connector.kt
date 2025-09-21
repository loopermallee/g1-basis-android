package io.texne.g1.hub.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import io.texne.g1.basis.client.G1ServiceClient
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings

/**
 * Connection ladder:
 * A) Use Hub/Service connection if present
 * B) Try bonded connect (no scan)
 * C) Fallback to scan (broad first, then filtered)
 */
class G1Connector(private val ctx: Context) {

    companion object {
        private const val TAG = "G1Connector"
        private val NUS_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private const val SCAN_PASS_DURATION_MS = 7_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
    }

    /** Public entry: try the smart ladder */
    suspend fun connectSmart(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "connectSmart started")
        // A) Attach to Hub/Service if already connected
        if (attachToHub()) {
            Log.i(TAG, "CONNECT_PATH=HUB")
            return@withContext true
        }

        // B) Bonded connect (no scan)
        if (tryBondedConnectInternal()) {
            Log.i(TAG, "CONNECT_PATH=BONDED")
            return@withContext true
        }

        // C) Scan fallback
        return@withContext scanAndConnect()
    }

    // ===== A) Attach to Hub/Service ============================================================

    private fun attachToHub(): Boolean {
        return try {
            val client = G1ServiceClient.open(ctx) ?: return false
            val connected = runCatching { client.listConnectedGlasses() }.getOrDefault(emptyList())
            val ok = connected.isNotEmpty()
            Log.i(TAG, "attachToHub connected=${connected.size}")
            client.close()
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "attachToHub error", t)
            false
        }
    }

    // ===== B) Bonded connect (no scan) =========================================================

    @SuppressLint("MissingPermission")
    private fun tryBondedConnectInternal(): Boolean {
        if (!ensurePermission(Manifest.permission.BLUETOOTH_CONNECT, "CONNECT")) {
            return false
        }
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val adapter = mgr.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return false
        val bonded = adapter.bondedDevices.orEmpty().filter { d ->
            val n = d.name ?: ""
            n.contains("Even", true) || n.contains("G1", true)
        }
        if (bonded.isEmpty()) {
            Log.i(TAG, "No bonded Even/G1 devices")
            return false
        }

        bonded.forEach { dev ->
            Log.i(TAG, "Attempt bonded connect to ${dev.name} / ${dev.address}")
            val ok = connectGattOnce(dev)
            if (ok) {
                return true
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    fun tryBondedConnect(): Boolean {
        return tryBondedConnectInternal().also { success ->
            if (success) {
                Log.i(TAG, "CONNECT_PATH=BONDED")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGattOnce(dev: BluetoothDevice): Boolean {
        var success = false
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.i(TAG, "onConnectionStateChange status=$status state=$newState")
                if (status != BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w(TAG, "GATT_STATUS=$status")
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.requestMtu(185)
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (completed.compareAndSet(false, true)) {
                        runCatching { gatt.close() }
                        latch.countDown()
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "MTU=$mtu status=$status")
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val nus = gatt.getService(NUS_UUID)
                Log.i(TAG, "NUS present? ${nus != null}")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "GATT_STATUS=$status")
                }
                success = (status == BluetoothGatt.GATT_SUCCESS && nus != null)
                if (completed.compareAndSet(false, true)) {
                    latch.countDown()
                }
            }
        }

        try {
            dev.connectGatt(ctx, /* autoConnect */ false, callback)
            val awaited = latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!awaited) {
                Log.w(TAG, "connectGattOnce timeout for ${dev.address}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "connectGattOnce error", t)
        }
        return success
    }

    // ===== C) Scan fallback ====================================================================

    private fun scanAndConnect(): Boolean {
        if (!ensureScanPermissions()) {
            return false
        }
        Log.i(TAG, "Starting scanAndConnect broad pass")
        if (runScan(emptyList())) {
            Log.i(TAG, "CONNECT_PATH=SCAN")
            return true
        }
        Log.i(TAG, "Broad scan finished, starting filtered pass")
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_UUID))
            .build()
        val filteredSuccess = runScan(listOf(filter))
        if (filteredSuccess) {
            Log.i(TAG, "CONNECT_PATH=SCAN")
        }
        return filteredSuccess
    }

    private fun runScan(filters: List<ScanFilter>): Boolean {
        val scanner = BluetoothLeScannerCompat.getScanner()
        val settings = ScanSettings.Builder()
            .setLegacy(false)
            .setReportDelay(0)
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val processed = AtomicBoolean(false)
        var success = false

        lateinit var stopRunnable: Runnable

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handleResult)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed code=$errorCode")
                if (processed.compareAndSet(false, true)) {
                    latch.countDown()
                }
            }

            private fun handleResult(result: ScanResult) {
                val device = result.device
                val name = device?.name ?: result.scanRecord?.deviceName ?: return
                if (!name.contains("Even", true) && !name.contains("G1", true)) {
                    return
                }
                if (!processed.compareAndSet(false, true)) {
                    return
                }
                Log.i(TAG, "scan hit ${name} / ${device.address}")
                runCatching { scanner.stopScan(this) }
                handler.removeCallbacks(stopRunnable)
                success = connectGattOnce(device)
                latch.countDown()
            }
        }

        stopRunnable = Runnable {
            if (processed.compareAndSet(false, true)) {
                Log.i(TAG, "scan window elapsed")
                runCatching { scanner.stopScan(callback) }
                latch.countDown()
            }
        }

        return try {
            scanner.startScan(filters, settings, callback)
            handler.postDelayed(stopRunnable, SCAN_PASS_DURATION_MS)
            val awaited = latch.await(SCAN_PASS_DURATION_MS + CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!awaited) {
                Log.w(TAG, "scan latch timeout")
            }
            handler.removeCallbacks(stopRunnable)
            runCatching { scanner.stopScan(callback) }
            success
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to start BLE scan", error)
            false
        } catch (error: IllegalStateException) {
            Log.w(TAG, "BLE scanner unavailable", error)
            false
        }
    }

    // ===== Permissions helper ==================================================================

    private fun ensureScanPermissions(): Boolean {
        var ok = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ok = ok && ensurePermission(Manifest.permission.BLUETOOTH_SCAN, "SCAN")
            ok = ok && ensurePermission(Manifest.permission.BLUETOOTH_CONNECT, "CONNECT")
        } else {
            ok = ok && ensurePermission(Manifest.permission.ACCESS_FINE_LOCATION, "SCAN")
        }
        return ok
    }

    private fun ensurePermission(permission: String, marker: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            (permission == Manifest.permission.BLUETOOTH_CONNECT || permission == Manifest.permission.BLUETOOTH_SCAN)
        ) {
            return true
        }
        val granted = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "PERM_MISSING=$marker")
        }
        return granted
    }
}
