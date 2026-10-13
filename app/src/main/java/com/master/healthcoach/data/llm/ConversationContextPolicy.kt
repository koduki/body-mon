package com.master.healthcoach.data.llm

import com.master.healthcoach.data.db.GoalEntity

/** Settings are explicit data; pasted reference prose and previous advice are not profile edits. */
internal object ConversationContextPolicy {
    const val VERSION = "2026-10-13-evidence-v2"
    private val legacyReview = Regex("(?m)^専門家ビュー（端末内KPI判定）")
    private val age = Regex("(\\d{1,3})\\s*歳")
    private val height = Regex("(\\d{2,3}(?:\\.\\d+)?)\\s*cm", RegexOption.IGNORE_CASE)

    fun historicalAssistantContext(content: String): String =
        legacyReview.find(content)?.let { content.substring(0, it.range.first).trim() } ?: content

    fun profileConflicts(memory: String?, goal: GoalEntity?): List<String> = buildList {
        if (goal == null || memory.isNullOrBlank()) return@buildList
        val heights = height.findAll(memory).map { it.groupValues[1].toDouble() }.toSet()
        if (goal.heightCm != null && heights.any { kotlin.math.abs(it - goal.heightCm) > 0.1 }) {
            add("身長が設定とメモリーで異なります。設定は${goal.heightCm}cm。引用文章の数値を本人情報として採用しません")
        }
        val ages = age.findAll(memory).map { it.groupValues[1].toInt() }.toSet()
        if (goal.age != null && ages.any { it != goal.age }) {
            add("年齢が設定とメモリーで異なります。設定は${goal.age}歳。確認までは設定を使用します")
        }
    }

    fun memoryForModel(memory: String?, goal: GoalEntity?): String {
        // Do not mutate the original memory; only remove physical profile duplicates from model context.
        val cleaned = height.replace(age.replace(memory.orEmpty(), "[年齢は設定参照]"), "[身長は設定参照]")
        return (listOf(cleaned) + profileConflicts(memory, goal)).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun memoryInput(content: String): String =
        if (content.length > 500 && (content.contains("推奨") || content.contains("|---") || content.contains("結論"))) {
            "[参考文章を含む発言。引用内のプロフィール・科学的主張・提案は本人の確認済み情報ではない]\n$content"
        } else content
}
