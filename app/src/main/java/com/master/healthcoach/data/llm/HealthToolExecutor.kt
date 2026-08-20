package com.master.healthcoach.data.llm

import com.master.healthcoach.data.HealthRepository
import com.master.healthcoach.data.db.estimatedEnergyBalanceKcal
import com.master.healthcoach.domain.NutritionMacros
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
        "get_sleep_summary" -> sleep(call)
        "get_heart_rate_summary" -> heartRate(call)
        "get_activity_intensity_summary" -> intensity(call)
        "get_metabolism_summary" -> metabolism(call)
        "get_nutrition_summary" -> nutrition(call)
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
            put("morningRoutineMinutes", items.sumOf { it.morningRoutineMinutes })
            put("cardioMinutes", items.sumOf { it.cardioMinutes })
            put(
                "morningRoutineInterpretation",
                "Other Workoutを朝の5分ルーティン（軽い筋トレ＋有酸素）として扱う",
            )
            put("daily", buildJsonArray {
                items.filter { it.exerciseSessionCount > 0 }.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        put("sessions", item.exerciseSessionCount)
                        put("minutes", item.exerciseMinutes)
                        put("strengthMinutes", item.strengthMinutes)
                        put("morningRoutineMinutes", item.morningRoutineMinutes)
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

    private suspend fun sleep(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getDaily(from, to)
        val measured = items.filter { it.sleepMinutes != null }
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("measurementDays", measured.size)
            number("sleepDailyAverageMinutes", measured.mapNotNull {
                it.sleepMinutes
            }.averageOrNull())
            put("daily", buildJsonArray {
                measured.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        item.sleepMinutes?.let { put("sleepMinutes", it) }
                    })
                }
            })
        }
    }

    private suspend fun heartRate(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getDaily(from, to)
        val measured = items.filter { it.heartRateAverageBpm != null }
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("measurementDays", measured.size)
            number("averageBpm", measured.mapNotNull {
                it.heartRateAverageBpm
            }.averageOrNull())
            measured.mapNotNull { it.heartRateMinimumBpm }.minOrNull()?.let {
                put("minimumBpm", it)
            }
            measured.mapNotNull { it.heartRateMaximumBpm }.maxOrNull()?.let {
                put("maximumBpm", it)
            }
            put("measurementCount", measured.sumOf {
                it.heartRateMeasurementCount ?: 0
            })
            put("daily", buildJsonArray {
                measured.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        item.heartRateAverageBpm?.let { put("averageBpm", it) }
                        item.heartRateMinimumBpm?.let { put("minimumBpm", it) }
                        item.heartRateMaximumBpm?.let { put("maximumBpm", it) }
                    })
                }
            })
        }
    }

    private suspend fun intensity(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getDaily(from, to)
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            number(
                "moderateIntensityMinutes",
                items.mapNotNull { it.moderateIntensityMinutes }.sumOrNull(),
            )
            number(
                "vigorousIntensityMinutes",
                items.mapNotNull { it.vigorousIntensityMinutes }.sumOrNull(),
            )
            put("daily", buildJsonArray {
                items.filter {
                    it.moderateIntensityMinutes != null ||
                        it.vigorousIntensityMinutes != null
                }.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        item.moderateIntensityMinutes?.let { put("moderateMinutes", it) }
                        item.vigorousIntensityMinutes?.let { put("vigorousMinutes", it) }
                    })
                }
            })
        }
    }

    private suspend fun metabolism(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getDaily(from, to)
        val measured = items.filter { it.basalCaloriesKcal != null }
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("measurementDays", measured.size)
            number("basalCaloriesDailyAverage", measured.mapNotNull {
                it.basalCaloriesKcal
            }.averageOrNull())
            put("daily", buildJsonArray {
                measured.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        number("basalCaloriesKcal", item.basalCaloriesKcal)
                    })
                }
            })
        }
    }

    private suspend fun nutrition(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getDaily(from, to)
        val measured = items.filter { it.intakeCaloriesKcal != null }
        val intakeAverage = measured.mapNotNull { it.intakeCaloriesKcal }.averageOrNull()
        val proteinAverage = items.mapNotNull { it.proteinGrams }.averageOrNull()
        val fatAverage = items.mapNotNull { it.totalFatGrams }.averageOrNull()
        val carbohydrateAverage = items.mapNotNull { it.carbohydrateGrams }.averageOrNull()
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("measurementDays", measured.size)
            put(
                "sourceNote",
                "あすけんからHealth Connectへ書き出されるのは摂取カロリー・たんぱく質・脂質・炭水化物。" +
                    "欠測日は0kcalとせず、未記録として扱う。" +
                    "推定エネルギー収支は摂取−基礎代謝−活動消費で、デバイス推定のため参考値。" +
                    "PFCエネルギー比は記録グラム×4/9/4kcalを摂取カロリーで割った参考値。",
            )
            number("intakeCaloriesDailyAverage", intakeAverage)
            number("proteinDailyAverageGrams", proteinAverage)
            number("totalFatDailyAverageGrams", fatAverage)
            number("carbohydrateDailyAverageGrams", carbohydrateAverage)
            number(
                "proteinEnergyPercent",
                NutritionMacros.proteinEnergyPercent(proteinAverage, intakeAverage),
            )
            number(
                "fatEnergyPercent",
                NutritionMacros.fatEnergyPercent(fatAverage, intakeAverage),
            )
            number(
                "carbohydrateEnergyPercent",
                NutritionMacros.carbohydrateEnergyPercent(
                    carbohydrateAverage,
                    intakeAverage,
                ),
            )
            put("daily", buildJsonArray {
                measured.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        number("intakeCaloriesKcal", item.intakeCaloriesKcal)
                        number("proteinGrams", item.proteinGrams)
                        number("totalFatGrams", item.totalFatGrams)
                        number("carbohydrateGrams", item.carbohydrateGrams)
                        number(
                            "estimatedEnergyBalanceKcal",
                            item.estimatedEnergyBalanceKcal,
                        )
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
            goal.weeklyExerciseSessions?.let {
                put("weeklyMorningRoutineTargetDays", it)
            }
            number("dailyActiveCaloriesKcal", goal.dailyActiveCaloriesKcal)
            number("latestFatMassKg", latestBody?.fatMassKg)
            number("latestLeanBodyMassKg", latestBody?.leanBodyMassKg)
            number("currentStepsDailyAverage", latestActivity.mapNotNull { it.steps }.averageOrNull())
            put("currentExerciseSessions", latestActivity.sumOf { it.exerciseSessionCount })
        }
    }

    private fun List<Number>.averageOrNull(): Double? =
        if (isEmpty()) null else sumOf { it.toDouble() } / size

    private fun List<Long>.sumOrNull(): Long? = if (isEmpty()) null else sum()

    private fun kotlinx.serialization.json.JsonObjectBuilder.number(name: String, value: Number?) {
        if (value == null || value.toDouble().isNaN()) put(name, JsonNull)
        else put(name, JsonPrimitive(value))
    }
}
