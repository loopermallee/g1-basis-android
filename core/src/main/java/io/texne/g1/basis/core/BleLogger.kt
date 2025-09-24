package io.texne.g1.basis.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight in-memory logger for capturing BLE diagnostics that can be surfaced in-app.
 */
object BleLogger {
    private const val MAX_ENTRIES = 500

    private data class LogEntry(
        val timestampMillis: Long,
        val priority: Int,
        val tag: String,
        val message: String
    )

    private val entries = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val formatter = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }
    private val writableLines = MutableStateFlow<List<String>>(emptyList())

    val lines: StateFlow<List<String>> = writableLines.asStateFlow()

    fun log(priority: Int = Log.DEBUG, tag: String, message: String, throwable: Throwable? = null) {
        val formattedThrowable = throwable?.let { throwableValue ->
            " (" + throwableValue::class.java.simpleName + ": " + (throwableValue.message ?: "") + ")"
        }.orEmpty()
        Log.println(priority, tag, message + (throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
        val entry = LogEntry(
            timestampMillis = System.currentTimeMillis(),
            priority = priority,
            tag = tag,
            message = message + formattedThrowable
        )
        val snapshot: List<String>
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
            snapshot = entries.map { logEntry -> format(logEntry) }
        }
        writableLines.value = snapshot
    }

    fun debug(tag: String, message: String) = log(Log.DEBUG, tag, message)

    fun info(tag: String, message: String) = log(Log.INFO, tag, message)

    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.WARN, tag, message, throwable)

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.ERROR, tag, message, throwable)

    fun latest(limit: Int): List<String> {
        val snapshot: List<LogEntry> = synchronized(entries) { entries.toList() }
        return snapshot.takeLast(limit).map { entry -> format(entry) }
    }

    private fun format(entry: LogEntry): String {
        val level = when (entry.priority) {
            Log.ERROR -> "E"
            Log.WARN -> "W"
            Log.INFO -> "I"
            Log.VERBOSE -> "V"
            Log.ASSERT -> "A"
            else -> "D"
        }
        val timestamp = formatter.get().format(Date(entry.timestampMillis))
        return "$timestamp $level/${entry.tag}: ${entry.message}"
    }
}
