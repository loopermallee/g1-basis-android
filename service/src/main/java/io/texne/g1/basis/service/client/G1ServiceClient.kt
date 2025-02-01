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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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

    //

    suspend fun displayFormattedPageSequence(id: String, sequence: List<TimedFormattedPage>): Boolean {
        sequence.forEach { timedPage ->
            if(displayFormattedPage(id, timedPage.page)) {
                return false
            }
            delay(timedPage.milliseconds)
        }
        return stopDisplaying(id)
    }

    suspend fun displayTimedFormattedPage(id: String, timedFormattedPage: TimedFormattedPage): Boolean {
        if(displayFormattedPage(id, timedFormattedPage.page)) {
            return false
        }
        delay(timedFormattedPage.milliseconds)
        return stopDisplaying(id)
    }

    suspend fun displayFormattedPage(id: String, formattedPage: FormattedPage): Boolean {
        if(formattedPage.lines.size > 5 || formattedPage.lines.any { it.text.length > 40 }) {
            return false
        }
        val renderedLines = formattedPage.lines.map {
            when(it.justify) {
                JustifyLine.LEFT -> it.text.plus(" ".repeat(40 - it.text.length))
                JustifyLine.CENTER -> " ".repeat((40 - it.text.length)/2).plus(it.text).plus(" ".repeat(40-((40 - it.text.length)/2)))
                JustifyLine.RIGHT -> " ".repeat(40 - it.text.length).plus(it.text)
            }
        }
        val renderedPage: List<String> = when(formattedPage.lines.size) {
            5 -> renderedLines
            4 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus(" ".repeat(40))
                JustifyPage.BOTTOM -> listOf(" ".repeat(40)).plus(renderedLines)
                JustifyPage.CENTER -> renderedLines.plus(" ".repeat(40))
            }
            3 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus(" ".repeat(40)).plus(" ".repeat(40))
                JustifyPage.BOTTOM -> listOf(" ".repeat(40)).plus(" ".repeat(40)).plus(renderedLines)
                JustifyPage.CENTER -> listOf(" ".repeat(40)).plus(renderedLines).plus(" ".repeat(40))
            }
            2 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus(" ".repeat(40)).plus(" ".repeat(40))
                JustifyPage.BOTTOM -> listOf(" ".repeat(40)).plus(" ".repeat(40)).plus(renderedLines)
                JustifyPage.CENTER -> listOf(" ".repeat(40)).plus(renderedLines).plus(" ".repeat(40))
            }
            1 -> when(formattedPage.justify) {
                JustifyPage.TOP -> renderedLines.plus(" ".repeat(40)).plus(" ".repeat(40)).plus(" ".repeat(40)).plus(" ".repeat(40))
                JustifyPage.BOTTOM -> listOf(" ".repeat(40)).plus(" ".repeat(40)).plus(" ".repeat(40)).plus(" ".repeat(40)).plus(renderedLines)
                JustifyPage.CENTER -> listOf(" ".repeat(40)).plus(" ".repeat(40)).plus(renderedLines).plus(" ".repeat(40)).plus(" ".repeat(40))
            }
            else -> listOf(" ".repeat(40)," ".repeat(40)," ".repeat(40)," ".repeat(40)," ".repeat(40))
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

    suspend fun displayTextPage(id: String, page: List<String>) = suspendCoroutine<Boolean> { continuation ->
        service?.displayTextPage(id, page.toTypedArray(), object: OperationCallback.Stub() {
            override fun onResult(success: Boolean) {
                continuation.resume(success)
            }
        })
    }

    suspend fun stopDisplaying(id: String) = suspendCoroutine<Boolean> { continuation ->
        service?.stopDisplaying(id, object: OperationCallback.Stub() {
            override fun onResult(success: Boolean) {
                continuation.resume(success)
            }
        })
    }
}