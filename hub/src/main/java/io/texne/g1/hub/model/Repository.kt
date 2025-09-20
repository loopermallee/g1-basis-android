package io.texne.g1.hub.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceManager
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Singleton
class Repository @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var gestureJob: Job? = null
    private var scannerSelectionJob: Job? = null
    private val gestureEventsFlow = MutableSharedFlow<G1ServiceCommon.GestureEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    data class ScannerSelection(
        val leftAddress: String? = null,
        val rightAddress: String? = null
    )
    private val scannerSelection = MutableStateFlow(ScannerSelection())

    fun gestureEvents(): SharedFlow<G1ServiceCommon.GestureEvent> = gestureEventsFlow.asSharedFlow()

    fun observeScannerSelection(): StateFlow<ScannerSelection> = scannerSelection.asStateFlow()

    fun getServiceStateFlow() =
        service.state

    fun bindService(): Boolean {
        service = G1ServiceManager.open(applicationContext) ?: return false
        gestureJob?.cancel()
        gestureJob = scope.launch {
            service.gestures.collect { gesture ->
                gestureEventsFlow.emit(gesture)
            }
        }
        scannerSelectionJob?.cancel()
        scannerSelectionJob = scope.launch {
            service.state.collect { serviceState ->
                val availableLeft = serviceState?.availableLeftDevices?.map { it.address } ?: emptyList()
                val availableRight = serviceState?.availableRightDevices?.map { it.address } ?: emptyList()
                val current = scannerSelection.value
                var updated = current
                if(current.leftAddress != null && current.leftAddress !in availableLeft) {
                    updated = updated.copy(leftAddress = null)
                }
                if(current.rightAddress != null && current.rightAddress !in availableRight) {
                    updated = updated.copy(rightAddress = null)
                }
                if(updated != current) {
                    scannerSelection.value = updated
                }
            }
        }
        return true
    }

    fun unbindService() {
        if(::service.isInitialized) {
            gestureJob?.cancel()
            gestureJob = null
            scannerSelectionJob?.cancel()
            scannerSelectionJob = null
            scannerSelection.value = ScannerSelection()
            service.close()
        }
    }

    fun startLooking() {
        if(::service.isInitialized) {
            service.lookForGlasses()
        }
    }

    suspend fun connectDevices(leftAddress: String, rightAddress: String) =
        service.connect(leftAddress, rightAddress)

    fun disconnectGlasses(id: String) =
        service.disconnect(id)

    fun selectLeftDevice(address: String?) {
        scannerSelection.value = scannerSelection.value.copy(leftAddress = address)
    }

    fun selectRightDevice(address: String?) {
        scannerSelection.value = scannerSelection.value.copy(rightAddress = address)
    }

    fun currentScannerSelection(): ScannerSelection = scannerSelection.value

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
}
