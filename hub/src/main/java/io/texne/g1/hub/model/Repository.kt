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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

@Singleton
class Repository @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var gestureJob: Job? = null
    private val gestureEventsFlow = MutableSharedFlow<G1ServiceCommon.GestureEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun gestureEvents(): SharedFlow<G1ServiceCommon.GestureEvent> = gestureEventsFlow.asSharedFlow()

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
        return true
    }

    fun unbindService() {
        if(::service.isInitialized) {
            gestureJob?.cancel()
            gestureJob = null
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

        return when {
            sanitizedPages.size == 1 ->
                service.displayCentered(connected.id, sanitizedPages.first(), holdMillis)
            holdMillis == null ->
                displayCenteredPage(connected.id, sanitizedPages, 0)
            else -> {
                val duration = holdMillis ?: DEFAULT_PAGE_HOLD_MILLIS
                val sequence = sanitizedPages.map { lines ->
                    G1ServiceCommon.TimedFormattedPage(
                        page = buildCenteredFormattedPage(lines),
                        milliseconds = duration
                    )
                }
                service.displayFormattedPageSequence(connected.id, sequence)
            }
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
        private const val DEFAULT_PAGE_HOLD_MILLIS = 4_000L
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
