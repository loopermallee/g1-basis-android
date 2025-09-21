package io.texne.g1.hub.ui.debug

/**
 * Supplies stored log entries that should be surfaced in the debug panel.
 */
interface DebugLogProvider {
    /** Human readable name for the log source. */
    val name: String

    /** Returns stored log entries ordered from oldest to newest. */
    suspend fun getLogs(): List<String>
}
