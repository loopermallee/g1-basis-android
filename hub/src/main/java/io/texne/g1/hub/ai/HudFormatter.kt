package io.texne.g1.hub.ai

object HudFormatter {
    private const val DEFAULT_MAX_LINES_PER_PAGE = 4
    private const val DEFAULT_MAX_CHARS_PER_LINE = 32
    private const val ELLIPSIS = "..."
    private val whitespaceRegex = Regex("""\s+""")

    data class Result(
        val pages: List<List<String>>,
        val truncated: Boolean
    )

    fun format(
        text: String,
        maxLinesPerPage: Int = DEFAULT_MAX_LINES_PER_PAGE,
        maxCharsPerLine: Int = DEFAULT_MAX_CHARS_PER_LINE
    ): Result {
        require(maxLinesPerPage > 0) { "maxLinesPerPage must be greater than 0" }
        require(maxCharsPerLine > 0) { "maxCharsPerLine must be greater than 0" }

        val normalized = text
            .replace('\n', ' ')
            .replace(whitespaceRegex, " ")
            .trim()

        if (normalized.isEmpty()) {
            return Result(pages = listOf(listOf("")), truncated = false)
        }

        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        var truncated = false

        fun flushLine() {
            if (currentLine.isNotEmpty()) {
                lines += currentLine.toString()
                currentLine = StringBuilder()
            }
        }

        normalized.split(" ").forEach { rawWord ->
            if (rawWord.isEmpty()) return@forEach

            val word = if (rawWord.length > maxCharsPerLine) {
                truncated = true
                truncateToFit(rawWord, maxCharsPerLine)
            } else {
                rawWord
            }

            val candidate = if (currentLine.isEmpty()) word else "${currentLine} $word"

            if (candidate.length <= maxCharsPerLine) {
                currentLine.clear()
                currentLine.append(candidate)
            } else {
                flushLine()
                if (word.length <= maxCharsPerLine) {
                    currentLine.append(word)
                } else {
                    lines += word
                }
            }
        }

        flushLine()

        if (lines.isEmpty()) {
            lines += ""
        }

        val sanitizedLines = lines.map { line ->
            if (line.length <= maxCharsPerLine) {
                line
            } else {
                truncated = true
                truncateToFit(line, maxCharsPerLine)
            }
        }

        val pages = sanitizedLines.chunked(maxLinesPerPage).ifEmpty {
            listOf(listOf(""))
        }

        return Result(pages = pages, truncated = truncated)
    }

    private fun truncateToFit(text: String, maxChars: Int): String {
        if (maxChars <= ELLIPSIS.length) {
            return ELLIPSIS.take(maxChars)
        }
        return text.take(maxChars - ELLIPSIS.length) + ELLIPSIS
    }
}
