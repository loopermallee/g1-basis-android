package io.texne.g1.hub.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceClient
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.text.Charsets
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult as NordicScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings

/**
 * Implements a three step connection ladder:
 * 1) Attach to the hub/service if an active connection already exists.
 * 2) Attempt a bonded connection without scanning.
 * 3) Fallback to a broad scan followed by a filtered pass.
 */
@Singleton
class G1Connector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    sealed class Result {
        data object Success : Result()
        data object PermissionMissing : Result()
        data object NotFound : Result()
    }

    companion object {
        private const val TAG = "G1Connector"
        private const val BROAD_SCAN_DURATION_MS = 7_000L
        private const val STRICT_SCAN_DURATION_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val BOND_TIMEOUT_MS = 20_000L
        private val NUS_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val MANUFACTURER_KEYWORDS = listOf("even", "g1")
        private val KNOWN_MANUFACTURER_IDS = emptySet<Int>()
    }

    suspend fun connectSmart(): Result = withContext(Dispatchers.IO) {
        if (attachToHub()) {
            Log.i(TAG, "CONNECT_PATH=HUB")
            return@withContext Result.Success
        }

        when (val bonded = tryBondedConnectInternal()) {
            BondedResult.Success -> {
                Log.i(TAG, "CONNECT_PATH=BONDED")
                return@withContext Result.Success
            }
            BondedResult.PermissionMissing -> return@withContext Result.PermissionMissing
            BondedResult.NoDevices -> Unit
        }

        return@withContext when (val scan = scanAndConnect()) {
            ScanOutcome.Success -> Result.Success
            ScanOutcome.PermissionMissing -> Result.PermissionMissing
            ScanOutcome.NotFound -> Result.NotFound
        }
    }

    suspend fun tryBondedConnect(): Result = withContext(Dispatchers.IO) {
        when (val bonded = tryBondedConnectInternal()) {
            BondedResult.Success -> {
                Log.i(TAG, "CONNECT_PATH=BONDED")
                Result.Success
            }
            BondedResult.PermissionMissing -> Result.PermissionMissing
            BondedResult.NoDevices -> Result.NotFound
        }
    }

    private fun attachToHub(): Boolean {
        return try {
            val client = G1ServiceClient.open(context) ?: return false
            val connected = runCatching { client.listConnectedGlasses() }
                .getOrDefault(emptyList())
            val success = connected.isNotEmpty()
            Log.i(TAG, "attachToHub connected=${connected.size}")
            client.close()
            success
        } catch (error: Throwable) {
            Log.w(TAG, "attachToHub error", error)
            false
        }
    }

    private suspend fun tryBondedConnectInternal(): BondedResult = withContext(Dispatchers.IO) {
        if (!ensurePermission(Manifest.permission.BLUETOOTH_CONNECT, "CONNECT")) {
            return@withContext BondedResult.PermissionMissing
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        val bonded = adapter?.bondedDevices.orEmpty().filter { device ->
            val name = device.name.orEmpty()
            name.contains("Even", ignoreCase = true) || name.contains("G1", ignoreCase = true)
        }
        if (bonded.isEmpty()) {
            Log.i(TAG, "No bonded Even/G1 devices")
            return@withContext BondedResult.NoDevices
        }

        bonded.forEach { device ->
            Log.i(TAG, "Attempt bonded connect to ${device.name} / ${device.address}")
            if (connectGattOnce(device)) {
                return@withContext BondedResult.Success
            }
        }
        BondedResult.NoDevices
    }

    private fun scanAndConnect(): ScanOutcome {
        if (ensureScanPermissions() == ScanPermission.Missing) {
            return ScanOutcome.PermissionMissing
        }

        Log.i(TAG, "Starting broad scan pass")
        when (val broad = runScan(emptyList(), BROAD_SCAN_DURATION_MS)) {
            ScanOutcome.Success -> return ScanOutcome.Success
            ScanOutcome.PermissionMissing -> return broad
            ScanOutcome.NotFound -> Unit
        }

        Log.i(TAG, "Broad scan complete, starting filtered pass")
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_UUID))
            .build()
        return runScan(listOf(filter), STRICT_SCAN_DURATION_MS)
    }

    private fun runScan(filters: List<ScanFilter>, durationMs: Long): ScanOutcome {
        if (ensureScanPermissions() == ScanPermission.Missing) {
            return ScanOutcome.PermissionMissing
        }
        val scanner = BluetoothLeScannerCompat.getScanner()
        val settings = ScanSettings.Builder()
            .setLegacy(false)
            .setReportDelay(0)
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val processed = AtomicBoolean(false)
        var result: ScanOutcome = ScanOutcome.NotFound

        lateinit var stopRunnable: Runnable

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scanResult: NordicScanResult) {
                handleResult(scanResult)
            }

            override fun onBatchScanResults(results: MutableList<NordicScanResult>) {
                results.forEach(::handleResult)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed code=$errorCode")
                if (processed.compareAndSet(false, true)) {
                    result = ScanOutcome.NotFound
                    latch.countDown()
                }
            }

            private fun handleResult(resultItem: NordicScanResult) {
                val device = resultItem.device ?: return
                val record = resultItem.scanRecord
                val advertisedName = record?.deviceName ?: device.name
                val hasNameMatch = advertisedName?.let { matchesEvenName(it) } == true
                val hasNus = record.hasNusService()
                val hasManufacturer = record.hasEvenManufacturerData()
                if (!hasNameMatch && !hasNus && !hasManufacturer) {
                    return
                }
                if (!processed.compareAndSet(false, true)) {
                    return
                }
                val label = advertisedName ?: "unknown"
                Log.i(TAG, "scan hit ${label} / ${device.address}")
                runCatching { scanner.stopScan(this) }
                handler.removeCallbacks(stopRunnable)
                result = if (connectGattOnce(device)) {
                    Log.i(TAG, "CONNECT_PATH=SCAN")
                    ScanOutcome.Success
                } else {
                    ScanOutcome.NotFound
                }
                latch.countDown()
            }
        }

        stopRunnable = Runnable {
            if (processed.compareAndSet(false, true)) {
                Log.i(TAG, "scan window elapsed")
                runCatching { scanner.stopScan(callback) }
                result = ScanOutcome.NotFound
                latch.countDown()
            }
        }

        return try {
            scanner.startScan(filters, settings, callback)
            handler.postDelayed(stopRunnable, durationMs)
            latch.await(durationMs + CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            handler.removeCallbacks(stopRunnable)
            runCatching { scanner.stopScan(callback) }
            result
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to start BLE scan", error)
            ScanOutcome.PermissionMissing
        } catch (error: IllegalStateException) {
            Log.w(TAG, "BLE scanner unavailable", error)
            ScanOutcome.NotFound
        }
    }

    private fun matchesEvenName(name: String): Boolean {
        val normalized = name.lowercase(Locale.US)
        return MANUFACTURER_KEYWORDS.any { normalized.contains(it) }
    }

    private fun ScanRecord?.hasNusService(): Boolean {
        val uuids = this?.serviceUuids ?: return false
        return uuids.any { parcelUuid -> parcelUuid?.uuid == NUS_UUID }
    }

    private fun ScanRecord?.hasEvenManufacturerData(): Boolean {
        val data = this?.manufacturerSpecificData ?: return false
        for (index in 0 until data.size()) {
            val companyId = data.keyAt(index)
            val payload = data.valueAt(index) ?: continue
            if (payload.isEmpty()) {
                continue
            }
            val keywordMatch = MANUFACTURER_KEYWORDS.any { keyword ->
                payload.containsKeyword(keyword)
            }
            if (keywordMatch || KNOWN_MANUFACTURER_IDS.contains(companyId)) {
                return true
            }
        }
        return false
    }

    private fun ByteArray.containsKeyword(keyword: String): Boolean {
        if (keyword.isEmpty()) {
            return false
        }
        val text = try {
            String(this, Charsets.ISO_8859_1)
        } catch (error: Throwable) {
            return false
        }
        return text.contains(keyword, ignoreCase = true)
    }

    @SuppressLint("MissingPermission")
    private fun connectGattOnce(device: BluetoothDevice): Boolean {
        if (!ensurePermission(Manifest.permission.BLUETOOTH_CONNECT, "CONNECT")) {
            return false
        }
        if (!ensureBond(device)) {
            Log.w(TAG, "Unable to bond with ${device.address}")
            return false
        }
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
                success = status == BluetoothGatt.GATT_SUCCESS && nus != null
                if (completed.compareAndSet(false, true)) {
                    latch.countDown()
                }
            }
        }

        return try {
            device.connectGatt(context, /* autoConnect = */ false, callback)
            val awaited = latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!awaited) {
                Log.w(TAG, "connectGattOnce timeout for ${device.address}")
            }
            success
        } catch (error: Throwable) {
            Log.w(TAG, "connectGattOnce error", error)
            false
        }
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    private fun ensureBond(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return true
        }

        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val bonded = AtomicBoolean(false)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                if (target.address != device.address) {
                    return
                }

                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                Log.i(TAG, "Bond state change for ${device.address}: state=$state previous=$previous")
                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        bonded.set(true)
                        if (completed.compareAndSet(false, true)) {
                            latch.countDown()
                        }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        if (completed.compareAndSet(false, true)) {
                            latch.countDown()
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }

        return try {
            val initialState = device.bondState
            if (initialState == BluetoothDevice.BOND_BONDED) {
                bonded.set(true)
                return true
            }

            val shouldInitiateBond = initialState != BluetoothDevice.BOND_BONDING
            if (shouldInitiateBond && !startBond(device)) {
                return false
            }

            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                bonded.set(true)
                return true
            }

            val awaited = latch.await(BOND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!awaited) {
                Log.w(TAG, "Bond timeout for ${device.address}")
            }
            bonded.get() || device.bondState == BluetoothDevice.BOND_BONDED
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBond(device: BluetoothDevice): Boolean {
        val insecureStarted = try {
            val method = device.javaClass.getMethod("createBondInsecure")
            val result = method.invoke(device)
            val started = (result as? Boolean) == true
            if (started) {
                Log.i(TAG, "createBondInsecure() initiated for ${device.address}")
            } else {
                Log.i(TAG, "createBondInsecure() returned false for ${device.address}")
            }
            started
        } catch (error: NoSuchMethodException) {
            Log.i(TAG, "createBondInsecure unavailable for ${device.address}")
            false
        } catch (error: Throwable) {
            Log.w(TAG, "createBondInsecure error for ${device.address}", error)
            false
        }
        if (insecureStarted) {
            return true
        }

        return try {
            val started = device.createBond()
            if (!started) {
                Log.w(TAG, "createBond() returned false for ${device.address}")
            } else {
                Log.i(TAG, "createBond() initiated for ${device.address}")
            }
            started
        } catch (error: Throwable) {
            Log.w(TAG, "createBond() error for ${device.address}", error)
            false
        }
    }

    private fun ensureScanPermissions(): ScanPermission {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ensurePermission(Manifest.permission.BLUETOOTH_SCAN, "SCAN")
            val connect = ensurePermission(Manifest.permission.BLUETOOTH_CONNECT, "CONNECT")
            if (scan && connect) ScanPermission.Granted else ScanPermission.Missing
        } else {
            if (ensurePermission(Manifest.permission.ACCESS_FINE_LOCATION, "SCAN")) {
                ScanPermission.Granted
            } else {
                ScanPermission.Missing
            }
        }
    }

    private fun ensurePermission(permission: String, marker: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            (permission == Manifest.permission.BLUETOOTH_CONNECT || permission == Manifest.permission.BLUETOOTH_SCAN)
        ) {
            return true
        }
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "PERM_MISSING=$marker")
        }
        return granted
    }

    private enum class ScanPermission { Granted, Missing }

    private sealed class BondedResult {
        data object Success : BondedResult()
        data object NoDevices : BondedResult()
        data object PermissionMissing : BondedResult()
    }

    private sealed class ScanOutcome {
        data object Success : ScanOutcome()
        data object NotFound : ScanOutcome()
        data object PermissionMissing : ScanOutcome()
    }
}
