package io.texne.g1.hub.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Repository @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {
    fun getServiceStateFlow() =
        service.state

    fun bindService(): Boolean {
        service = G1ServiceManager.open(applicationContext) ?: return false
        return true
    }

    fun unbindService() {
        if(::service.isInitialized) {
            service.close()
        }
    }

    fun startLooking() {
        if(::service.isInitialized) {
            service.lookForGlasses()
        }
    }

    suspend fun connectGlasses(id: String) =
        service.connect(id)

    fun disconnectGlasses(id: String) =
        service.disconnect(id)

    fun connectedGlasses(): List<G1ServiceCommon.Glasses> =
        if(::service.isInitialized) service.listConnectedGlasses() else emptyList()

    suspend fun displayCenteredOnConnectedGlasses(
        lines: List<String>,
        holdMillis: Long? = 5_000L
    ): Boolean {
        if(!::service.isInitialized) {
            return false
        }
        val connected = service.listConnectedGlasses().firstOrNull() ?: return false
        return service.displayCentered(connected.id, lines, holdMillis)
    }

    suspend fun stopDisplayingOnConnectedGlasses(): Boolean {
        if(!::service.isInitialized) {
            return false
        }
        val connected = service.listConnectedGlasses().firstOrNull() ?: return false
        return service.stopDisplaying(connected.id)
    }

    private lateinit var service: G1ServiceManager
}