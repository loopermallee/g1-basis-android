package io.texne.g1.basis.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import kotlin.time.Duration
import kotlin.math.roundToInt

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
        internal data class DeviceCandidate(
            val device: BluetoothDevice,
            val identifier: String,
            val side: G1Gesture.Side,
            val rssi: Int?
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

        private fun pairingIdentifier(name: String?, address: String): String? {
            val value = name ?: return null
            val segments = value.split("_")
            val sideIndex = segments.indexOfFirst { it == "L" || it == "R" }
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
            split("_").any { it == side }

        private fun sideFromName(name: String?): G1Gesture.Side? = when {
            name?.hasSideToken("L") == true -> G1Gesture.Side.LEFT
            name?.hasSideToken("R") == true -> G1Gesture.Side.RIGHT
            else -> null
        }

        private fun candidateFromScanResult(result: ScanResult): DeviceCandidate? {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: return null
            if (!name.startsWith(DEVICE_NAME_PREFIX)) {
                return null
            }
            val identifier = pairingIdentifier(name, device.address) ?: return null
            val side = sideFromName(name) ?: return null
            val rssi = result.rssi.takeIf { it != 0 }
            return DeviceCandidate(device, identifier, side, rssi)
        }

        @SuppressLint("MissingPermission")
        private fun candidateFromDevice(device: BluetoothDevice): DeviceCandidate? {
            val name = device.name ?: return null
            if (!name.startsWith(DEVICE_NAME_PREFIX)) {
                return null
            }
            val identifier = pairingIdentifier(name, device.address) ?: return null
            val side = sideFromName(name) ?: return null
            return DeviceCandidate(device, identifier, side, null)
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
            sideFilter: Set<G1Gesture.Side>? = null
        ): List<G1DevicePair> {
            val completedPairs = mutableListOf<G1DevicePair>()
            val seenAddresses = mutableSetOf<String>()
            val trackedIdentifiers = mutableSetOf<String>().apply { addAll(foundPairs.keys) }

            rawResults
                .asSequence()
                .filterNotNull()
                .mapNotNull { result ->
                    val candidate = candidateFromScanResult(result) ?: return@mapNotNull null
                    val address = candidate.device.address
                    if (!seenAddresses.add(address) || foundAddresses.contains(address)) {
                        return@mapNotNull null
                    }
                    Pair(candidate, address)
                }
                .forEach { (candidate, address) ->
                    val identifier = candidate.identifier

                    if (sideFilter != null && identifier !in trackedIdentifiers) {
                        if (sideFilter.contains(candidate.side)) {
                            trackedIdentifiers.add(identifier)
                        } else {
                            return@forEach
                        }
                    } else {
                        trackedIdentifiers.add(identifier)
                    }

                    foundAddresses.add(address)

                    val existing = foundPairs[identifier]
                    val resolvedLeft = when {
                        candidate.side == G1Gesture.Side.LEFT -> candidate
                        else -> existing?.left
                    }
                    val resolvedRight = when {
                        candidate.side == G1Gesture.Side.RIGHT -> candidate
                        else -> existing?.right
                    }

                    if (resolvedLeft != null && resolvedRight != null) {
                        foundPairs.remove(identifier)
                        trackedIdentifiers.remove(identifier)
                        completedPairs.add(G1DevicePair(identifier, resolvedLeft, resolvedRight))
                    } else {
                        foundPairs[identifier] = FoundPair(resolvedLeft, resolvedRight)
                    }
                }

            return completedPairs
        }

        fun find(
            duration: Duration,
            sideFilter: Set<G1Gesture.Side>? = null
        ) = callbackFlow<G1?> {
            val scanner = BluetoothLeScannerCompat.getScanner()
            val settings = ScanSettings.Builder()
                .setLegacy(false)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()
            val handler = Handler(Looper.getMainLooper())
            val foundAddresses = mutableListOf<String>()
            val foundPairs = mutableMapOf<String, FoundPair>()

            val bondedDevices = try {
                BluetoothAdapter.getDefaultAdapter()?.bondedDevices ?: emptySet()
            } catch (error: SecurityException) {
                Log.w("G1", "Unable to access bonded devices", error)
                emptySet()
            }

            val bondedPairs = collectBondedPairs(bondedDevices, sideFilter)
            bondedPairs.forEach { pair ->
                listOf(pair.left.device.address, pair.right.device.address)
                    .forEach { address ->
                        if (!foundAddresses.contains(address)) {
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
                fun handleResults(results: List<ScanResult?>) {
                    collectCompletePairs(results, foundAddresses, foundPairs, sideFilter)
                        .forEach { pair ->
                            trySendBlocking(
                                G1(
                                    G1Device(pair.right.device, G1Gesture.Side.RIGHT, pair.right.rssi),
                                    G1Device(pair.left.device, G1Gesture.Side.LEFT, pair.left.rssi)
                                )
                            )
                        }
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
                }
                scanCallback = callback

                try {
                    scanner.startScan(emptyList(), settings, callback)
                    scanStarted = true
                } catch (error: SecurityException) {
                    Log.w("G1", "Unable to start BLE scan", error)
                    trySendBlocking(null)
                    close()
                } catch (error: IllegalStateException) {
                    Log.w("G1", "Unable to start BLE scan", error)
                    trySendBlocking(null)
                    close()
                }

                if (scanStarted) {
                    stopScanRunnable = Runnable {
                        runCatching { scanner.stopScan(callback) }
                        trySendBlocking(null)
                    }
                    handler.postDelayed(stopScanRunnable!!, duration.inWholeMilliseconds)
                }
            } else {
                trySendBlocking(null)
                close()
                scanCallback = null
            }

            awaitClose {
                stopScanRunnable?.let { handler.removeCallbacks(it) }
                if (scanStarted) {
                    scanCallback?.let { callback ->
                        runCatching { scanner.stopScan(callback) }
                    }
                }
            }
        }
    }
}