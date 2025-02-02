package io.texne.g1.basis.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.texne.g1.basis.service.protocol.G1Glasses
import io.texne.g1.basis.service.protocol.G1ServiceState
import io.texne.g1.basis.service.protocol.IG1Service
import io.texne.g1.basis.service.protocol.ObserveStateCallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import kotlin.collections.any
import kotlin.collections.forEach
import kotlin.collections.map
import kotlin.collections.plus
import kotlin.collections.toTypedArray
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.text.repeat

const val SPACE_FILL_MULTIPLIER = 2

class G1ServiceClient private constructor(
    private val context: Context
): Closeable {

    companion object {
        fun open(context: Context): G1ServiceClient? {
            val client = G1ServiceClient(context)
            val intent = Intent()
            intent.setClassName(context, "io.texne.g1.basis.service.G1Service")
            if(context.bindService(
                intent,
                client.serviceConnection,
                Context.BIND_AUTO_CREATE
            )) {
                return client
            }
            return null
        }
    }

    enum class GlassesStatus { UNINITIALIZED, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    data class Glasses(
        val id: String,
        val name: String,
        val status: GlassesStatus,
        val batteryPercentage: Int
    )

    enum class ServiceStatus { READY, LOOKING, LOOKED, ERROR }

    data class State(
        val status: ServiceStatus,
        val glasses: List<Glasses>
    )

    private var service: IG1Service? = null
    private val writableState =
        MutableStateFlow<State?>(null)

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
                        writableState.value = State(
                            status = when(newState.status) {
                                G1ServiceState.READY -> ServiceStatus.READY
                                G1ServiceState.LOOKING -> ServiceStatus.LOOKING
                                G1ServiceState.LOOKED -> ServiceStatus.LOOKED
                                else -> ServiceStatus.ERROR
                            },
                            glasses = newState.glasses.map { Glasses(
                                id = it.id,
                                name = it.name,
                                status = when(it.connectionState) {
                                    G1Glasses.UNINITIALIZED -> GlassesStatus.UNINITIALIZED
                                    G1Glasses.DISCONNECTED -> GlassesStatus.DISCONNECTED
                                    G1Glasses.CONNECTING -> GlassesStatus.CONNECTING
                                    G1Glasses.CONNECTED -> GlassesStatus.CONNECTED
                                    G1Glasses.DISCONNECTING -> GlassesStatus.DISCONNECTING
                                    else -> GlassesStatus.ERROR
                                },
                                batteryPercentage = it.batteryPercentage
                            ) }
                        )
                    }
                }
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    //

    override fun close() {
        context.unbindService(serviceConnection)
    }

    //

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

    //

    fun listConnectedGlasses(): List<Glasses> =
        state.value?.glasses?.filter { it.status == GlassesStatus.CONNECTED } ?: listOf()

    //

    enum class JustifyLine { LEFT, RIGHT, CENTER }
    enum class JustifyPage { TOP, BOTTOM, CENTER }

    data class FormattedLine(
        val text: String,
        val justify: JustifyLine
    )

    data class FormattedPage(
        val lines: List<FormattedLine>,
        val justify: JustifyPage
    )

    data class TimedFormattedPage(
        val page: FormattedPage,
        val milliseconds: Long
    )

    suspend fun displayFormattedPageSequence(id: String, sequence: List<TimedFormattedPage>): Boolean {
        sequence.forEach { timedPage ->
            if(!displayFormattedPage(id, timedPage.page)) {
                return false
            }
            delay(timedPage.milliseconds)
        }
        return stopDisplaying(id)
    }

    suspend fun displayTimedFormattedPage(id: String, timedFormattedPage: TimedFormattedPage): Boolean {
        if(!displayFormattedPage(id, timedFormattedPage.page)) {
            return false
        }
        delay(timedFormattedPage.milliseconds)
        return stopDisplaying(id)
    }

    suspend fun displayCentered(id: String, lines: List<String>, milliseconds: Long? = 2000): Boolean {
        return if(milliseconds == null) displayFormattedPage(id, FormattedPage(
            justify = JustifyPage.CENTER,
            lines = lines.map { FormattedLine(text = it, justify = JustifyLine.CENTER) }
        )) else displayTimedFormattedPage( id,
            TimedFormattedPage(
                page = FormattedPage(
                    justify = JustifyPage.CENTER,
                    lines = lines.map { FormattedLine(text = it, justify = JustifyLine.CENTER) }
                ),
                milliseconds = milliseconds
            )
        )
    }

    suspend fun displayFormattedPage(id: String, formattedPage: FormattedPage): Boolean {
        if(formattedPage.lines.size > 5 || formattedPage.lines.any { it.text.length > 40 }) {
            return false
        }
        val renderedLines = formattedPage.lines.map {
            when(it.justify) {
                JustifyLine.LEFT -> it.text
                JustifyLine.CENTER -> " ".repeat((SPACE_FILL_MULTIPLIER*(40 - it.text.length)/2).toInt()).plus(it.text)
                JustifyLine.RIGHT -> " ".repeat((SPACE_FILL_MULTIPLIER*(40 - it.text.length)).toInt()).plus(it.text)
            }
        }
        val renderedPage: List<String> = when(formattedPage.lines.size) {
            5 -> renderedLines
            4 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus("")
                JustifyPage.BOTTOM -> listOf("").plus(renderedLines)
                JustifyPage.CENTER -> renderedLines.plus("")
            }
            3 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus(listOf("", ""))
                JustifyPage.BOTTOM -> listOf("", "").plus(renderedLines)
                JustifyPage.CENTER -> listOf("").plus(renderedLines).plus("")
            }
            2 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus(listOf("", "", ""))
                JustifyPage.BOTTOM -> listOf("", "", "").plus(renderedLines)
                JustifyPage.CENTER -> listOf("").plus(renderedLines).plus(listOf("", ""))
            }
            1 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus(listOf("", "", "", ""))
                JustifyPage.BOTTOM -> listOf("", "", "", "").plus(renderedLines)
                JustifyPage.CENTER -> listOf("", "").plus(renderedLines).plus(listOf("", ""))
            }
            else -> listOf("", "", "", "", "")
        }
        return displayTextPage(id, renderedPage)
    }

    suspend fun displayTimedTextPage(id: String, page: List<String>, milliseconds: Long): Boolean {
        if(!displayTextPage(id, page)) {
            return false
        }
        delay(milliseconds)
        return stopDisplaying(id)
    }

    suspend fun displayTextPage(id: String, page: List<String>) =
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

    suspend fun stopDisplaying(id: String) = suspendCoroutine<Boolean> { continuation ->
        service?.stopDisplaying(
            id,
            object : io.texne.g1.basis.service.protocol.OperationCallback.Stub() {
                override fun onResult(success: Boolean) {
                    continuation.resume(success)
                }
            })
    }
}