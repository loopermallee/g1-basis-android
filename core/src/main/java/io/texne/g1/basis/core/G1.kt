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
import kotlinx.coroutines.delay
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

@OptIn(DelicateCoroutinesApi::class)
internal fun <T1, T2, R> StateFlow<T1>.combineState(
    flow2: StateFlow<T2>,
    scope: CoroutineScope = GlobalScope,
    sharingStarted: SharingStarted = SharingStarted.Eagerly,
    transform: (T1, T2) -> R
): StateFlow<R> = this.combine(flow2) {
        o1, o2 -> transform.invoke(o1, o2)
}.stateIn(scope, sharingStarted, transform.invoke(this.value, flow2.value))

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

    suspend fun connect(context: Context, scope: CoroutineScope) = coroutineScope<Boolean> {
        if(state.value.connectionState == ConnectionState.CONNECTED) {
            true
        } else {
            val results = awaitAll(
                async { left.connect(context, scope) },
                async { right.connect(context, scope) }
            )
            if (results[0] == true && results[1] == true) {
                if(gesturesJob?.isActive != true) {
                    gesturesJob = scope.launch {
                        merge(left.gestures, right.gestures).collect { gesture ->
                            writableGestures.emit(gesture)
                        }
                    }
                }
                true
            } else {
                left.disconnect()
                right.disconnect()
                false
            }
        }
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

    // find devices --------------------------------------------------------------------------------

    companion object {
        internal data class FoundPair(
            val left: ScanResult? = null,
            val right: ScanResult? = null
        )

        internal data class G1DevicePair(
            val identifier: String,
            val left: ScanResult,
            val right: ScanResult
        )

        private fun pairingIdentifier(result: ScanResult): String? {
            val name = result.device.name ?: return null
            val segments = name.split("_")
            val sideIndex = segments.indexOfFirst { it == "L" || it == "R" }
            if (sideIndex == -1) {
                return null
            }

            val prefixSegments = segments.take(sideIndex)
            val suffixSegments = segments.drop(sideIndex + 1)

            val identifierSuffix = if (suffixSegments.isNotEmpty()) {
                suffixSegments.joinToString("_")
            } else {
                result.device.address.replace(":", "").takeLast(6)
            }

            if (identifierSuffix.isEmpty()) {
                return null
            }

            return (prefixSegments + identifierSuffix).joinToString("_")
        }

        private fun String.hasSideToken(side: String): Boolean =
            this.split("_").any { it == side }

        private fun ScanResult.isLeftDevice(): Boolean =
            this.device.name?.hasSideToken("L") == true

        private fun ScanResult.isRightDevice(): Boolean =
            this.device.name?.hasSideToken("R") == true

        internal fun collectCompletePairs(
            rawResults: List<ScanResult?>,
            foundAddresses: MutableList<String>,
            foundPairs: MutableMap<String, FoundPair>
        ): List<G1DevicePair> {
            val completedPairs = mutableListOf<G1DevicePair>()
            rawResults
                .asSequence()
                .filterNotNull()
                .filter { result ->
                    result.device.name?.startsWith(DEVICE_NAME_PREFIX) == true &&
                        foundAddresses.contains(result.device.address).not()
                }
                .mapNotNull { result ->
                    val identifier = pairingIdentifier(result) ?: return@mapNotNull null
                    identifier to result
                }
                .distinctBy { (_, result) -> result.device.address }
                .groupBy({ (identifier, _) -> identifier }, { (_, result) -> result })
                .forEach { (identifier, grouped) ->
                    grouped.forEach { found ->
                        foundAddresses.add(found.device.address)
                    }

                    val existing = foundPairs[identifier]
                    val candidateLeft = grouped.firstOrNull { it.isLeftDevice() }
                    val candidateRight = grouped.firstOrNull { it.isRightDevice() }
                    val resolvedLeft = existing?.left ?: candidateLeft
                    val resolvedRight = existing?.right ?: candidateRight

                    if (resolvedLeft != null && resolvedRight != null) {
                        foundPairs.remove(identifier)
                        completedPairs.add(G1DevicePair(identifier, resolvedLeft, resolvedRight))
                    } else if (resolvedLeft != null || resolvedRight != null) {
                        foundPairs[identifier] = FoundPair(resolvedLeft, resolvedRight)
                    }
                }

            return completedPairs
        }

        fun find(duration: Duration) = callbackFlow<G1?> {
            val scanner = BluetoothLeScannerCompat.getScanner()
            val settings = ScanSettings.Builder()
                .setLegacy(false)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(5000)
                .setUseHardwareFilteringIfSupported(true)
                .build()
            val foundAddresses = mutableListOf<String>()
            val foundPairs = mutableMapOf<String, FoundPair>()
            val callback = object: ScanCallback() {
                @SuppressLint("MissingPermission")
                override fun onBatchScanResults(results: List<ScanResult?>) {
                    collectCompletePairs(results, foundAddresses, foundPairs)
                        .forEach { pair ->
                            val left = pair.left
                            val right = pair.right
                            trySendBlocking(G1(
                                G1Device(right, G1Gesture.Side.RIGHT),
                                G1Device(left, G1Gesture.Side.LEFT)
                            ))
                        }
                    }
            }
            scanner.startScan(listOf(), settings, callback)
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