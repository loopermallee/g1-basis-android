package io.texne.g1.basis.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.util.SparseArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import no.nordicsemi.android.support.v18.scanner.ScanRecord as NordicScanRecord
import java.util.Locale
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.text.Charsets

@OptIn(DelicateCoroutinesApi::class)
internal fun <T1, T2, R> StateFlow<T1>.combineState(
    flow2: StateFlow<T2>,
    scope: CoroutineScope = GlobalScope,
    sharingStarted: SharingStarted = SharingStarted.Eagerly,
    transform: (T1, T2) -> R
): StateFlow<R> = this.combine(flow2) {
        o1, o2 -> transform.invoke(o1, o2)
}.stateIn(scope, sharingStarted, transform.invoke(this.value, flow2.value))

private const val MAX_CONNECTION_ATTEMPTS = 3

class G1 {

    // internals -----------------------------------------------------------------------------------

    private val right: G1Device
    private val left: G1Device
    private val writableGestures = MutableSharedFlow<G1Gesture>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val gestures = writableGestures.asSharedFlow()
    private var gesturesJob: Job? = null

    // externally visible --------------------------------------------------------------------------

    val id: String
    val name: String

    enum class ConnectionState { UNINITIALIZED, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }
    data class State(
        val connectionState: ConnectionState,
        val batteryPercentage: Int?,
        val leftConnectionState: ConnectionState,
        val rightConnectionState: ConnectionState,
        val leftBatteryPercentage: Int?,
        val rightBatteryPercentage: Int?
    )
    val state: StateFlow<State>
    private var currentState: State? = null

    // construction --------------------------------------------------------------------------------

    private constructor(right: G1Device, left: G1Device) {
        val splitL = left.name.split("_")
        this.id = "${left.address}${right.address}".filter { it != ':' }
        this.name = "${splitL[0]}.${splitL[1]}"
        this.right = right
        this.left = left
        this.state = left.state.combineState(right.state) { l, r ->
            val lConnectionState = l.connectionState
            val rConnectionState = r.connectionState
            val connectionState = when {
                lConnectionState == ConnectionState.CONNECTED && rConnectionState == ConnectionState.CONNECTED -> ConnectionState.CONNECTED
                lConnectionState == ConnectionState.DISCONNECTING || rConnectionState == ConnectionState.DISCONNECTING -> ConnectionState.DISCONNECTING
                lConnectionState == ConnectionState.DISCONNECTED && rConnectionState == ConnectionState.CONNECTED -> ConnectionState.DISCONNECTING
                lConnectionState == ConnectionState.CONNECTED && rConnectionState == ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTING
                lConnectionState == ConnectionState.CONNECTING || rConnectionState == ConnectionState.CONNECTING -> ConnectionState.CONNECTING
                lConnectionState == ConnectionState.DISCONNECTED && rConnectionState == ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
                lConnectionState == ConnectionState.ERROR || rConnectionState == ConnectionState.ERROR -> ConnectionState.ERROR
                else -> ConnectionState.UNINITIALIZED
            }

            val leftBatteryPercentage = l.batteryPercentage
            val rightBatteryPercentage = r.batteryPercentage
            val batteryPercentage = if(leftBatteryPercentage == null || rightBatteryPercentage == null) null else leftBatteryPercentage.coerceAtMost(
                rightBatteryPercentage
            )

            val current = currentState
            if(
                current != null &&
                connectionState == current.connectionState &&
                batteryPercentage == current.batteryPercentage &&
                lConnectionState == current.leftConnectionState &&
                rConnectionState == current.rightConnectionState &&
                leftBatteryPercentage == current.leftBatteryPercentage &&
                rightBatteryPercentage == current.rightBatteryPercentage
            ) {
                current
            } else {
                val newState = State(
                    connectionState = connectionState,
                    batteryPercentage = batteryPercentage,
                    leftConnectionState = lConnectionState,
                    rightConnectionState = rConnectionState,
                    leftBatteryPercentage = leftBatteryPercentage,
                    rightBatteryPercentage = rightBatteryPercentage
                )
                Log.d("G1", "G1_STATE - composing - ${l} and ${r} = ${newState}")
                currentState = newState
                newState
            }
        }
    }

    // connect / disconnect ------------------------------------------------------------------------

