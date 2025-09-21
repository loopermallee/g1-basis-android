package io.texne.g1.hub.todo

import io.texne.g1.basis.client.MAX_CHARACTERS_PER_LINE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodoHudFormatterTest {

    @Test
    fun summaryFormattingClampsLongLinesToLimit() {
        val longText = "A".repeat(MAX_CHARACTERS_PER_LINE * 2)
        val item = TodoItem(
            id = "1",
            shortText = longText,
            fullText = longText,
            isDone = false,
            archivedAt = null,
            position = 0
        )

        val result = TodoHudFormatter.format(
            tasks = listOf(item),
            displayMode = TodoHudFormatter.DisplayMode.SUMMARY,
            expandedTaskId = null
        )

        val line = result.pages.single().single()

        assertEquals(MAX_CHARACTERS_PER_LINE, line.length)
        assertTrue(line.endsWith("..."))
    }

    @Test
    fun summaryFormattingRetainsTextAtLimit() {
        val prefixLength = "1. ${TodoHudFormatter.HUD_UNCHECKED_ICON} ".length
        val content = "B".repeat(MAX_CHARACTERS_PER_LINE - prefixLength)
        val item = TodoItem(
            id = "1",
            shortText = content,
            fullText = content,
            isDone = false,
            archivedAt = null,
            position = 0
        )

        val result = TodoHudFormatter.format(
            tasks = listOf(item),
            displayMode = TodoHudFormatter.DisplayMode.SUMMARY,
            expandedTaskId = null
        )

        val line = result.pages.single().single()

        assertEquals(MAX_CHARACTERS_PER_LINE, line.length)
        assertEquals("1. ${TodoHudFormatter.HUD_UNCHECKED_ICON} $content", line)
    }

    @Test
    fun expandedFormattingWrapsLongWordsWithinLimit() {
        val longWord = "C".repeat(MAX_CHARACTERS_PER_LINE + 5)
        val item = TodoItem(
            id = "1",
            shortText = "Short header",
            fullText = longWord,
            isDone = true,
            archivedAt = null,
            position = 0
        )

        val result = TodoHudFormatter.format(
            tasks = listOf(item),
            displayMode = TodoHudFormatter.DisplayMode.FULL,
            expandedTaskId = item.id
        )

        val allLines = result.pages.flatten()
        assertTrue(allLines.all { it.length <= MAX_CHARACTERS_PER_LINE })

        val bodyLines = result.pages.flatMap { it.drop(1) }
        assertEquals(
            listOf(
                longWord.take(MAX_CHARACTERS_PER_LINE),
                longWord.drop(MAX_CHARACTERS_PER_LINE)
            ),
            bodyLines
        )
    }
}
