package io.texne.g1.basis.core

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.support.v18.scanner.ScanResult
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class G1Device(
    private val scanResult: ScanResult
) {

    @SuppressLint("MissingPermission")
    val name = scanResult.device.name
    val address = scanResult.device.address

    // state flow ----------------------------------------------------------------------------------

    data class State(
        val connectionState: G1.ConnectionState = G1.ConnectionState.UNINITIALIZED,
        val batteryPercentage: Int? = null
    )

    private val writableState = MutableStateFlow<State>(State())
    val state = writableState.asStateFlow()

    // manager -------------------------------------------------------------------------------------

    private lateinit var manager: G1BLEManager

    // connection ----------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun connect(context: Context, scope: CoroutineScope): Boolean {
        if(this::manager.isInitialized.not()) {
            manager = G1BLEManager(scanResult.device.name, context, scope)
            scope.launch {
                manager.connectionState.collect {
                    Log.d("G1Device", "CONNECTION_STATUS ${scanResult.device.name} = ${it}")
                    writableState.value = state.value.copy(
                        connectionState = it
                    )
                }
            }
            scope.launch {
                manager.incoming.collect { packet ->
                    when(packet) {
                        is BatteryLevelResponsePacket -> {
                            if(state.value.batteryPercentage != packet.level) {
                                writableState.value = state.value.copy(
                                    batteryPercentage = packet.level
                                )
                            }
                        }
                        else -> {
                            // is this the response we're expecting?
                            val request = currentRequest
                            if(request != null && request.outgoing.type.byte == packet.type?.byte) {
                                // service the request, and advance the queue
                                request.callback.invoke(packet)
                                val nextRequest = queuedRequests.removeFirstOrNull()
                                if(nextRequest != null) {
                                    currentRequest = nextRequest.copy(
                                        expires = Date().time + REQUEST_EXPIRATION_MILLIS
                                    )
                                    manager.send(nextRequest.outgoing)
                                } else {
                                    currentRequest = null
                                }
                            } else {
                                // TODO: emit it as an unrequested packet
                            }
                        }
                    }
                }
            }
        }
        try {
            manager.connect(scanResult.device)
                .useAutoConnect(true)
                .retry(3)
                .timeout(30_000)
                .suspend()
            startPeriodicBatteryCheck()
            return true
        } catch (e: Throwable) {
            Log.e(
                "G1BLEManager",
                "ERROR: Device connection error ${e}"
            )
            return false
        }
    }

    suspend fun disconnect() {
        stopHeartbeat()
        manager.disconnect().suspend()
    }

    // requests ------------------------------------------------------------------------------------

    // TODO: methods sending requests to device

    // request and response mechanism --------------------------------------------------------------

    private val REQUEST_EXPIRATION_MILLIS = 5000

    private data class Request(
        val outgoing: OutgoingPacket,
        val callback: (IncomingPacket?) -> Unit,
        val expires: Long = 0
    )

    private val queuedRequests = mutableListOf<Request>()
    private var currentRequest: Request? = null

    private fun sendRequest(outgoing: OutgoingPacket) = manager.send(outgoing)

    private suspend fun sendRequestForResponse(outgoing: OutgoingPacket) =
        suspendCoroutine<IncomingPacket?> { continuation ->
            if(queuedRequests.isEmpty()) {
                currentRequest = Request(
                    outgoing = outgoing,
                    callback = { incomingPacket -> continuation.resume(incomingPacket) },
                    expires = Date().time + REQUEST_EXPIRATION_MILLIS
                )
                manager.send(outgoing)
            } else {
                queuedRequests.add(
                    Request(
                        outgoing = outgoing,
                        callback = { incomingPacket -> continuation.resume(incomingPacket) }
                    )
                )
            }
        }

    // heartbeat (battery check) -------------------------------------------------------------------

    private val batteryCheckScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private var batteryCheckTask: ScheduledFuture<*>? = null

    private fun startPeriodicBatteryCheck() {
        batteryCheckTask?.cancel(true)
        batteryCheckTask = batteryCheckScheduler.scheduleWithFixedDelay(this::sendBatteryCheck, 0, 10, TimeUnit.SECONDS)
    }

    private fun sendBatteryCheck() {
        manager.send(BatteryLevelRequestPacket())

        // if current request has expired, return failure and advance queue
        val request = currentRequest
        if(request != null && request.expires < Date().time) {
            request.callback(null)
            val nextRequest = queuedRequests.removeFirstOrNull()
            if(nextRequest != null) {
                currentRequest = nextRequest.copy(
                    expires = Date().time + REQUEST_EXPIRATION_MILLIS
                )
                manager.send(nextRequest.outgoing)
            }
        }
    }

    private fun stopHeartbeat() {
        batteryCheckTask?.cancel(true)
        batteryCheckTask = null
    }
}
