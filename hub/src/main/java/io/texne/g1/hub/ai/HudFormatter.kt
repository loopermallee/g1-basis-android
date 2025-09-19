package io.texne.g1.hub.ai

object HudFormatter {
    private val whitespaceRegex = Regex("""\s+""")

    data class Result(
        val lines: List<String>,
        val truncated: Boolean
    )

    fun format(text: String, maxLines: Int = 5, maxCharsPerLine: Int = 40): Result {
        val normalized = text
            .replace('\n', ' ')
            .replace(whitespaceRegex, " ")
            .trim()

        if (normalized.isEmpty()) {
            return Result(lines = listOf(""), truncated = false)
        }

        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var truncated = false

        val words = normalized.split(" ")
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (candidate.length <= maxCharsPerLine) {
                current.clear()
                current.append(candidate)
            } else {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current.clear()
                }
                if (word.length > maxCharsPerLine) {
                    val chunks = word.chunked(maxCharsPerLine)
                    for (chunk in chunks.dropLast(1)) {
                        lines += chunk
                        if (lines.size == maxLines) {
                            truncated = true
                            break
                        }
                    }
                    if (truncated) {
                        break
                    }
                    current.append(chunks.last())
                } else {
                    current.append(word)
                }
            }

            if (lines.size == maxLines) {
                truncated = true
                break
            }
        }

        if (!truncated && current.isNotEmpty()) {
            lines += current.toString()
        } else if (truncated && lines.size == maxLines - 1 && current.isNotEmpty()) {
            lines += current.toString()
        }

        if (lines.isEmpty() && current.isNotEmpty()) {
            lines += current.toString()
        }

        if (lines.size > maxLines) {
            truncated = true
        }

        val trimmedLines = if (lines.size > maxLines) {
            lines.take(maxLines)
        } else {
            lines
        }

        val output = if (truncated && trimmedLines.isNotEmpty()) {
            val last = trimmedLines.last()
            val adjusted = if (last.length >= maxCharsPerLine) {
                last.take(maxCharsPerLine - 1) + "…"
            } else {
                (last + " …").take(maxCharsPerLine)
            }
            trimmedLines.dropLast(1) + adjusted
        } else {
            trimmedLines
        }

        return Result(lines = output, truncated = truncated)
    }
}