    suspend fun connect(
        context: Context,
        scope: CoroutineScope,
        maxAttempts: Int = MAX_CONNECTION_ATTEMPTS
    ) = coroutineScope<Boolean> {
        if(state.value.connectionState == ConnectionState.CONNECTED) {
            return@coroutineScope true
        }

        var attempt = 1
        while (attempt <= maxAttempts) {
            val results = awaitAll(
                async { left.connect(context, scope) },
                async { right.connect(context, scope) }
            )

            val leftResult = results.getOrNull(0) == true
            val rightResult = results.getOrNull(1) == true
            if (leftResult && rightResult) {
                if(gesturesJob?.isActive != true) {
                    gesturesJob = scope.launch {
                        merge(left.gestures, right.gestures).collect { gesture ->
                            writableGestures.emit(gesture)
                        }
                    }
                }
                return@coroutineScope true
            }

            Log.w(
                "G1",
                "Connection attempt $attempt failed for $name (left=$leftResult, right=$rightResult)"
            )

            awaitAll(
                async {
                    try {
                        left.disconnect()
                    } catch (error: Throwable) {
                        Log.w("G1", "Left disconnect cleanup failed for $name", error)
                    }
                },
                async {
                    try {
                        right.disconnect()
                    } catch (error: Throwable) {
                        Log.w("G1", "Right disconnect cleanup failed for $name", error)
                    }
                }
            )

            if(attempt < maxAttempts) {
                Log.i(
                    "G1",
                    "Retrying connection for $name (${attempt + 1} of $maxAttempts)"
                )
            }
            attempt += 1
        }

        false
    }

    suspend fun disconnect() {
        left.disconnect()
        right.disconnect()
        gesturesJob?.cancel()
        gesturesJob = null
    }

    // requests ------------------------------------------------------------------------------------

    suspend fun displayTextPage(page: List<String>): Boolean {
        val singleStringPage = page.joinToString("\n")
        if(left.sendRequestForResponse(
                SendTextPacket(
                    singleStringPage,
                    1,
                    1
                )
            ) == null) {
            return false
        }
        if(right.sendRequestForResponse(
                SendTextPacket(
                    singleStringPage,
                    1,
                    1
                )
            ) == null) {
            return false
        }
        return true
    }

    suspend fun stopDisplaying(): Boolean {
        left.sendRequest(ExitRequestPacket())
        right.sendRequest(ExitRequestPacket())
        return true
    }

    fun initialAverageRssi(): Int? {
        val values = listOfNotNull(left.rssi, right.rssi)
        if (values.isEmpty()) {
            return null
        }
        val average = values.sum().toDouble() / values.size.toDouble()
        return average.roundToInt()
    }

    fun initialSignalStrength(): Int? = initialAverageRssi()?.let { rssi ->
        when {
            rssi >= -50 -> 4
            rssi >= -60 -> 3
            rssi >= -70 -> 2
            rssi >= -80 -> 1
            else -> 0
        }
    }

    // find devices --------------------------------------------------------------------------------

