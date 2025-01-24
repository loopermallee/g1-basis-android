package io.texne.g1.basis.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import kotlin.time.Duration

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

    // externally visible --------------------------------------------------------------------------

    val id: String
    val name: String
    val serial: String
    enum class ConnectionState { UNINITIALIZED, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }
    val connectionState: StateFlow<ConnectionState>

    // construction --------------------------------------------------------------------------------

    private constructor(right: G1Device, left: G1Device) {
        val splitL = left.name.split("_")
        val splitR = right.name.split("_")
        this.id = "${left.address}${right.address}"
        this.name = "${splitL[0]}.${splitL[1]}"
        this.serial = "${splitL[3]}${splitR[3]}"
        this.right = right
        this.left = left
        this.connectionState = right.connectionState.combineState(left.connectionState) { l, r ->
            val state = when {
                l == ConnectionState.CONNECTED && r == ConnectionState.CONNECTED -> ConnectionState.CONNECTED
                l == ConnectionState.DISCONNECTING || r == ConnectionState.DISCONNECTING -> ConnectionState.DISCONNECTING
                l == ConnectionState.DISCONNECTED && r == ConnectionState.CONNECTED -> ConnectionState.DISCONNECTING
                l == ConnectionState.CONNECTED && r == ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTING
                l == ConnectionState.CONNECTING || r == ConnectionState.CONNECTING -> ConnectionState.CONNECTING
                l == ConnectionState.DISCONNECTED && r == ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
                l == ConnectionState.ERROR || r == ConnectionState.ERROR -> ConnectionState.ERROR
                else -> ConnectionState.UNINITIALIZED
            }
            Log.d("G1", "CONNECTION_STATUS - composing - ${l} and ${r} = ${state}")
            state
        }
    }

    // connect / disconnect ------------------------------------------------------------------------

    suspend fun connect(context: Context, scope: CoroutineScope) = coroutineScope<Boolean> {
        if(connectionState.value == ConnectionState.CONNECTED) {
            true
        } else {
            val results = awaitAll(
                async { left.connect(context, scope) },
                async { right.connect(context, scope) }
            )
            if (results[0] == true && results[1] == true) {
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
    }

    // find devices --------------------------------------------------------------------------------

    companion object {
        fun find(duration: Duration) = callbackFlow<G1?> {
            data class FoundPair(
                val left: ScanResult? = null,
                val right: ScanResult? = null
            )

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
                    results
                        .filter { it -> it != null
                                && it.device.name != null
                                && it.device.name.startsWith(DEVICE_NAME_PREFIX)
                                && foundAddresses.contains(it.device.address).not() }
                        .distinctBy { it!!.device.address }
                        .groupBy { it!!.device.name.split("_")[1] }
                        .forEach {
                            it.value.forEach { found ->
                                foundAddresses.add(found!!.device.address)
                            }
                            val pair = foundPairs.get(it.key)
                            var left = pair?.left ?: it.value.find { found -> found!!.device.name.split('_')[2] == "L" }
                            var right = pair?.right ?: it.value.find { found -> found!!.device.name.split('_')[2] == "R" }
                            if(left != null && right != null) {
                                foundPairs.remove(it.key)
                                trySendBlocking(G1(
                                    G1Device(right),
                                    G1Device(left)
                                ))
                            }
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