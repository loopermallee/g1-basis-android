package io.texne.g1.basis.core

import android.annotation.SuppressLint
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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
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
        private data class PairingTokens(
            val base: String,
            val suffix: String?
        )

        private data class PairingCandidate(
            val suffix: String?,
            val result: ScanResult
        )

        internal data class FoundPair(
            val left: MutableList<PairingCandidate> = mutableListOf(),
            val right: MutableList<PairingCandidate> = mutableListOf()
        )

        internal data class G1DevicePair(
            val identifier: String,
            val left: ScanResult,
            val right: ScanResult
        )

        private fun pairingTokens(result: ScanResult): PairingTokens? {
            val name = result.device.name ?: return null
            val segments = name
                .split("_")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val sideIndex = segments.indexOfFirst { segment ->
                val normalized = segment.uppercase(Locale.ROOT)
                normalized == "L" || normalized == "R" || normalized == "LEFT" || normalized == "RIGHT"
            }
            if (sideIndex == -1) {
                return null
            }

            val prefix = segments.take(sideIndex).joinToString("_").ifEmpty { null }
            val suffix = segments.drop(sideIndex + 1).joinToString("_").ifEmpty { null }

            val base = prefix ?: result.device.address.replace(":", "")

            return PairingTokens(base, suffix)
        }

        private fun MutableList<PairingCandidate>.removeMatching(suffix: String?): PairingCandidate? {
            if (isEmpty()) {
                return null
            }

            val normalizedSuffix = suffix?.lowercase(Locale.ROOT)
            val exactIndex = if (normalizedSuffix != null) {
                indexOfFirst { candidate ->
                    candidate.suffix?.lowercase(Locale.ROOT) == normalizedSuffix
                }
            } else {
                indexOfFirst { candidate -> candidate.suffix == null }
            }

            if (exactIndex != -1) {
                return removeAt(exactIndex)
            }

            if (normalizedSuffix == null) {
                return removeAt(0)
            }

            val nullIndex = indexOfFirst { candidate -> candidate.suffix == null }
            if (nullIndex != -1) {
                return removeAt(nullIndex)
            }

            return null
        }

        private fun String.hasSideToken(side: String): Boolean {
            val target = side.uppercase(Locale.ROOT)
            return this.split("_")
                .map { it.trim().uppercase(Locale.ROOT) }
                .any { token ->
                    when (target) {
                        "L" -> token == "L" || token == "LEFT"
                        "R" -> token == "R" || token == "RIGHT"
                        else -> token == target
                    }
                }
        }

        private fun ScanResult.isLeftDevice(): Boolean =
            this.device.name?.hasSideToken("L") == true

        private fun ScanResult.isRightDevice(): Boolean =
            this.device.name?.hasSideToken("R") == true

        private fun ScanResult.side(): G1Gesture.Side? = when {
            isLeftDevice() -> G1Gesture.Side.LEFT
            isRightDevice() -> G1Gesture.Side.RIGHT
            else -> null
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
                .forEach { result ->
                    val name = result.device.name ?: return@forEach
                    val address = result.device.address
                    if (!name.startsWith(DEVICE_NAME_PREFIX)) {
                        return@forEach
                    }
                    if (!seenAddresses.add(address) || foundAddresses.contains(address)) {
                        return@forEach
                    }

                    val tokens = pairingTokens(result) ?: return@forEach
                    val identifier = tokens.base
                    val side = result.side() ?: return@forEach

                    if (sideFilter != null && identifier !in trackedIdentifiers) {
                        if (sideFilter.contains(side)) {
                            trackedIdentifiers.add(identifier)
                        } else {
                            return@forEach
                        }
                    } else {
                        trackedIdentifiers.add(identifier)
                    }

                    foundAddresses.add(address)

                    val bucket = foundPairs.getOrPut(identifier) { FoundPair() }
                    val candidate = PairingCandidate(tokens.suffix, result)

                    val completedPair = when (side) {
                        G1Gesture.Side.LEFT -> {
                            val match = bucket.right.removeMatching(tokens.suffix)
                            if (match != null) {
                                G1DevicePair(identifier, result, match.result)
                            } else {
                                bucket.left.add(candidate)
                                null
                            }
                        }
                        G1Gesture.Side.RIGHT -> {
                            val match = bucket.left.removeMatching(tokens.suffix)
                            if (match != null) {
                                G1DevicePair(identifier, match.result, result)
                            } else {
                                bucket.right.add(candidate)
                                null
                            }
                        }
                    }

                    if (completedPair != null) {
                        if (bucket.left.isEmpty() && bucket.right.isEmpty()) {
                            foundPairs.remove(identifier)
                            trackedIdentifiers.remove(identifier)
                        }
                        completedPairs.add(completedPair)
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
            val foundAddresses = mutableListOf<String>()
            val foundPairs = mutableMapOf<String, FoundPair>()
            fun handleResults(results: List<ScanResult?>) {
                collectCompletePairs(results, foundAddresses, foundPairs, sideFilter)
                    .forEach { pair ->
                        val left = pair.left
                        val right = pair.right
                        trySendBlocking(G1(
                            G1Device(right, G1Gesture.Side.RIGHT),
                            G1Device(left, G1Gesture.Side.LEFT)
                        ))
                    }
            }

            val callback = object: ScanCallback() {
                @SuppressLint("MissingPermission")
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handleResults(listOf(result))
                }

                @SuppressLint("MissingPermission")
                override fun onBatchScanResults(results: List<ScanResult?>) {
                    handleResults(results)
                }
            }
            scanner.startScan(emptyList(), settings, callback)
            Handler(Looper.getMainLooper()).postDelayed({
                scanner.stopScan(callback)
                trySendBlocking(null)
            }, duration.inWholeMilliseconds)
            awaitClose {
                scanner.stopScan(callback)
            }
        }
    }
}