    companion object {
        private val NUS_UUID: UUID = UUID.fromString(UART_SERVICE_UUID)
        private val NUS_PARCEL_UUID: ParcelUuid = ParcelUuid(NUS_UUID)
        private val BROAD_SCAN_DURATION = 7.seconds
        private val STRICT_SCAN_DURATION = 8.seconds
        private val MANUFACTURER_KEYWORDS = listOf("even", "g1")
        private val KNOWN_MANUFACTURER_IDS = emptySet<Int>()

        private data class ScanPass(
            val filters: List<ScanFilter>,
            val duration: Duration
        )

        private data class ManufacturerDataMatch(
            val companyId: Int,
            val payload: ByteArray
        )

        internal data class DeviceCandidate(
            val device: BluetoothDevice,
            val identifier: String,
            val side: G1Gesture.Side?,
            val rssi: Int?,
            val advertisedName: String? = null
        )

        internal data class FoundPair(
            val left: DeviceCandidate? = null,
            val right: DeviceCandidate? = null
        )

        internal data class G1DevicePair(
            val identifier: String,
            val left: DeviceCandidate,
            val right: DeviceCandidate
        )

        private fun List<ParcelUuid>?.containsNusServiceUuid(): Boolean {
            if (this == null) {
                return false
            }
            return any { parcelUuid -> parcelUuid?.uuid == NUS_UUID }
        }

        private fun SparseArray<ByteArray>?.firstManufacturerMatch(): ManufacturerDataMatch? {
            val sparseArray = this ?: return null
            for (index in 0 until sparseArray.size()) {
                val companyId = sparseArray.keyAt(index)
                val payload = sparseArray.valueAt(index) ?: continue
                if (payload.isEmpty()) {
                    continue
                }
                val keywordMatch = MANUFACTURER_KEYWORDS.any { keyword ->
                    payload.containsKeyword(keyword)
                }
                if (keywordMatch || KNOWN_MANUFACTURER_IDS.contains(companyId)) {
                    return ManufacturerDataMatch(companyId, payload)
                }
            }
            return null
        }

        private fun ScanRecord?.hasNusService(): Boolean =
            this?.serviceUuids.containsNusServiceUuid()

        private fun NordicScanRecord?.hasNusService(): Boolean =
            this?.serviceUuids.containsNusServiceUuid()

        private fun ScanRecord?.findManufacturerMatch(): ManufacturerDataMatch? =
            this?.manufacturerSpecificData.firstManufacturerMatch()

        private fun NordicScanRecord?.findManufacturerMatch(): ManufacturerDataMatch? =
            this?.manufacturerSpecificData.firstManufacturerMatch()

        private fun ManufacturerDataMatch.identifier(address: String): String? {
            val base = addressPairIdentifier(address) ?: return null
            val prefixLength = minOf(payload.size, 6)
            val prefix = if (prefixLength > 0) {
                payload.copyOfRange(0, prefixLength)
            } else {
                ByteArray(0)
            }
            val payloadHex = if (prefix.isNotEmpty()) {
                prefix.toHexString()
            } else {
                payload.toHexString()
            }
            return "${base}_mfg_${companyId.toString(16).padStart(4, '0')}_${payloadHex}"
        }

        private fun ByteArray.containsKeyword(keyword: String): Boolean {
            if (keyword.isEmpty()) {
                return false
            }
            val candidate = try {
                String(this, Charsets.ISO_8859_1)
            } catch (error: Throwable) {
                return false
            }
            return candidate.contains(keyword, ignoreCase = true)
        }

        private fun ByteArray.toHexString(): String =
            joinToString(separator = "") { byte ->
                byte.toInt().and(0xFF).toString(16).padStart(2, '0')
            }

        private fun addressPairIdentifier(address: String): String? {
            val sanitized = address.uppercase(Locale.US).filter { it.isLetterOrDigit() }
            if (sanitized.length <= 2) {
                return null
            }
            return sanitized.dropLast(2)
        }

        private fun buildSidePreference(sideFilter: Set<G1Gesture.Side>?): List<G1Gesture.Side> {
            if (sideFilter.isNullOrEmpty()) {
                return listOf(G1Gesture.Side.LEFT, G1Gesture.Side.RIGHT)
            }
            val preferred = sideFilter.toMutableList()
            preferred.addAll(G1Gesture.Side.entries.filter { it !in sideFilter })
            return preferred
        }

        private fun DeviceCandidate.withSide(side: G1Gesture.Side): DeviceCandidate {
            return if (this.side == side) {
                this
            } else {
                copy(side = side)
            }
        }

        private fun mergeCandidate(
            existing: FoundPair?,
            candidate: DeviceCandidate,
            sidePreference: List<G1Gesture.Side>
        ): FoundPair {
            val explicitSide = candidate.side
            return when (explicitSide) {
                G1Gesture.Side.LEFT -> FoundPair(candidate, existing?.right)
                G1Gesture.Side.RIGHT -> FoundPair(existing?.left, candidate)
                null -> {
                    val existingLeft = existing?.left
                    val existingRight = existing?.right
                    when {
                        existingLeft == null && existingRight == null -> {
                            val preferred = sidePreference.firstOrNull() ?: G1Gesture.Side.LEFT
                            if (preferred == G1Gesture.Side.LEFT) {
                                FoundPair(candidate.withSide(G1Gesture.Side.LEFT), null)
                            } else {
                                FoundPair(null, candidate.withSide(G1Gesture.Side.RIGHT))
                            }
                        }
                        existingLeft == null -> FoundPair(candidate.withSide(G1Gesture.Side.LEFT), existingRight)
                        existingRight == null -> FoundPair(existingLeft, candidate.withSide(G1Gesture.Side.RIGHT))
                        else -> existing
                    }
                }
            }
        }

        private fun buildScanPasses(totalDuration: Duration): ArrayDeque<ScanPass> {
            val passes = ArrayDeque<ScanPass>()
            val broadDuration = if (totalDuration < BROAD_SCAN_DURATION) totalDuration else BROAD_SCAN_DURATION
            if (broadDuration > Duration.ZERO) {
                passes.add(ScanPass(emptyList(), broadDuration))
            }

            val remaining = totalDuration - broadDuration
            if (remaining > Duration.ZERO) {
                val strictDuration = if (remaining < STRICT_SCAN_DURATION) remaining else STRICT_SCAN_DURATION
                if (strictDuration > Duration.ZERO) {
                    val filter = ScanFilter.Builder()
                        .setServiceUuid(NUS_PARCEL_UUID)
                        .build()
                    passes.add(ScanPass(listOf(filter), strictDuration))
                }
            }

            return passes
        }

        private fun pairingIdentifier(name: String?, address: String): String? {
            val value = name ?: return null
            if (!isRecognizedDeviceName(value)) {
                return null
            }
            val segments = normalizedSegments(value)
            val sideIndex = segments.indexOfFirst { it.equals("L", ignoreCase = true) || it.equals("R", ignoreCase = true) }
            if (sideIndex == -1) {
                return null
            }

            val prefixSegments = segments.take(sideIndex).filter { it.isNotEmpty() }
            val suffixSegments = segments.drop(sideIndex + 1).filter { it.isNotEmpty() }

            if (suffixSegments.isNotEmpty()) {
                val identifierSuffix = suffixSegments.joinToString("_")
                val identifierPrefix = prefixSegments.joinToString("_")
                return listOfNotNull(
                    identifierPrefix.takeIf { it.isNotEmpty() },
                    identifierSuffix.takeIf { it.isNotEmpty() }
                ).joinToString("_")
            }

            val identifierPrefix = prefixSegments.joinToString("_")
            if (identifierPrefix.isNotEmpty()) {
                return identifierPrefix
            }

            val fallbackSuffix = address.replace(":", "").takeLast(6)
            return fallbackSuffix.takeIf { it.isNotEmpty() }
        }

        private fun String.hasSideToken(side: String): Boolean =
            normalizedSegments(this).any { it.equals(side, ignoreCase = true) }

        private fun sideFromName(name: String?): G1Gesture.Side? {
            val value = name ?: return null
            val segments = normalizedSegments(value)
            val sideIndex = segments.indexOfFirst { it.equals("L", ignoreCase = true) || it.equals("R", ignoreCase = true) }
            if (sideIndex == -1) {
                return null
            }
            val token = segments[sideIndex]
            return if (token.equals("L", ignoreCase = true)) {
                G1Gesture.Side.LEFT
            } else {
                G1Gesture.Side.RIGHT
            }
        }

        private fun candidateFromScanResult(result: ScanResult): DeviceCandidate? {
            val device = result.device
            val record = result.scanRecord
            val advertisedName = record?.deviceName ?: device.name
            val hasRecognizedName = advertisedName?.let { isRecognizedDeviceName(it) } == true
            val manufacturerMatch = record.findManufacturerMatch()
            val hasDiscoveryHint = record.hasNusService() || manufacturerMatch != null

            if (!hasRecognizedName && !hasDiscoveryHint) {
                return null
            }

            val identifier = pairingIdentifier(advertisedName, device.address)
                ?: manufacturerMatch?.identifier(device.address)
                ?: addressPairIdentifier(device.address)
                ?: return null

            val side = when {
                hasRecognizedName -> sideFromName(advertisedName)
                else -> null
            }
            val rssi = result.rssi.takeIf { it != 0 }
            return DeviceCandidate(device, identifier, side, rssi, advertisedName)
        }

        @SuppressLint("MissingPermission")
        private fun candidateFromDevice(device: BluetoothDevice): DeviceCandidate? {
            val name = device.name ?: return null
            if (!isRecognizedDeviceName(name)) {
                return null
            }
            val identifier = pairingIdentifier(name, device.address) ?: return null
            val side = sideFromName(name) ?: return null
            return DeviceCandidate(device, identifier, side, null)
        }

        private fun normalizedSegments(value: String): List<String> {
            val sanitized = if (value.contains("_")) {
                value
            } else {
                value.replace('-', '_').replace(' ', '_')
            }
            return sanitized.split("_").filter { it.isNotEmpty() }
        }

        private fun isRecognizedDeviceName(name: String): Boolean {
            val normalized = name.lowercase(Locale.US)
            return MANUFACTURER_KEYWORDS.any { keyword -> normalized.contains(keyword) }
        }

        @SuppressLint("MissingPermission")
        internal fun collectBondedPairs(
            devices: Set<BluetoothDevice>,
            sideFilter: Set<G1Gesture.Side>? = null
        ): List<G1DevicePair> {
            if (devices.isEmpty()) {
                return emptyList()
            }

            val trackedIdentifiers = mutableSetOf<String>()
            val partialPairs = mutableMapOf<String, FoundPair>()
            val completedPairs = mutableListOf<G1DevicePair>()

            devices.forEach { device ->
                val candidate = candidateFromDevice(device) ?: return@forEach
                val identifier = candidate.identifier
                val existing = partialPairs[identifier]
                val resolvedLeft = when {
                    candidate.side == G1Gesture.Side.LEFT -> candidate
                    else -> existing?.left
                }
                val resolvedRight = when {
                    candidate.side == G1Gesture.Side.RIGHT -> candidate
                    else -> existing?.right
                }

                if (sideFilter != null && identifier !in trackedIdentifiers) {
                    if (sideFilter.contains(candidate.side)) {
                        trackedIdentifiers.add(identifier)
                    } else {
                        partialPairs[identifier] = FoundPair(resolvedLeft, resolvedRight)
                        return@forEach
                    }
                } else {
                    trackedIdentifiers.add(identifier)
                }

                if (resolvedLeft != null && resolvedRight != null) {
                    partialPairs.remove(identifier)
                    trackedIdentifiers.remove(identifier)
                    completedPairs.add(G1DevicePair(identifier, resolvedLeft, resolvedRight))
                } else {
                    partialPairs[identifier] = FoundPair(resolvedLeft, resolvedRight)
                }
            }

            return completedPairs
        }

        internal fun collectCompletePairs(
            rawResults: List<ScanResult?>,
            foundAddresses: MutableList<String>,
            foundPairs: MutableMap<String, FoundPair>,
            sideFilter: Set<G1Gesture.Side>? = null,
            addressDedupe: MutableSet<String>? = null
        ): List<G1DevicePair> {
            val completedPairs = mutableListOf<G1DevicePair>()
            val seenAddresses = mutableSetOf<String>()
            val trackedIdentifiers = mutableSetOf<String>().apply { addAll(foundPairs.keys) }
            val sidePreference = buildSidePreference(sideFilter)

            rawResults
                .asSequence()
                .filterNotNull()
                .forEach { result ->
                    val candidate = candidateFromScanResult(result) ?: return@forEach
                    val address = candidate.device.address
                    if (!seenAddresses.add(address)) {
                        return@forEach
                    }
                    val alreadyKnown = addressDedupe?.contains(address) ?: foundAddresses.contains(address)
                    if (alreadyKnown) {
                        return@forEach
                    }

                    val identifier = candidate.identifier
                    if (sideFilter != null && identifier !in trackedIdentifiers) {
                        val matchesFilter = when (val side = candidate.side) {
                            null -> sidePreference.firstOrNull()?.let { filterSide ->
                                sideFilter.contains(filterSide)
                            } ?: false
                            else -> sideFilter.contains(side)
                        }
                        if (!matchesFilter) {
                            return@forEach
                        }
                    }

                    trackedIdentifiers.add(identifier)

                    val updatedPair = mergeCandidate(foundPairs[identifier], candidate, sidePreference)
                    val resolvedLeft = updatedPair.left
                    val resolvedRight = updatedPair.right

                    if (addressDedupe?.add(address) != false) {
                        foundAddresses.add(address)
                    }

                    if (resolvedLeft != null && resolvedRight != null) {
                        foundPairs.remove(identifier)
                        trackedIdentifiers.remove(identifier)
                        completedPairs.add(G1DevicePair(identifier, resolvedLeft, resolvedRight))
                    } else {
                        foundPairs[identifier] = updatedPair
                    }
                }

            return completedPairs
        }

        fun find(
            context: Context,
            duration: Duration,
            sideFilter: Set<G1Gesture.Side>? = null
        ) = callbackFlow<G1?> {
            val scanner = BluetoothLeScannerCompat.getScanner()
            val settings = ScanSettings.Builder()
                .setLegacy(false)
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setReportDelay(0)
                .build()
            val handler = Handler(Looper.getMainLooper())
            val foundAddresses = mutableListOf<String>()
            val foundAddressSet = mutableSetOf<String>()
            val foundPairs = mutableMapOf<String, FoundPair>()

            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            val bondedDevices = try {
                bluetoothManager?.adapter?.bondedDevices ?: emptySet()
            } catch (error: SecurityException) {
                Log.w("G1", "Unable to access bonded devices", error)
                emptySet()
            }

            val bondedPairs = collectBondedPairs(bondedDevices, sideFilter)
            bondedPairs.forEach { pair ->
                listOf(pair.left.device.address, pair.right.device.address)
                    .forEach { address ->
                        if (foundAddressSet.add(address)) {
                            foundAddresses.add(address)
                        }
                    }
                trySendBlocking(
                    G1(
                        G1Device(pair.right.device, G1Gesture.Side.RIGHT, pair.right.rssi),
                        G1Device(pair.left.device, G1Gesture.Side.LEFT, pair.left.rssi)
                    )
                )
            }

            var scanCallback: ScanCallback? = null
            var stopScanRunnable: Runnable? = null
            var scanStarted = false

            if (bondedPairs.isEmpty()) {
                val passes = buildScanPasses(duration)

                fun handleResults(results: List<ScanResult?>) {
                    collectCompletePairs(results, foundAddresses, foundPairs, sideFilter, foundAddressSet)
                        .forEach { pair ->
                            trySendBlocking(
                                G1(
                                    G1Device(pair.right.device, G1Gesture.Side.RIGHT, pair.right.rssi),
                                    G1Device(pair.left.device, G1Gesture.Side.LEFT, pair.left.rssi)
                                )
                            )
                        }
                }

                fun stopActivePass() {
                    stopScanRunnable?.let { handler.removeCallbacks(it) }
                    stopScanRunnable = null
                    if (scanStarted) {
                        scanCallback?.let { callback ->
                            runCatching { scanner.stopScan(callback) }
                        }
                        scanStarted = false
                    }
                }

                fun startNextPass() {
                    val nextPass = passes.removeFirstOrNull()
                    if (nextPass == null) {
                        trySendBlocking(null)
                        close()
                        return
                    }

                    try {
                        scanner.startScan(nextPass.filters, settings, scanCallback!!)
                        scanStarted = true
                    } catch (error: SecurityException) {
                        Log.w("G1", "Unable to start BLE scan", error)
                        trySendBlocking(null)
                        close()
                        return
                    } catch (error: IllegalStateException) {
                        Log.w("G1", "Unable to start BLE scan", error)
                        trySendBlocking(null)
                        close()
                        return
                    }

                    stopScanRunnable = Runnable {
                        stopActivePass()
                        startNextPass()
                    }
                    handler.postDelayed(stopScanRunnable!!, nextPass.duration.inWholeMilliseconds)
                }

                val callback = object : ScanCallback() {
                    @SuppressLint("MissingPermission")
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        handleResults(listOf(result))
                    }

                    @SuppressLint("MissingPermission")
                    override fun onBatchScanResults(results: List<ScanResult?>) {
                        handleResults(results)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.w("G1", "BLE scan failed with code=$errorCode")
                        stopActivePass()
                        trySendBlocking(null)
                        close()
                    }
                }
                scanCallback = callback

                if (passes.isEmpty()) {
                    trySendBlocking(null)
                    close()
                } else {
                    startNextPass()
                }

                awaitClose {
                    stopActivePass()
                }
            } else {
                trySendBlocking(null)
                close()
                awaitClose { }
            }
        }
    }
}