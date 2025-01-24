package io.texne.g1.basis.service.server

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions
import io.texne.g1.basis.core.G1
import io.texne.g1.basis.service.protocol.G1Glasses
import io.texne.g1.basis.service.protocol.IG1Service
import io.texne.g1.basis.service.protocol.ObserveStateCallback
import io.texne.g1.basis.service.protocol.OperationCallback
import io.texne.g1.basis.service.protocol.G1ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.DurationUnit
import kotlin.time.toDuration

//

private fun G1.ConnectionState.toInt(): Int =
    when(this) {
        G1.ConnectionState.UNINITIALIZED -> G1Glasses.UNINITIALIZED
        G1.ConnectionState.DISCONNECTED -> G1Glasses.DISCONNECTED
        G1.ConnectionState.CONNECTING -> G1Glasses.CONNECTING
        G1.ConnectionState.CONNECTED -> G1Glasses.CONNECTED
        G1.ConnectionState.DISCONNECTING -> G1Glasses.DISCONNECTING
        G1.ConnectionState.ERROR -> G1Glasses.ERROR
    }

private fun G1Service.ServiceStatus.toInt(): Int =
    when(this) {
        G1Service.ServiceStatus.READY -> G1ServiceState.READY
        G1Service.ServiceStatus.LOOKING -> G1ServiceState.LOOKING
        G1Service.ServiceStatus.LOOKED -> G1ServiceState.LOOKED
        G1Service.ServiceStatus.ERROR -> G1ServiceState.ERROR
    }

private fun G1Service.InternalGlasses.toGlasses(): G1Glasses {
    val glasses = G1Glasses()
    glasses.id = this.g1.id
    glasses.name = this.g1.name
    glasses.serial = this.g1.serial
    glasses.connectionState = this.connectionState.toInt()
    return glasses
}

private fun G1Service.InternalState.toState(): G1ServiceState {
    val state = G1ServiceState()
    state.status = this.status.toInt()
    state.glasses = this.nearbyGlasses.values.map { it -> it.toGlasses() }.toTypedArray()
    return state
}

//

class G1Service: Service() {

    enum class ServiceStatus {
        READY,
        LOOKING,
        LOOKED,
        ERROR
    }

    // internal state ------------------------------------------------------------------------------


    internal data class InternalGlasses(
        val connectionState: G1.ConnectionState,
        val g1: G1
    )
    internal data class InternalState(
        val status: ServiceStatus = ServiceStatus.READY,
        val nearbyGlasses: Map<String, InternalGlasses> = mapOf()
    )
    private val state = MutableStateFlow<InternalState>(InternalState())

    // client-service interface --------------------------------------------------------------------

    private val binder = object : IG1Service.Stub() {

        override fun observeState(callback: ObserveStateCallback?) {
            if(callback != null) {
                coroutineScope.launch {
                    state.collect {
                        callback.onStateChange(it.toState())
                    }
                }
            }
        }

        override fun lookForGlasses() {
            withPermissions {
                state.value = state.value.copy(status = ServiceStatus.LOOKING, nearbyGlasses = mapOf())
                coroutineScope.launch {
                    G1.find(15.toDuration(DurationUnit.SECONDS)).collect { found ->
                        if(found != null) {
                            state.value = state.value.copy(nearbyGlasses = state.value.nearbyGlasses.plus(
                                Pair(found.id, InternalGlasses(found.connectionState.value, found))
                            ))
                            coroutineScope.launch {
                                found.connectionState.collect { connState ->
                                    Log.d("G1Service", "CONNECTION_STATUS ${found.name} (${found.serial}) = ${connState}")
                                    state.value = state.value.copy(nearbyGlasses = state.value.nearbyGlasses.entries.associate { it ->
                                        if(it.key == found.id) {
                                            Pair(
                                                it.key,
                                                it.value.copy(
                                                    connectionState = connState,
                                                    g1 = it.value.g1
                                                )
                                            )
                                        } else {
                                            Pair(
                                                it.key,
                                                it.value
                                            )
                                        }
                                    })
                                }
                            }
                        } else {
                            state.value = state.value.copy(status = ServiceStatus.LOOKED, nearbyGlasses = state.value.nearbyGlasses)
                        }
                    }
                }
            }
        }

        override fun connectGlasses(id: String?, callback: OperationCallback?) {
            if(id != null) {
                val glasses = state.value.nearbyGlasses.get(id)
                if (glasses != null) {
                    coroutineScope.launch {
                        val result = glasses.g1.connect(this@G1Service, coroutineScope)
                        callback?.onResult(result)
                    }
                } else {
                    callback?.onResult(false)
                }
            } else {
                callback?.onResult(false)
            }
        }

        override fun disconnectGlasses(id: String?, callback: OperationCallback?) {
            if(id != null) {
                val glasses = state.value.nearbyGlasses.get(id)
                if (glasses != null) {
                    coroutineScope.launch {
                        glasses.g1.disconnect()
                        callback?.onResult(true)
                    }
                } else {
                    callback?.onResult(false)
                }
            } else {
                callback?.onResult(false)
            }
        }
    }

    // infrastructure ------------------------------------------------------------------------------

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // permissions infrastructure ------------------------------------------------------------------

    private fun withPermissions(block: () -> Unit) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Permissions.check(this@G1Service, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                "Please provide the permissions so the service can interact with the G1 G1Glasses",
                Permissions.Options().setCreateNewTask(true),
                object: PermissionHandler() {
                    override fun onGranted() {
                        block()
                    }
                })
        } else {
            block()
        }
    }

    // internal service mechanism ------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    // singleton -----------------------------------------------------------------------------------

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, G1Service::class.java))
        }
    }
}