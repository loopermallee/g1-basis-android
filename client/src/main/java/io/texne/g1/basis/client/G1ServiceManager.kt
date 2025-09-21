package io.texne.g1.basis.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.texne.g1.basis.service.protocol.G1Glasses
import io.texne.g1.basis.service.protocol.G1GestureEvent
import io.texne.g1.basis.service.protocol.G1ServiceState
import io.texne.g1.basis.service.protocol.IG1Service
import io.texne.g1.basis.service.protocol.ObserveStateCallback
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class G1ServiceManager private constructor(context: Context): G1ServiceCommon<IG1Service>(context) {

    companion object {
        fun open(context: Context): G1ServiceManager? {
            val client = G1ServiceManager(context)
            val intent = Intent("io.texne.g1.basis.service.protocol.IG1Service")
            intent.setClassName(context.packageName, "io.texne.g1.basis.service.G1Service")
            if (context.bindService(
                    intent,
                    client.serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
            ) {
                return client
            }
            return null
        }
    }

    private var lastGestureSequence: Int = 0

    override val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            service = IG1Service.Stub.asInterface(binder)
            service?.observeState(object : ObserveStateCallback.Stub() {
                override fun onStateChange(newState: G1ServiceState?) {
                    if(newState != null) {
                        newState.gestureEvent?.let { event ->
                            if(event.sequence > lastGestureSequence) {
                                lastGestureSequence = event.sequence
                                val type = when(event.type) {
                                    G1GestureEvent.TYPE_TAP -> GestureType.TAP
                                    G1GestureEvent.TYPE_HOLD -> GestureType.HOLD
                                    else -> null
                                }
                                val side = when(event.side) {
                                    G1GestureEvent.SIDE_LEFT -> GestureSide.LEFT
                                    G1GestureEvent.SIDE_RIGHT -> GestureSide.RIGHT
                                    else -> null
                                }
                                if(type != null && side != null) {
                                    writableGestures.tryEmit(
                                        GestureEvent(
                                            sequence = event.sequence,
                                            type = type,
                                            side = side,
                                            timestampMillis = event.timestampMillis
                                        )
                                    )
                                }
                            }
                        }
                        writableState.value = State(
                            status = when(newState.status) {
                                G1ServiceState.READY -> ServiceStatus.READY
                                G1ServiceState.LOOKING -> ServiceStatus.LOOKING
                                G1ServiceState.LOOKED -> ServiceStatus.LOOKED
                                else -> ServiceStatus.ERROR
                            },
                            glasses = newState.glasses.map { glass ->
                                val status = when(glass.connectionState) {
                                    G1Glasses.UNINITIALIZED -> GlassesStatus.UNINITIALIZED
                                    G1Glasses.DISCONNECTED -> GlassesStatus.DISCONNECTED
                                    G1Glasses.CONNECTING -> GlassesStatus.CONNECTING
                                    G1Glasses.CONNECTED -> GlassesStatus.CONNECTED
                                    G1Glasses.DISCONNECTING -> GlassesStatus.DISCONNECTING
                                    else -> GlassesStatus.ERROR
                                }
                                Glasses(
                                    id = glass.id,
                                    name = glass.name,
                                    status = status,
                                    batteryPercentage = glass.batteryPercentage,
                                    leftStatus = when(glass.leftConnectionState) {
                                        G1Glasses.UNINITIALIZED -> GlassesStatus.UNINITIALIZED
                                        G1Glasses.DISCONNECTED -> GlassesStatus.DISCONNECTED
                                        G1Glasses.CONNECTING -> GlassesStatus.CONNECTING
                                        G1Glasses.CONNECTED -> GlassesStatus.CONNECTED
                                        G1Glasses.DISCONNECTING -> GlassesStatus.DISCONNECTING
                                        else -> GlassesStatus.ERROR
                                    },
                                    rightStatus = when(glass.rightConnectionState) {
                                        G1Glasses.UNINITIALIZED -> GlassesStatus.UNINITIALIZED
                                        G1Glasses.DISCONNECTED -> GlassesStatus.DISCONNECTED
                                        G1Glasses.CONNECTING -> GlassesStatus.CONNECTING
                                        G1Glasses.CONNECTED -> GlassesStatus.CONNECTED
                                        G1Glasses.DISCONNECTING -> GlassesStatus.DISCONNECTING
                                        else -> GlassesStatus.ERROR
                                    },
                                    leftBatteryPercentage = glass.leftBatteryPercentage,
                                    rightBatteryPercentage = glass.rightBatteryPercentage,
                                    signalStrength = glass.signalStrength,
                                    rssi = glass.rssi,
                                    leftMacAddress = glass.leftMacAddress ?: "",
                                    rightMacAddress = glass.rightMacAddress ?: "",
                                    leftNegotiatedMtu = glass.leftNegotiatedMtu,
                                    rightNegotiatedMtu = glass.rightNegotiatedMtu,
                                    leftLastConnectionAttemptMillis = glass.leftLastConnectionAttemptMillis,
                                    rightLastConnectionAttemptMillis = glass.rightLastConnectionAttemptMillis,
                                    leftLastConnectionSuccessMillis = glass.leftLastConnectionSuccessMillis,
                                    rightLastConnectionSuccessMillis = glass.rightLastConnectionSuccessMillis,
                                    leftLastDisconnectMillis = glass.leftLastDisconnectMillis,
                                    rightLastDisconnectMillis = glass.rightLastDisconnectMillis,
                                    lastConnectionAttemptMillis = glass.lastConnectionAttemptMillis,
                                    lastConnectionSuccessMillis = glass.lastConnectionSuccessMillis,
                                    lastDisconnectMillis = glass.lastDisconnectMillis
                                )
                            },
                            scanTriggerTimestamps = newState.scanTriggerTimestamps?.toList() ?: emptyList(),
                            recentScanResults = newState.recentScanResults?.map { result ->
                                ScanResult(
                                    id = result.id,
                                    name = result.name,
                                    signalStrength = result.signalStrength,
                                    rssi = result.rssi,
                                    timestampMillis = result.timestampMillis
                                )
                            } ?: emptyList(),
                            lastConnectedId = newState.lastConnectedId
                        )
                    }
                }
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            lastGestureSequence = 0
        }
    }

    fun lookForGlasses() {
        service?.lookForGlasses()
    }

    suspend fun connect(id: String) = suspendCoroutine<Boolean> { continuation ->
        service?.connectGlasses(
            id,
            object : io.texne.g1.basis.service.protocol.OperationCallback.Stub() {
                override fun onResult(success: Boolean) {
                    continuation.resume(success)
                }
            })
    }

    fun disconnect(id: String) {
        service?.disconnectGlasses(id, null)
    }

    override suspend fun sendTextPage(id: String, page: List<String>) =
        suspendCoroutine<Boolean> { continuation ->
            service?.displayTextPage(
                id,
                page.toTypedArray(),
                object : io.texne.g1.basis.service.protocol.OperationCallback.Stub() {
                    override fun onResult(success: Boolean) {
                        continuation.resume(success)
                    }
                })
        }

    override suspend fun stopDisplaying(id: String) = suspendCoroutine<Boolean> { continuation ->
        service?.stopDisplaying(
            id,
            object : io.texne.g1.basis.service.protocol.OperationCallback.Stub() {
                override fun onResult(success: Boolean) {
                    continuation.resume(success)
                }
            })
    }
}