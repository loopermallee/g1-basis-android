package io.texne.g1.basis.example.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.service.client.G1ServiceClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Repository @Inject constructor(
   @ApplicationContext private val applicationContext: Context
) {
    // ---------------------------------------------------------------------------------------------

    fun getServiceStateFlow() =
        service.state

    fun bindService() =
        service.open()

    fun unbindService() =
        service.close()

    fun startLooking() =
        service.lookForGlasses()

    suspend fun connectGlasses(id: String) =
        service.connect(id)

    fun disconnectGlasses(id: String) =
        service.disconnect(id)

    suspend fun sendText(id: String, pages: List<List<String>>) {
        // TODO
    }

    // ---------------------------------------------------------------------------------------------

    private val service = G1ServiceClient(applicationContext)
}