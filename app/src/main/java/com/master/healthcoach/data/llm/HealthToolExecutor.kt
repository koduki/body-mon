package com.master.healthcoach.data.llm

import com.master.healthcoach.data.HealthRepository
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class HealthToolExecutor(private val repository: HealthRepository) {
    suspend fun execute(call: GeminiFunctionCall): JsonElement = when (call.name) {
        "get_body_composition" -> body(call)
        "get_activity_summary" -> activity(call)
        "get_exercise_summary" -> exercise(call)
        "get_goal_progress" -> goals()
        else -> buildJsonObject { put("error", "未対応の関数: ${call.name}") }
    }

    private fun range(call: GeminiFunctionCall): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        val to = call.arguments["to"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        val from = call.arguments["from"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: to.minusDays(27)
        return if (from <= to) from to to else to to from
    }

    private suspend fun body(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getBody(from, to)
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("measurementDays", items.size)
            put("records", buildJsonArray {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        number("weightKg", item.weightKg)
                        number("bodyFatPercent", item.bodyFatPercent)
                        number("fatMassKg", item.fatMassKg)
                        number("leanBodyMassKg", item.leanBodyMassKg)
                        item.leanMassSource?.let { put("leanMassSource", it) }
                    })
                }
            })
        }
    }

    private suspend fun activity(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getDaily(from, to)
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            number("stepsDailyAverage", items.mapNotNull { it.steps }.averageOrNull())
            number(
                "activeCaloriesDailyAverage",
                items.mapNotNull { it.activeCaloriesKcal }.averageOrNull(),
            )
            put("daily", buildJsonArray {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        item.steps?.let { put("steps", it) }
                        number("distanceMeters", item.distanceMeters)
                        number("activeCaloriesKcal", item.activeCaloriesKcal)
                    })
                }
            })
        }
    }

    private suspend fun exercise(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getDaily(from, to)
        val sessions = repository.getExerciseSessions(from, to)
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("sessions", items.sumOf { it.exerciseSessionCount })
            put("minutes", items.sumOf { it.exerciseMinutes })
            put("strengthMinutes", items.sumOf { it.strengthMinutes })
            put("cardioMinutes", items.sumOf { it.cardioMinutes })
            put("daily", buildJsonArray {
                items.filter { it.exerciseSessionCount > 0 }.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        put("sessions", item.exerciseSessionCount)
                        put("minutes", item.exerciseMinutes)
                        put("strengthMinutes", item.strengthMinutes)
                        put("cardioMinutes", item.cardioMinutes)
                    })
                }
            })
            put("recentSessions", buildJsonArray {
                sessions.takeLast(30).forEach { session ->
                    add(buildJsonObject {
                        put("startEpochMillis", session.startEpochMillis)
                        put("exercise", session.exerciseLabel)
                        put("category", session.category)
                        put("durationMinutes", session.durationMinutes)
                    })
                }
            })
        }
    }

    private suspend fun goals(): JsonElement {
        val goal = repository.getGoal()
            ?: return buildJsonObject { put("status", "目標未設定") }
        val today = LocalDate.now()
        val latestBody = repository.getBody(today.minusDays(7), today).lastOrNull()
        val latestActivity = repository.getDaily(today.minusDays(6), today)
        return buildJsonObject {
            goal.age?.let { put("age", it) }
            number("heightCm", goal.heightCm)
            goal.sex?.let { put("sex", it) }
            goal.deadline?.let { put("deadline", it) }
            number("targetFatMassKg", goal.targetFatMassKg)
            number("minimumLeanMassKg", goal.minimumLeanMassKg)
            goal.dailySteps?.let { put("dailySteps", it) }
            goal.weeklyExerciseSessions?.let { put("weeklyExerciseSessions", it) }
            number("dailyActiveCaloriesKcal", goal.dailyActiveCaloriesKcal)
            number("latestFatMassKg", latestBody?.fatMassKg)
            number("latestLeanBodyMassKg", latestBody?.leanBodyMassKg)
            number("currentStepsDailyAverage", latestActivity.mapNotNull { it.steps }.averageOrNull())
            put("currentExerciseSessions", latestActivity.sumOf { it.exerciseSessionCount })
        }
    }

    private fun List<Number>.averageOrNull(): Double? =
        if (isEmpty()) null else sumOf { it.toDouble() } / size

    private fun kotlinx.serialization.json.JsonObjectBuilder.number(name: String, value: Number?) {
        if (value == null || value.toDouble().isNaN()) put(name, JsonNull)
        else put(name, JsonPrimitive(value))
    }
}
