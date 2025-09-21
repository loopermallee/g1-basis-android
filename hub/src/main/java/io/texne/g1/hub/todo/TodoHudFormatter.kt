package io.texne.g1.hub.todo

import io.texne.g1.basis.client.MAX_CHARACTERS_PER_LINE
import kotlin.math.min

object TodoHudFormatter {
    const val HUD_CHECKED_ICON = "[✔]"
    const val HUD_UNCHECKED_ICON = "[ ]"
    const val MAX_ITEMS_PER_PAGE = 4
    private const val MAX_CHAR_PER_LINE = MAX_CHARACTERS_PER_LINE
    private const val TITLE = "TODO"
    private const val EMPTY_MESSAGE = "All caught up!"
    private const val ELLIPSIS = "..."
    private val whitespaceRegex = Regex("""\s+""")

    data class Result(
        val pages: List<List<String>>
    )

    enum class DisplayMode { SUMMARY, FULL }

    fun format(
        tasks: List<TodoItem>,
        displayMode: DisplayMode,
        expandedTaskId: String?
    ): Result {
        if (expandedTaskId != null) {
            val expandedIndex = tasks.indexOfFirst { it.id == expandedTaskId }
            if (expandedIndex != -1) {
                return Result(pages = formatExpanded(tasks[expandedIndex], expandedIndex))
            }
        }

        val lines = if (tasks.isEmpty()) {
            listOf(TITLE, EMPTY_MESSAGE)
        } else {
            tasks.mapIndexed { index, item ->
                val content = when (displayMode) {
                    DisplayMode.SUMMARY -> item.shortText
                    DisplayMode.FULL -> item.fullText
                }
                buildListLine(index + 1, item.isDone, content)
            }
        }

        val pages = lines.chunked(MAX_ITEMS_PER_PAGE).ifEmpty {
            listOf(listOf(TITLE, EMPTY_MESSAGE))
        }

        return Result(pages = pages)
    }

    private fun formatExpanded(
        item: TodoItem,
        index: Int
    ): List<List<String>> {
        val header = buildListLine(index + 1, item.isDone, item.shortText)
        val body = wrapFullText(item.fullText)
        if (body.isEmpty()) {
            return listOf(listOf(header))
        }

        val pages = mutableListOf<List<String>>()
        var cursor = 0
        while (cursor < body.size) {
            val chunkEnd = min(cursor + (MAX_ITEMS_PER_PAGE - 1), body.size)
            val chunk = body.subList(cursor, chunkEnd)
            pages += listOf(header) + chunk
            cursor += MAX_ITEMS_PER_PAGE - 1
        }

        return pages
    }

    private fun buildListLine(index: Int, isDone: Boolean, rawText: String): String {
        val base = sanitize(rawText)
        val icon = if (isDone) HUD_CHECKED_ICON else HUD_UNCHECKED_ICON
        val composed = "$index. $icon $base"
        return trimToLimit(composed)
    }

    private fun sanitize(text: String): String {
        if (text.isBlank()) {
            return ""
        }
        return text
            .replace('\n', ' ')
            .replace(whitespaceRegex, " ")
            .trim()
    }

    private fun wrapFullText(text: String): List<String> {
        val normalized = sanitize(text)
        if (normalized.isEmpty()) {
            return emptyList()
        }

        val lines = mutableListOf<String>()
        var current = StringBuilder()

        fun flushCurrent() {
            if (current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder()
            }
        }

        normalized.split(' ').forEach { word ->
            if (word.isEmpty()) return@forEach
            if (word.length > MAX_CHAR_PER_LINE) {
                flushCurrent()
                lines += chunkLongWord(word)
                return@forEach
            }

            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (candidate.length <= MAX_CHAR_PER_LINE) {
                current.clear()
                current.append(candidate)
            } else {
                flushCurrent()
                current.append(word)
            }
        }

        flushCurrent()

        return lines
    }

    private fun chunkLongWord(word: String): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            val end = min(start + MAX_CHAR_PER_LINE, word.length)
            chunks += word.substring(start, end)
            start = end
        }
        return chunks
    }

    private fun trimToLimit(text: String): String {
        if (text.length <= MAX_CHAR_PER_LINE) {
            return text
        }
        val limit = MAX_CHAR_PER_LINE - ELLIPSIS.length
        if (limit <= 0) {
            return ELLIPSIS.take(MAX_CHAR_PER_LINE)
        }
        return text.take(limit) + ELLIPSIS
    }
}
