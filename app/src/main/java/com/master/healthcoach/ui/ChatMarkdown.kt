package com.master.healthcoach.ui

/** Small, literal-first renderer model. No HTML execution, remote images or hidden links. */
internal sealed interface ChatBlock {
    data class Paragraph(val text: String) : ChatBlock
    data class Heading(val text: String, val level: Int) : ChatBlock
    data class Bullet(val text: String, val marker: String) : ChatBlock
    data class Quote(val text: String) : ChatBlock
    data class Code(val text: String, val language: String) : ChatBlock
    data class Table(val rows: List<List<String>>) : ChatBlock
}

internal object ChatMarkdown {
    fun parse(text: String): List<ChatBlock> {
        val lines = text.lines()
        val result = mutableListOf<ChatBlock>()
        val paragraph = mutableListOf<String>()
        fun flush() { if (paragraph.isNotEmpty()) { result += ChatBlock.Paragraph(paragraph.joinToString("\n")); paragraph.clear() } }
        fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val language = trimmed.removePrefix("```").trim()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    result += ChatBlock.Code(code.joinToString("\n"), language)
                    if (i < lines.size) i++
                    continue
                }
                i + 1 < lines.size && line.contains('|') && cells(lines[i + 1]).let { row ->
                    row.size >= 2 && row.all { it.matches(Regex(":?-{3,}:?")) }
                } -> {
                    flush()
                    val rows = mutableListOf(cells(line))
                    i += 2
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows += cells(lines[i++])
                    result += ChatBlock.Table(rows)
                    continue
                }
                trimmed.isEmpty() -> flush()
                trimmed.matches(Regex("#{1,6} .+")) -> {
                    flush(); val level = trimmed.takeWhile { it == '#' }.length
                    result += ChatBlock.Heading(trimmed.drop(level + 1), level)
                }
                trimmed.startsWith("> ") -> { flush(); result += ChatBlock.Quote(trimmed.drop(2)) }
                trimmed.matches(Regex("([-*+] |\\d+\\. ).+")) -> {
                    flush(); val marker = trimmed.substringBefore(' ')
                    result += ChatBlock.Bullet(trimmed.substringAfter(' '), if (marker in listOf("-", "*", "+")) "•" else marker)
                }
                else -> paragraph += line
            }
            i++
        }
        flush()
        return result
    }
}
