package com.master.healthcoach.data.llm

import java.time.LocalDate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only direct personal evaluations prefetch. General physiology questions remain conversational. */
internal object ChatMetricPrefetch {
    fun calls(message: String, today: LocalDate): List<GeminiFunctionCall> {
        val personal = Regex("どう思|どのくらい|今日|今朝|今週|最新|私|直近").containsMatchIn(message)
        if (!personal) return emptyList()
        val completeEnd = today.minusDays(1)
        fun call(name: String, from: LocalDate, to: LocalDate) = GeminiFunctionCall(name, buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
        })
        return when {
            message.contains("消費カロリー") || message.contains("歩数") -> listOf(
                call("get_activity_summary", completeEnd.minusDays(6), completeEnd),
                call("get_activity_summary", today, today),
            )
            Regex("体重|体脂肪|脂肪量|除脂肪").containsMatchIn(message) -> listOf(
                call("get_body_composition", today.minusDays(27), today),
            )
            Regex("休む|休み|休養|リングフィット|RFA|HIIT").containsMatchIn(message) -> listOf(
                call("get_exercise_summary", today.minusDays(3), today),
                call("get_sleep_summary", today.minusDays(6), today),
            )
            else -> emptyList()
        }
    }
}
