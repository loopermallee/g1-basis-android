package io.texne.g1.basis.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class G1Device(
    private val device: BluetoothDevice,
    private val side: G1Gesture.Side,
    private val initialRssi: Int? = null
) {

    @SuppressLint("MissingPermission")
    val name = device.name
    val address = device.address
    val rssi: Int? = initialRssi

    // state flow ----------------------------------------------------------------------------------

    data class DashboardStatus(
        val countdownTicks: Int?,
        val currentPage: Int?,
        val totalPages: Int?
    )

    data class State(
        val connectionState: G1.ConnectionState = G1.ConnectionState.UNINITIALIZED,
        val batteryPercentage: Int? = null,
        val dashboardStatus: DashboardStatus? = null
    )

    private val writableState = MutableStateFlow<State>(State())
    val state = writableState.asStateFlow()

    private val writableGestures = MutableSharedFlow<G1Gesture>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val gestures = writableGestures.asSharedFlow()

    // manager -------------------------------------------------------------------------------------

    private lateinit var manager: G1BLEManager

    // connection ----------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun connect(context: Context, scope: CoroutineScope): Boolean {
        if(this::manager.isInitialized.not()) {
            val claimed = G1BLEManager.claim(device)
            manager = claimed ?: G1BLEManager.create(context, device)
            scope.launch {
                manager.connectionState.collect {
                    Log.d("G1Device", "CONNECTION_STATUS ${device.name ?: device.address} = ${it}")
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
                        is GesturePacket -> {
                            val gesture = when(packet.gestureType) {
                                G1Gesture.Type.TAP -> G1Gesture.Tap(side, packet.timestampMillis)
                                G1Gesture.Type.HOLD -> G1Gesture.Hold(side, packet.timestampMillis)
                            }
                            writableGestures.emit(gesture)
                        }
                        is DashboardStatusPacket -> {
                            Log.d("G1Device", "DASHBOARD_STATUS ${packet}")
                            val status = DashboardStatus(
                                countdownTicks = packet.countdownTicks,
                                currentPage = packet.currentPage,
                                totalPages = packet.totalPages
                            )
                            if(state.value.dashboardStatus != status) {
                                writableState.value = state.value.copy(
                                    dashboardStatus = status
                                )
                            }
                        }
                        else -> {
                            // is this the response we're expecting?
                            val request = currentRequest
                            if(request != null && packet.responseTo == request.outgoing.type) {
                                // service the request, and advance the queue until a command is successfully sent
                                request.callback.invoke(packet)
                                advanceQueue()
                            } else {
                                // TODO: emit it as an unrequested packet
                            }
                        }
                    }
                }
            }
        }
        val ready = when {
            manager.isReady -> true
            manager.connectionState.value == G1.ConnectionState.CONNECTING -> awaitReady(CONNECT_TIMEOUT_MS)
            manager.connectionState.value == G1.ConnectionState.CONNECTED -> true
            else -> {
                val connected = manager.connect(CONNECT_TIMEOUT_MS)
                connected
            }
        }
        if (!ready) {
            Log.e("G1BLEManager", "Failed to establish connection to ${device.address}")
            return false
        }
        startPeriodicBatteryCheck()
        return true
    }

    suspend fun disconnect() {
        stopHeartbeat()
        manager.disconnectAwait()
    }

    private fun advanceQueue() {
        var nextRequest = queuedRequests.removeFirstOrNull()
        var successfullySent = false
        while (nextRequest != null && !successfullySent) {
            currentRequest = nextRequest.copy(
                expires = Date().time + REQUEST_EXPIRATION_MILLIS
            )
            successfullySent = manager.send(nextRequest.outgoing)
            if(!successfullySent) {
                Log.e(
                    "G1Device",
                    "Failed to send request ${nextRequest.outgoing.type}; invoking failure callback"
                )
                nextRequest.callback(null)
                nextRequest = queuedRequests.removeFirstOrNull()
            }
        }
        if(nextRequest == null) {
            currentRequest = null
        }
    }

    // request and response mechanism --------------------------------------------------------------

    private val REQUEST_EXPIRATION_MILLIS = 5000

    private data class Request(
        val outgoing: OutgoingPacket,
        val callback: (IncomingPacket?) -> Unit,
        val expires: Long = 0
    )

    private val queuedRequests = mutableListOf<Request>()
    private var currentRequest: Request? = null

    fun sendRequest(outgoing: OutgoingPacket) = manager.send(outgoing)

    suspend fun sendRequestForResponse(outgoing: OutgoingPacket) =
        suspendCoroutine<IncomingPacket?> { continuation ->
            if(queuedRequests.isEmpty()) {
                currentRequest = Request(
                    outgoing = outgoing,
                    callback = { incomingPacket -> continuation.resume(incomingPacket) },
                    expires = Date().time + REQUEST_EXPIRATION_MILLIS
                )
                if(!manager.send(outgoing)) {
                    currentRequest = null
                    continuation.resume(null)
                }
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
        batteryCheckTask = batteryCheckScheduler.scheduleWithFixedDelay(this::sendBatteryCheck, 0, 15, TimeUnit.SECONDS)
    }

    private fun sendBatteryCheck() {
        if(!manager.send(BatteryLevelRequestPacket())) {
            Log.e(
                "G1Device",
                "Heartbeat battery check failed to send"
            )
        }

        // if current request has expired, return failure and advance queue
        val request = currentRequest
        if(request != null && request.expires < Date().time) {
            Log.w(
                "G1Device",
                "Request ${request.outgoing.type} expired before response; advancing queue"
            )
            request.callback(null)
            advanceQueue()
        }
    }

    private fun stopHeartbeat() {
        batteryCheckTask?.cancel(true)
        batteryCheckTask = null
    }

    private suspend fun awaitReady(timeoutMs: Long): Boolean {
        if (manager.connectionState.value == G1.ConnectionState.CONNECTED) {
            return true
        }
        val terminalState = withTimeoutOrNull(timeoutMs) {
            manager.connectionState.first { state ->
                state == G1.ConnectionState.CONNECTED ||
                    state == G1.ConnectionState.DISCONNECTED ||
                    state == G1.ConnectionState.ERROR
            }
        } ?: return false
        return terminalState == G1.ConnectionState.CONNECTED
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
