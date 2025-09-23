package io.texne.g1.basis.client

import android.content.Context
import android.content.ServiceConnection
import io.texne.g1.basis.service.protocol.RSSI_UNKNOWN
import io.texne.g1.basis.service.protocol.SIGNAL_STRENGTH_UNKNOWN
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

const val MAX_LINES_PER_PAGE = 5
const val MAX_CHARACTERS_PER_LINE = 40

abstract class G1ServiceCommon<ServiceInterface> constructor(
    private val context: Context
): Closeable {

    enum class GlassesStatus { UNINITIALIZED, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    data class Glasses(
        val id: String,
        val name: String,
        val status: GlassesStatus,
        val batteryPercentage: Int,
        val leftStatus: GlassesStatus,
        val rightStatus: GlassesStatus,
        val leftBatteryPercentage: Int,
        val rightBatteryPercentage: Int,
        val signalStrength: Int = SIGNAL_STRENGTH_UNKNOWN,
        val rssi: Int = RSSI_UNKNOWN
    )

    enum class ServiceStatus { READY, LOOKING, LOOKED, PERMISSION_REQUIRED, ERROR }

    data class State(
        val status: ServiceStatus,
        val glasses: List<Glasses>
    )

    protected val writableState =
        MutableStateFlow<State?>(null)

    val state = writableState.asStateFlow()

    enum class GestureType { TAP, HOLD }
    enum class GestureSide { LEFT, RIGHT }

    data class GestureEvent(
        val sequence: Int,
        val type: GestureType,
        val side: GestureSide,
        val timestampMillis: Long
    )

    protected val writableGestures = MutableSharedFlow<GestureEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val gestures = writableGestures.asSharedFlow()

    protected var service: ServiceInterface? = null
    abstract protected val serviceConnection: ServiceConnection

    //

    override fun close() {
        context.unbindService(serviceConnection)
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
        if(!isValidFormattedPage(formattedPage)) {
            return false
        }

        val horizontallyAligned = formattedPage.lines.map(::renderLine)
        val renderedPage = verticallyAlignLines(horizontallyAligned, formattedPage.justify)

        if(!renderedPage.isValidPage()) {
            return false
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

    suspend fun displayTextPage(id: String, page: List<String>): Boolean {
        if(!page.isValidPage()) {
            return false
        }
        return sendTextPage(id, page)
    }

    abstract suspend fun sendTextPage(id: String, page: List<String>): Boolean
    abstract suspend fun stopDisplaying(id: String):  Boolean

    private fun isValidFormattedPage(formattedPage: FormattedPage): Boolean =
        formattedPage.lines.size <= MAX_LINES_PER_PAGE &&
            formattedPage.lines.all { it.text.length <= MAX_CHARACTERS_PER_LINE }

    private fun renderLine(line: FormattedLine): String {
        val text = line.text
        val remaining = (MAX_CHARACTERS_PER_LINE - text.length).coerceAtLeast(0)
        return when(line.justify) {
            JustifyLine.LEFT -> text
            JustifyLine.RIGHT -> " ".repeat(remaining) + text
            JustifyLine.CENTER -> {
                val leftPadding = remaining / 2
                val rightPadding = remaining - leftPadding
                " ".repeat(leftPadding) + text + " ".repeat(rightPadding)
            }
        }
    }

    private fun verticallyAlignLines(lines: List<String>, justifyPage: JustifyPage): List<String> {
        if(lines.size >= MAX_LINES_PER_PAGE) {
            return lines.take(MAX_LINES_PER_PAGE)
        }

        val paddingNeeded = MAX_LINES_PER_PAGE - lines.size
        val (topPadding, bottomPadding) = when(justifyPage) {
            JustifyPage.TOP -> 0 to paddingNeeded
            JustifyPage.BOTTOM -> paddingNeeded to 0
            JustifyPage.CENTER -> {
                val top = paddingNeeded / 2
                val bottom = paddingNeeded - top
                top to bottom
            }
        }

        val top = List(topPadding) { "" }
        val bottom = List(bottomPadding) { "" }
        return top + lines + bottom
    }

    private fun List<String>.isValidPage(): Boolean =
        size <= MAX_LINES_PER_PAGE && all { it.length <= MAX_CHARACTERS_PER_LINE }
}
