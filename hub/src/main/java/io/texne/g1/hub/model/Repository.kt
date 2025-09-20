package io.texne.g1.hub.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceManager
import io.texne.g1.basis.service.protocol.RSSI_UNKNOWN
import io.texne.g1.basis.service.protocol.SIGNAL_STRENGTH_UNKNOWN
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Singleton
class Repository @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {
    data class EyeSnapshot(
        val status: G1ServiceCommon.GlassesStatus,
        val batteryPercentage: Int
    )

    data class GlassesSnapshot(
        val id: String,
        val name: String,
        val status: G1ServiceCommon.GlassesStatus,
        val batteryPercentage: Int,
        val left: EyeSnapshot,
        val right: EyeSnapshot,
        val signalStrength: Int?,
        val rssi: Int?
    )

    data class ServiceSnapshot(
        val status: G1ServiceCommon.ServiceStatus,
        val glasses: List<GlassesSnapshot>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var gestureJob: Job? = null
    private var serviceStateJob: Job? = null
    private val gestureEventsFlow = MutableSharedFlow<G1ServiceCommon.GestureEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val serviceState = MutableStateFlow<ServiceSnapshot?>(null)

    fun gestureEvents(): SharedFlow<G1ServiceCommon.GestureEvent> = gestureEventsFlow.asSharedFlow()

    fun getServiceStateFlow() =
        serviceState.asStateFlow()

    fun bindService(): Boolean {
        service = G1ServiceManager.open(applicationContext) ?: return false
        gestureJob?.cancel()
        gestureJob = scope.launch {
            service.gestures.collect { gesture ->
                gestureEventsFlow.emit(gesture)
            }
        }
        serviceStateJob?.cancel()
        serviceStateJob = scope.launch {
            service.state.collect { state ->
                serviceState.value = state?.let { snapshot ->
                    ServiceSnapshot(
                        status = snapshot.status,
                        glasses = snapshot.glasses.map { it.toSnapshot() }
                    )
                }
            }
        }
        return true
    }

    fun unbindService() {
        if(::service.isInitialized) {
            gestureJob?.cancel()
            gestureJob = null
            serviceStateJob?.cancel()
            serviceStateJob = null
            serviceState.value = null
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
        pages: List<List<String>>,
        holdMillis: Long? = 5_000L
    ): Boolean {
        if(!::service.isInitialized) {
            return false
        }
        val connected = service.listConnectedGlasses().firstOrNull() ?: return false
        val sanitizedPages = sanitizePages(pages)
        if (sanitizedPages.isEmpty()) {
            return false
        }

        return if (sanitizedPages.size == 1) {
            service.displayCentered(connected.id, sanitizedPages.first(), holdMillis)
        } else {
            displayCenteredPage(connected.id, sanitizedPages, 0)
        }
    }

    suspend fun displayCenteredPageOnConnectedGlasses(
        pages: List<List<String>>,
        pageIndex: Int
    ): Boolean {
        if(!::service.isInitialized) {
            return false
        }
        val connected = service.listConnectedGlasses().firstOrNull() ?: return false
        val sanitizedPages = sanitizePages(pages)
        if (sanitizedPages.isEmpty() || pageIndex !in sanitizedPages.indices) {
            return false
        }
        return displayCenteredPage(connected.id, sanitizedPages, pageIndex)
    }

    suspend fun stopDisplayingOnConnectedGlasses(): Boolean {
        if(!::service.isInitialized) {
            return false
        }
        val connected = service.listConnectedGlasses().firstOrNull() ?: return false
        return service.stopDisplaying(connected.id)
    }

    internal fun setServiceManagerForTest(serviceManager: G1ServiceManager) {
        service = serviceManager
    }

    private lateinit var service: G1ServiceManager

    private companion object {
        private const val MAX_LINES_PER_PAGE = 4
    }

    private fun sanitizePages(pages: List<List<String>>): List<List<String>> =
        pages.map { it.take(MAX_LINES_PER_PAGE) }.filter { it.isNotEmpty() }

    private suspend fun displayCenteredPage(
        glassesId: String,
        pages: List<List<String>>,
        pageIndex: Int
    ): Boolean {
        val pageLines = pages[pageIndex]
        return service.displayFormattedPage(glassesId, buildCenteredFormattedPage(pageLines))
    }

    private fun buildCenteredFormattedPage(lines: List<String>): G1ServiceCommon.FormattedPage =
        G1ServiceCommon.FormattedPage(
            justify = G1ServiceCommon.JustifyPage.CENTER,
            lines = lines.map { line ->
                G1ServiceCommon.FormattedLine(
                    text = line,
                    justify = G1ServiceCommon.JustifyLine.CENTER
                )
            }
        )

    private fun G1ServiceCommon.Glasses.toSnapshot() = GlassesSnapshot(
        id = id,
        name = name,
        status = status,
        batteryPercentage = batteryPercentage,
        left = EyeSnapshot(
            status = leftStatus,
            batteryPercentage = leftBatteryPercentage
        ),
        right = EyeSnapshot(
            status = rightStatus,
            batteryPercentage = rightBatteryPercentage
        ),
        signalStrength = signalStrength.takeIf { it != SIGNAL_STRENGTH_UNKNOWN },
        rssi = rssi.takeIf { it != RSSI_UNKNOWN }
    )
}
