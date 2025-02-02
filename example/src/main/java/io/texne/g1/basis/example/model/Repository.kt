package io.texne.g1.basis.example.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Repository @Inject constructor(
   @ApplicationContext private val applicationContext: Context
) {
    // ---------------------------------------------------------------------------------------------

    fun getServiceStateFlow() =
        service.state

    fun bindService(): Boolean {
        service = G1ServiceClient.open(applicationContext) ?: return false
        return true
    }

    fun unbindService() =
        service.close()

    fun startLooking() =
        service.lookForGlasses()

    suspend fun connectGlasses(id: String) =
        service.connect(id)

    fun disconnectGlasses(id: String) =
        service.disconnect(id)

    suspend fun sendText(id: String): Boolean {
        return service.displayCentered(
            id,
            listOf("This is a test", "of centered text", "for two seconds")
        )
    }

    // ---------------------------------------------------------------------------------------------

    private lateinit var service: G1ServiceClient
}