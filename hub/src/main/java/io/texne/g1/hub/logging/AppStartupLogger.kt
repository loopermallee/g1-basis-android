package io.texne.g1.hub.logging

import android.os.Process
import io.texne.g1.hub.ui.debug.DebugLogProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures high-level application lifecycle events so they can be surfaced in the
 * in-app debug panel and easily copied when diagnosing freezes or crashes.
 */
@Singleton
class AppStartupLogger @Inject constructor() : DebugLogProvider {

    override val name: String = "App startup"

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()

    fun recordAppLaunched() {
        record("Application onCreate executed (pid=${Process.myPid()})")
    }

    fun recordUncaughtException(thread: Thread, throwable: Throwable) {
        record(
            "Uncaught exception on '${thread.name}': ${throwable::class.java.simpleName} - ${throwable.message ?: "no message"}"
        )
    }

    fun record(message: String) {
        val entry = Entry(System.currentTimeMillis(), message)
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
    }

    override suspend fun getLogs(): List<String> {
        val snapshot = synchronized(lock) { entries.toList() }
        if (snapshot.isEmpty()) {
            return emptyList()
        }
        val formatter = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        return snapshot.map { entry ->
            "${formatter.format(Date(entry.timestampMillis))} • ${entry.message}"
        }
    }

    private data class Entry(
        val timestampMillis: Long,
        val message: String
    )

    private companion object {
        private const val MAX_ENTRIES = 100
        private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS z"
    }
}
