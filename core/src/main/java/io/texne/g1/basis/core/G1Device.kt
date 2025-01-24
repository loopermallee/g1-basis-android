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

internal class G1Device(
    private val scanResult: ScanResult
) {

    @SuppressLint("MissingPermission")
    val name = scanResult.device.name
    val address = scanResult.device.address

    // connection state flow -----------------------------------------------------------------------

    private val writableConnectionState = MutableStateFlow<G1.ConnectionState>(G1.ConnectionState.UNINITIALIZED)
    val connectionState = writableConnectionState.asStateFlow()

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
                    writableConnectionState.value = it
                }
            }
            scope.launch {
                manager.incoming.collect { packet ->
                    if(packet.type == null) {
//                        Log.d("G1BLEManager", "Empty packet arrived from stream")
                    } else {
                        val callbacks = pendingCommandRequests.get(packet.type)
                        if (callbacks.isNullOrEmpty().not()) {
                            pendingCommandRequests[packet.type] = callbacks!!.slice(1..callbacks.lastIndex)
                            val first = callbacks.first()
                            first.callback(packet)
                        } else {
                            Log.e(
                                "G1BLEManager",
                                "ERROR: Data received for command ${packet.type} that has no attached callbacks"
                            )
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
            startHeartbeat()
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

    // commands ------------------------------------------------------------------------------------

    // TODO: commands to device

    // command and response ------------------------------------------------------------------------

    private val COMMAND_EXPIRATION_MILLIS = 5000

    private data class CommandCallback(
        val callback: (ResponsePacket?) -> Unit,
        val expires: Long
    )

    private val commandScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private val pendingCommandRequests = mutableMapOf<Byte, List<CommandCallback>>()

    private fun addCommandCallback(command: Byte, callback: (ResponsePacket?) -> Unit) {
        val callbacks = pendingCommandRequests.get(command)
        val newCallback = CommandCallback(
            callback = callback,
            expires = Date().time + COMMAND_EXPIRATION_MILLIS
        )
        pendingCommandRequests[command] = if(callbacks == null) {
            listOf(newCallback)
        } else {
            callbacks.plus(newCallback)
        }
    }

    // heartbeat -----------------------------------------------------------------------------------

    private val heartbeatScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private var heartbeatTask: ScheduledFuture<*>? = null

    private fun startHeartbeat() {
        heartbeatTask?.cancel(true)
        heartbeatTask = heartbeatScheduler.scheduleWithFixedDelay(this::sendHeartbeat, 0, 10, TimeUnit.SECONDS)
    }

    private fun sendHeartbeat() {
        manager.send(HeartbeatRequestPacket())
        // clean up expired requests with every heartbeat
        val now = Date().time
        val expired = pendingCommandRequests.map { entry -> Pair(entry.key, entry.value.filter { it.expires < now }) }.filter { it.second.isNotEmpty() }
        if(expired.isNotEmpty()) {
            expired.forEach { entry ->
                val unexpired = pendingCommandRequests.get(entry.first)?.filter { it.expires >= now }!!
                pendingCommandRequests[entry.first] = unexpired
                entry.second.forEach { callback ->
                    callback.callback(null)
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatTask?.cancel(true)
        heartbeatTask = null
    }
}
