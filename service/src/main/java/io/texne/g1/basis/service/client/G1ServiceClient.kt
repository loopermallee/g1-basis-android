package io.texne.g1.basis.service.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.texne.g1.basis.service.protocol.IG1Service
import io.texne.g1.basis.service.protocol.ObserveStateCallback
import io.texne.g1.basis.service.protocol.OperationCallback
import io.texne.g1.basis.service.protocol.G1ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class G1ServiceClient(
    private val context: Context
): Closeable {

    private var service: IG1Service? = null
    private val writableState = MutableStateFlow<G1ServiceState?>(null)

    val state = writableState.asStateFlow()

    private var serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            service = binder as IG1Service
            service?.observeState(object : ObserveStateCallback.Stub() {
                override fun onStateChange(newState: G1ServiceState?) {
                    if(newState != null) {
                        writableState.value = newState
                    }
                }
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    //

    fun open(): Boolean {
        Intent().also { intent ->
            intent.setClassName(context, "io.texne.g1.basis.service.server.G1Service")
            return context.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    override fun close() {
        context.unbindService(serviceConnection)
    }

    //

    fun lookForGlasses() {
        service?.lookForGlasses()
    }

    suspend fun connect(id: String) = suspendCoroutine<Boolean> { continuation ->
        service?.connectGlasses(id, object: OperationCallback.Stub() {
            override fun onResult(success: Boolean) {
                continuation.resume(success)
            }
        })
    }

    fun disconnect(id: String) {
        service?.disconnectGlasses(id, null)
    }
}