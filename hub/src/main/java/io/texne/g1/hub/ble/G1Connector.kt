package io.texne.g1.hub.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.SparseArray
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceClient
import io.texne.g1.basis.core.BleLogger
import io.texne.g1.basis.core.ensureBond
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
import io.texne.g1.basis.core.G1BLEManager
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult as NordicScanResult
import no.nordicsemi.android.support.v18.scanner.ScanRecord as NordicScanRecord
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

    private val preferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

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
        private const val PREFS_NAME = "g1_connector"
        private const val KEY_LAST_SUCCESSFUL_MAC = "last_successful_mac"
        private const val KEY_KNOWN_NUS_ADDRESSES = "known_nus_addresses"
        private const val KEY_KNOWN_MANUFACTURER_ADDRESSES = "known_manufacturer_addresses"
    }

    suspend fun connectSmart(): Result = withContext(Dispatchers.IO) {
        BleLogger.info(TAG, "connectSmart start")
        if (attachToHub()) {
            BleLogger.info(TAG, "CONNECT_PATH=HUB")
            return@withContext Result.Success
        }

        when (val bonded = tryBondedConnectInternal()) {
            BondedResult.Success -> {
                BleLogger.info(TAG, "CONNECT_PATH=BONDED")
                return@withContext Result.Success
            }
            BondedResult.PermissionMissing -> return@withContext Result.PermissionMissing
            BondedResult.NoDevices -> Unit
        }

        return@withContext when (val scan = scanAndConnect()) {
            ScanOutcome.Success -> Result.Success
            ScanOutcome.PermissionMissing -> {
                BleLogger.warn(TAG, "connectSmart scan blocked by permissions")
                Result.PermissionMissing
            }
            ScanOutcome.NotFound -> {
                BleLogger.warn(TAG, "connectSmart scan path not found")
                Result.NotFound
            }
        }
    }

    suspend fun tryBondedConnect(): Result = withContext(Dispatchers.IO) {
        when (val bonded = tryBondedConnectInternal()) {
            BondedResult.Success -> {
                BleLogger.info(TAG, "CONNECT_PATH=BONDED")
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
            BleLogger.info(TAG, "attachToHub connected=${connected.size}")
            client.close()
            success
        } catch (error: Throwable) {
            BleLogger.warn(TAG, "attachToHub error", error)
            false
        }
    }

    private suspend fun tryBondedConnectInternal(): BondedResult = withContext(Dispatchers.IO) {
        if (!ensurePermission(Manifest.permission.BLUETOOTH_CONNECT, "CONNECT")) {
            return@withContext BondedResult.PermissionMissing
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: run {
            BleLogger.warn(TAG, "Bluetooth adapter unavailable")
            return@withContext BondedResult.NoDevices
        }

        val attemptedAddresses = mutableSetOf<String>()
        val seenAddresses = mutableSetOf<String>()
        readLastSuccessfulMac()?.let { lastAddress ->
            val lastDevice = runCatching { adapter.getRemoteDevice(lastAddress) }.getOrNull()
            if (lastDevice != null) {
                seenAddresses.add(lastDevice.address)
                attemptedAddresses.add(lastDevice.address)
                BleLogger.info(TAG, "Attempt last successful connect to ${lastDevice.address} reason=LAST_SUCCESS")
                if (connectGattOnce(lastDevice)) {
                    return@withContext BondedResult.Success
                }
            }
        }

        val knownNusAddresses = readKnownAddressSet(KEY_KNOWN_NUS_ADDRESSES)
        val knownManufacturerAddresses = readKnownAddressSet(KEY_KNOWN_MANUFACTURER_ADDRESSES)

        val bonded = try {
            adapter.bondedDevices.orEmpty()
        } catch (error: SecurityException) {
            BleLogger.warn(TAG, "Unable to access bonded devices", error)
            emptySet()
        }

        val candidates = bonded.filter { device ->
            if (!seenAddresses.add(device.address)) {
                return@filter false
            }
            val name = device.name.orEmpty()
            val normalizedAddress = normalizedAddress(device.address)
            val recognizedByMetadata = normalizedAddress != null && (
                knownNusAddresses.contains(normalizedAddress) ||
                    knownManufacturerAddresses.contains(normalizedAddress)
                )
            matchesEvenName(name) || recognizedByMetadata
        }

        if (candidates.isEmpty() && attemptedAddresses.isEmpty()) {
            BleLogger.info(TAG, "No bonded Even/G1 devices")
            return@withContext BondedResult.NoDevices
        }

        candidates.forEach { device ->
            attemptedAddresses.add(device.address)
            val normalizedAddress = normalizedAddress(device.address)
            val reasonParts = mutableListOf<String>()
            if (matchesEvenName(device.name.orEmpty())) {
                reasonParts += "NAME"
            }
            if (normalizedAddress != null) {
                if (knownNusAddresses.contains(normalizedAddress)) {
                    reasonParts += "KNOWN_NUS"
                }
                if (knownManufacturerAddresses.contains(normalizedAddress)) {
                    reasonParts += "KNOWN_MFG"
                }
            }
            val reason = reasonParts.joinToString(separator = "+").ifEmpty { "UNKNOWN" }
            BleLogger.info(TAG, "Attempt bonded connect to ${device.name} / ${device.address} reason=$reason")
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

        BleLogger.info(TAG, "Starting broad scan pass")
        when (val broad = runScan(emptyList(), BROAD_SCAN_DURATION_MS)) {
            ScanOutcome.Success -> return ScanOutcome.Success
            ScanOutcome.PermissionMissing -> return broad
            ScanOutcome.NotFound -> Unit
        }

        BleLogger.info(TAG, "Broad scan complete, starting filtered pass")
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
                BleLogger.warn(TAG, "scan failed code=$errorCode")
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
                val flags = record?.advertiseFlags?.let { value -> String.format("0x%02X", value) } ?: "null"
                val serviceUuidSummary = record?.serviceUuids?.joinToString(prefix = "[", postfix = "]") { parcel ->
                    parcel?.uuid?.toString() ?: "null"
                } ?: "[]"
                val manufacturerSummary = record?.manufacturerSpecificData?.let { data ->
                    if (data.size() == 0) {
                        "[]"
                    } else {
                        (0 until data.size()).joinToString(prefix = "[", postfix = "]") { index ->
                            val id = data.keyAt(index)
                            val length = data.valueAt(index)?.size ?: 0
                            String.format("0x%04X(%d)", id, length)
                        }
                    }
                } ?: "[]"
                val reasonsList = mutableListOf<String>()
                if (hasNameMatch) {
                    reasonsList += "NAME"
                }
                if (hasNus) {
                    reasonsList += "NUS"
                }
                if (hasManufacturer) {
                    reasonsList += "MFG"
                }
                val reasons = reasonsList.joinToString(separator = "+").ifEmpty { "UNKNOWN" }
                val rssi = resultItem.rssi
                BleLogger.info(
                    TAG,
                    "SCAN_HIT address=${device.address} name=${advertisedName ?: "unknown"} rssi=$rssi flags=$flags uuids=$serviceUuidSummary manufacturers=$manufacturerSummary reason=$reasons"
                )
                rememberEncounteredDevice(device, hasNus, hasManufacturer)
                if (!processed.compareAndSet(false, true)) {
                    return
                }
                BleLogger.info(
                    TAG,
                    "SCAN_CHOICE address=${device.address} reason=$reasons autoConnect=false timeout=${CONNECT_TIMEOUT_MS}"
                )
                runCatching { scanner.stopScan(this) }
                handler.removeCallbacks(stopRunnable)
                result = if (connectGattOnce(device)) {
                    BleLogger.info(TAG, "CONNECT_PATH=SCAN")
                    ScanOutcome.Success
                } else {
                    ScanOutcome.NotFound
                }
                latch.countDown()
            }
        }

        stopRunnable = Runnable {
            if (processed.compareAndSet(false, true)) {
                BleLogger.info(TAG, "scan window elapsed")
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
            BleLogger.warn(TAG, "Unable to start BLE scan", error)
            ScanOutcome.PermissionMissing
        } catch (error: IllegalStateException) {
            BleLogger.warn(TAG, "BLE scanner unavailable", error)
            ScanOutcome.NotFound
        }
    }

    private fun matchesEvenName(name: String): Boolean {
        val normalized = name.lowercase(Locale.US)
        return MANUFACTURER_KEYWORDS.any { normalized.contains(it) }
    }

    private fun List<ParcelUuid>?.containsNusServiceUuid(): Boolean {
        if (this == null) {
            return false
        }
        return any { parcelUuid -> parcelUuid?.uuid == NUS_UUID }
    }

    private fun SparseArray<ByteArray>?.hasEvenManufacturerPayload(): Boolean {
        val data = this ?: return false
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

    private fun ScanRecord?.hasNusService(): Boolean =
        this?.serviceUuids.containsNusServiceUuid()

    private fun NordicScanRecord?.hasNusService(): Boolean =
        this?.serviceUuids.containsNusServiceUuid()

    private fun ScanRecord?.hasEvenManufacturerData(): Boolean =
        this?.manufacturerSpecificData.hasEvenManufacturerPayload()

    private fun NordicScanRecord?.hasEvenManufacturerData(): Boolean =
        this?.manufacturerSpecificData.hasEvenManufacturerPayload()

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
        if (!ensureBond(context, device, BOND_TIMEOUT_MS, TAG)) {
            BleLogger.warn(TAG, "Unable to bond with ${device.address}")
            return false
        }

        val manager = G1BLEManager.getOrCreate(device, context)
        var allowBondRecovery = true

        while (true) {
            val latch = CountDownLatch(1)
            val success = AtomicBoolean(false)
            var failureStatus: Int? = null

            val request = try {
                manager.connectIfNeeded(device)
            } catch (error: Throwable) {
                BleLogger.warn(TAG, "connectIfNeeded error", error)
                null
            }

            if (request == null) {
                BleLogger.info(TAG, "Reusing existing GATT for ${device.address}")
                success.set(true)
                manager.currentGatt()?.let { G1BLEManager.attach(it) }
            } else {
                BleLogger.info(TAG, "Issuing connect request for ${device.address} autoConnect=false timeout=${CONNECT_TIMEOUT_MS}")
                request
                    .useAutoConnect(false)
                    .timeout(CONNECT_TIMEOUT_MS.toLong())
                    .fail { _, status ->
                        failureStatus = status
                        BleLogger.warn(TAG, "GATT connect failed for ${device.address} (status=$status)")
                        success.set(false)
                        latch.countDown()
                    }
                    .invalid {
                        BleLogger.warn(TAG, "GATT connect request invalid for ${device.address}")
                        success.set(false)
                        latch.countDown()
                    }
                    .done {
                        manager.currentGatt()?.let { G1BLEManager.attach(it) }
                        success.set(true)
                        latch.countDown()
                    }
                    .enqueue()

                val awaited = latch.await(CONNECT_TIMEOUT_MS + 2000L, TimeUnit.MILLISECONDS)
                if (!awaited) {
                    BleLogger.warn(TAG, "connectGattOnce timeout for ${device.address}")
                    success.set(false)
                    runCatching { request.cancelPendingConnection() }
                }
            }

            val connected = success.get()
            if (connected) {
                rememberSuccessfulConnection(device)
                return true
            }

            val status = failureStatus
            if (allowBondRecovery && status.isInsufficientAuthentication()) {
                allowBondRecovery = false
                BleLogger.info(TAG, "Recovering from insufficient authentication for ${device.address} (status=$status)")
                val bonded = ensureBond(context, device, BOND_TIMEOUT_MS, TAG)
                if (bonded) {
                    runCatching { manager.disconnect().enqueue() }
                        .onFailure {
                            BleLogger.warn(TAG, "disconnect after auth failure error for ${device.address}", it)
                        }
                    continue
                } else {
                    BleLogger.warn(TAG, "Bond recovery failed for ${device.address}")
                }
            }

            BleLogger.warn(TAG, "connectGattOnce giving up for ${device.address}")
            return false
        }
    }

    private fun Int?.isInsufficientAuthentication(): Boolean {
        if (this == null) {
            return false
        }
        return this == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
            this == BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION
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
            BleLogger.info(TAG, "Permission $marker bypassed on legacy API")
            return true
        }
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            BleLogger.info(TAG, "Permission $marker granted")
        } else {
            BleLogger.warn(TAG, "Permission $marker missing")
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

    private fun rememberEncounteredDevice(
        device: BluetoothDevice,
        hasNus: Boolean,
        hasManufacturer: Boolean
    ) {
        val address = normalizedAddress(device.address) ?: return
        BleLogger.debug(TAG, "Encountered ${device.address} nus=$hasNus manufacturer=$hasManufacturer")
        if (hasNus) {
            updateKnownAddressSet(KEY_KNOWN_NUS_ADDRESSES, address)
        }
        if (hasManufacturer) {
            updateKnownAddressSet(KEY_KNOWN_MANUFACTURER_ADDRESSES, address)
        }
    }

    private fun rememberSuccessfulConnection(device: BluetoothDevice) {
        val address = normalizedAddress(device.address) ?: return
        BleLogger.info(TAG, "Recording successful connection for ${device.address}")
        preferences.edit {
            putString(KEY_LAST_SUCCESSFUL_MAC, address)
        }
        updateKnownAddressSet(KEY_KNOWN_NUS_ADDRESSES, address)
    }

    private fun readLastSuccessfulMac(): String? =
        preferences.getString(KEY_LAST_SUCCESSFUL_MAC, null)

    private fun readKnownAddressSet(key: String): Set<String> =
        preferences.getStringSet(key, emptySet())?.toSet().orEmpty()

    private fun updateKnownAddressSet(key: String, address: String) {
        val existing = preferences.getStringSet(key, emptySet()) ?: emptySet()
        if (existing.contains(address)) {
            return
        }
        preferences.edit {
            putStringSet(key, existing.toMutableSet().apply { add(address) })
        }
    }

    private fun normalizedAddress(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return raw.uppercase(Locale.US)
    }
}
