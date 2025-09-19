package io.texne.g1.hub.todo

/**
 * Formats todo items into HUD friendly pages. Each page contains exactly [LINES_PER_PAGE] lines
 * capped at [MAX_CHARACTERS_PER_LINE] characters (respecting both the 50 character design target
 * and the HUD's 40 character hardware limit). Summary lines are truncated with ellipsis while
 * expanded pages leverage the repository's stored [TodoItem.fullText].
 */
object TodoHudFormatter {
    const val LINES_PER_PAGE = 4
    private const val SPEC_MAX_CHARACTERS_PER_LINE = 50
    private const val HUD_CHARACTER_LIMIT = 40
    private val MAX_CHARACTERS_PER_LINE =
        minOf(SPEC_MAX_CHARACTERS_PER_LINE, HUD_CHARACTER_LIMIT)
    private const val ELLIPSIS = "..."
    private const val DETAIL_INDENT = "   "
    private val whitespaceRegex = Regex("""\s+""")

    fun format(
        tasks: List<TodoItem>,
        mode: TodoDisplayMode,
        expandedTaskId: Long? = null
    ): List<List<String>> {
        if (tasks.isEmpty()) {
            return emptyList()
        }

        val numbering = tasks.mapIndexed { index, task ->
            task.id to (index + 1)
        }.toMap()

        val lines = when {
            mode == TodoDisplayMode.SUMMARY -> buildSummaryLines(tasks, numbering)
            expandedTaskId != null -> {
                val task = tasks.firstOrNull { it.id == expandedTaskId }
                if (task != null) {
                    buildFullLines(listOf(task), numbering)
                } else {
                    buildFullLines(tasks, numbering)
                }
            }
            else -> buildFullLines(tasks, numbering)
        }

        return lines
            .chunked(LINES_PER_PAGE)
            .map { chunk -> padToPage(chunk) }
    }

    private fun buildSummaryLines(
        tasks: List<TodoItem>,
        numbering: Map<Long, Int>
    ): List<String> {
        return tasks.map { task ->
            val index = numbering[task.id] ?: 0
            val status = if (task.isCompleted) "[✔]" else "[ ]"
            val raw = "$index. $status ${task.summary}".trim()
            truncate(raw)
        }
    }

    private fun buildFullLines(
        tasks: List<TodoItem>,
        numbering: Map<Long, Int>
    ): List<String> {
        val lines = mutableListOf<String>()
        tasks.forEach { task ->
            val index = numbering[task.id] ?: 0
            val status = if (task.isCompleted) "[✔]" else "[ ]"
            val header = "$index. $status ${task.summary}".trim()
            lines += truncate(header)

            val wrappedDetails = wrapText(task.fullText, MAX_CHARACTERS_PER_LINE - DETAIL_INDENT.length)
            wrappedDetails.forEach { detail ->
                lines += truncate(DETAIL_INDENT + detail)
            }
        }
        return lines
    }

    private fun padToPage(lines: List<String>): List<String> {
        val padded = lines.toMutableList()
        while (padded.size < LINES_PER_PAGE) {
            padded += ""
        }
        return padded.take(LINES_PER_PAGE)
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_CHARACTERS_PER_LINE) {
            return text
        }
        if (MAX_CHARACTERS_PER_LINE <= ELLIPSIS.length) {
            return ELLIPSIS.take(MAX_CHARACTERS_PER_LINE)
        }
        return text.take(MAX_CHARACTERS_PER_LINE - ELLIPSIS.length) + ELLIPSIS
    }

    private fun wrapText(text: String, maxChars: Int): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val normalized = text
            .replace('\n', ' ')
            .replace(whitespaceRegex, " ")
            .trim()

        if (normalized.isEmpty()) {
            return emptyList()
        }

        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        fun flush() {
            if (currentLine.isNotEmpty()) {
                lines += currentLine.toString()
                currentLine = StringBuilder()
            }
        }

        normalized.split(" ").forEach { rawWord ->
            if (rawWord.isEmpty()) return@forEach

            var word = rawWord
            while (word.length > maxChars) {
                if (currentLine.isNotEmpty()) {
                    flush()
                }
                lines += word.take(maxChars)
                word = word.drop(maxChars)
            }

            val candidate = if (currentLine.isEmpty()) word else "${currentLine} $word"
            if (candidate.length <= maxChars) {
                currentLine.clear()
                currentLine.append(candidate)
            } else {
                flush()
                currentLine.append(word)
            }
        }

        flush()
        return lines
    }
}
