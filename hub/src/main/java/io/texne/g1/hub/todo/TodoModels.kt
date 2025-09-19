package io.texne.g1.hub.todo

/**
 * Represents a single todo entry managed by the HUD todo experience.
 */
data class TodoItem(
    val id: Long,
    val summary: String,
    val fullText: String,
    val isCompleted: Boolean = false
)

/**
 * Modes that determine how todos are formatted for the HUD.
 */
enum class TodoDisplayMode {
    SUMMARY,
    FULL
}
