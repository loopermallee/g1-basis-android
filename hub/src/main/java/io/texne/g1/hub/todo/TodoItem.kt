package io.texne.g1.hub.todo

/**
 * Represents a task tracked by the heads-up display.
 */
data class TodoItem(
    val id: String,
    val shortText: String,
    val fullText: String,
    val isDone: Boolean,
    val archivedAt: Long?,
    val position: Int
)
