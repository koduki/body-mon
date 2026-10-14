package com.master.healthcoach.data.llm

import com.master.healthcoach.domain.NutritionDetailPolicy

import com.master.healthcoach.data.HealthRepository
import com.master.healthcoach.domain.EnergyModel
import com.master.healthcoach.domain.NutritionMacros
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class HealthQueryService(
    private val repository: HealthRepository,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {
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
        val today = todayProvider()
        val to = call.arguments["to"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        val from = call.arguments["from"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: to.minusDays(27)
        return if (from <= to) from to to else to to from
    }

    private fun JsonObjectBuilder.putChronologyHeader(
        from: LocalDate,
        to: LocalDate,
        measuredDates: Set<String>,
    ) {
        val today = todayProvider()
        val yesterday = today.minusDays(1)
        put("todayDate", today.toString())
        put("yesterdayDate", yesterday.toString())
        put("from", from.toString())
        put("to", to.toString())
        put("recordPresenceScope", "requested_range_only")
        put("todayCumulativeIsPartial", to >= today)
        put("recommendedCompletedDayEnd", yesterday.toString())
        put("hasTodayRecord", today.toString() in measuredDates)
        put("hasYesterdayRecord", yesterday.toString() in measuredDates)
    }

    private fun relativeDay(dateStr: String): String {
        val today = todayProvider()
        val parsed = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return dateStr
        return ChatHistoryPolicy.relativeDayLabel(parsed, today)
    }

    private fun formatEpochLocalTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zoneIdProvider())
            .format(LOCAL_TIME_FORMATTER)

    private fun formatEpochLocalDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zoneIdProvider())
            .toLocalDate()
            .toString()

    private suspend fun body(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getBody(from, to)
        val trendWindowBody = repository.getBody(to.minusDays(27), to)
        val trendSummary = bodyTrendSummary(to, trendWindowBody, zoneIdProvider())
        val measuredDates = items.filter { it.weightKg != null }.map { it.date }.toSet()
        return buildJsonObject {
            putChronologyHeader(from, to, measuredDates)
            put("measurementDays", measuredDates.size)
            trendSummary.forEach { (key, value) -> put(key, value) }
            put("records", buildJsonArray {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        put("relativeDay", relativeDay(item.date))
                        put("isPartialDay", false)
                        put("recordKind", "point_measurement")
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
        val goal = repository.getGoal()
        val body = repository.getBody(
            from.minusDays(EnergyModel.WEIGHT_FALLBACK_DAYS),
            to,
        )
        val summary = EnergyModel.summarizeActive(items, body, goal?.heightCm, goal?.sex)
        val measuredDates = items.filter {
            it.steps != null || it.activeCaloriesKcal != null
        }.map { it.date }.toSet()
        return buildJsonObject {
            putChronologyHeader(from, to, measuredDates)
            number("stepsDailyAverage", items.mapNotNull { it.steps }.averageOrNull())
            number(
                "activeCaloriesDailyAverage",
                items.mapNotNull { it.activeCaloriesKcal }.averageOrNull(),
            )
            put(
                "referenceNote",
                EnergyModel.INTERPRETATION + " modelVersion=${EnergyModel.VERSION}",
            )
            summary?.let {
                number("referenceStepModelActiveKcalDailyAverage", it.stepModelDailyAverageKcal)
                number("referenceEvaluatedActiveKcalDailyAverage", it.evaluatedDailyAverageKcal)
                number("referenceActiveRangeLowKcal", it.range.lowKcal)
                number("referenceActiveRangeHighKcal", it.range.highKcal)
                put("referenceLargeDiscrepancyDays", it.largeDiscrepancyDays)
                put("referenceActiveValidDays", it.validDays)
            }
            put("daily", buildJsonArray {
                items.forEach { item ->
                    val estimate = EnergyModel.dailyActive(
                        item,
                        body,
                        goal?.heightCm,
                        goal?.sex,
                    )
                    add(buildJsonObject {
                        put("date", item.date)
                        put("relativeDay", relativeDay(item.date))
                        put("isPartialDay", item.date == todayProvider().toString())
                        item.steps?.let { put("steps", it) }
                        number("distanceMeters", item.distanceMeters)
                        put("cyclingMinutes", item.cyclingMinutes)
                        number("activeCaloriesKcal", item.activeCaloriesKcal)
                        put("metricOriginsJson", item.metricOriginsJson)
                        put("updatedAtEpochMillis", item.updatedAtEpochMillis)
                        estimate?.let {
                            number(
                                "referenceStepModelActiveKcal",
                                EnergyModel.roundForDisplay(it.stepModelKcal),
                            )
                            put("isLargeDiscrepancy", it.isLargeDiscrepancy)
                            number(
                                "referenceDiscrepancyHalfAddKcal",
                                EnergyModel.roundForDisplay(it.discrepancyReferenceAddKcal),
                            )
                            number(
                                "referenceEvaluatedActiveKcal",
                                EnergyModel.roundForDisplay(it.evaluatedKcal),
                            )
                            number(
                                "referenceActiveRangeLowKcal",
                                EnergyModel.roundForDisplay(it.range.lowKcal),
                            )
                            number(
                                "referenceActiveRangeHighKcal",
                                EnergyModel.roundForDisplay(it.range.highKcal),
                            )
                        }
                    })
                }
            })
        }
    }

    private suspend fun exercise(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getDaily(from, to)
        val sessions = repository.getExerciseSessions(from, to)
        val measuredDates = items.filter { it.exerciseSessionCount > 0 }.map { it.date }.toSet()
        return buildJsonObject {
            putChronologyHeader(from, to, measuredDates)
            put("sessions", sessions.map { it.recordId }.distinct().size)
            put("minutes", items.sumOf { it.exerciseMinutes })
            put("strengthMinutes", items.sumOf { it.strengthMinutes })
            put("morningRoutineMinutes", items.sumOf { it.morningRoutineMinutes })
            put("morningRoutineDays", items.count { it.morningRoutineMinutes > 0 })
            put("cardioMinutes", items.sumOf { it.cardioMinutes })
            put("hikingMinutes", items.sumOf { it.hikingMinutes })
            put("hiitMinutes", items.sumOf { it.hiitMinutes })
            put(
                "morningRoutineInterpretation",
                "朝4〜10時開始・1〜15分のOther Workoutを朝トレ候補として扱う。一般運動・RFA・筋力維持の証明とは区別する",
            )
            put("daily", buildJsonArray {
                items.filter { it.exerciseSessionCount > 0 }.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        put("relativeDay", relativeDay(item.date))
                        put("isPartialDay", item.date == todayProvider().toString())
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
                    val sessionDate = formatEpochLocalDate(session.startEpochMillis)
                    add(buildJsonObject {
                        put("date", sessionDate)
                        put("relativeDay", relativeDay(sessionDate))
                        put("startTimeLocal", formatEpochLocalTime(session.startEpochMillis))
                        put("startEpochMillis", session.startEpochMillis)
                        put("exercise", session.exerciseLabel)
                        put("category", session.category)
                        put("durationMinutes", session.durationMinutes)
                        put("endEpochMillis", session.endEpochMillis)
                        val rangeStart = from.atStartOfDay(zoneIdProvider()).toInstant().toEpochMilli()
                        val rangeEnd = to.plusDays(1).atStartOfDay(zoneIdProvider()).toInstant().toEpochMilli()
                        put("minutesInRequestedPeriod", ((minOf(session.endEpochMillis, rangeEnd) - maxOf(session.startEpochMillis, rangeStart)).coerceAtLeast(0) / 60_000))
                        put("intervalInterpretation", "original_session_times; daily_minutes_are_clipped_to_each_day")
                    })
                }
            })
        }
    }

    private suspend fun sleep(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getDaily(from, to)
        val measured = items.filter { it.sleepMinutes != null }
        val measuredDates = measured.map { it.date }.toSet()
        return buildJsonObject {
            putChronologyHeader(from, to, measuredDates)
            put("measurementDays", measured.size)
            put("sleepBaselineMinutes", SLEEP_BASELINE_MINUTES)
            put("sleepBaselineInterpretation", "6時間は本人の最低運用目標。回復十分の判定ではない。達成日はセッション枠で数える。")
            put("sleepDurationNote", "sleepMinutesは睡眠ステージ合計。ステージ欠測時はセッション枠で代用し、sleepDurationSourceで区別。sleep_stages_partialは既知区間のみの合計で一部欠測。")
            put(
                "sleepTargetHitDays",
                measured.count { (it.sleepSessionMinutes ?: it.sleepMinutes ?: 0L) >= SLEEP_BASELINE_MINUTES },
            )
            number(
                "sleepDailyAverageMinutes",
                measured.mapNotNull { it.sleepMinutes }.averageOrNull(),
            )
            number(
                "sleepHeartRateAverageBpm",
                items.mapNotNull { it.sleepHeartRateAverageBpm }.averageOrNull(),
            )
            put("daily", buildJsonArray {
                measured.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        put("relativeDay", relativeDay(item.date))
                        put("isPartialDay", false)
                        put("recordKind", "completed_sleep_session")
                        item.sleepMinutes?.let {
                            put("sleepMinutes", it)
                            put("meetsSixHourOpportunity", (item.sleepSessionMinutes ?: it) >= SLEEP_BASELINE_MINUTES)
                        }
                        number("sleepSessionMinutes", item.sleepSessionMinutes)
                        put("sleepDurationSource", item.sleepDurationSource)
                        put("sleepHeartRateStatus", if (item.sleepHeartRateAverageBpm == null) "no_samples_in_main_sleep" else "observed")
                        put("metricOriginsJson", item.metricOriginsJson)
                        item.sleepHeartRateAverageBpm?.let {
                            put("sleepHeartRateAverageBpm", it)
                        }
                    })
                }
            })
        }
    }

    private suspend fun heartRate(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getDaily(from, to)
        val measured = items.filter { it.heartRateAverageBpm != null }
        val measuredDates = measured.map { it.date }.toSet()
        return buildJsonObject {
            putChronologyHeader(from, to, measuredDates)
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
                        put("relativeDay", relativeDay(item.date))
                        put("isPartialDay", item.date == todayProvider().toString())
                        put("metricOriginsJson", item.metricOriginsJson)
                        item.heartRateCoverageMinutes?.let { put("coverageMinutes", it) }
                        item.heartRateOutsideExerciseSamples?.let { put("outsideExerciseSamples", it) }
                        put("interpretation", if (item.heartRateOutsideExerciseSamples == 0) "workout_only_not_resting" else "observed_samples_not_proven_resting")
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
        val measured = items.filter {
            it.moderateIntensityMinutes != null || it.vigorousIntensityMinutes != null
        }
        val measuredDates = measured.map { it.date }.toSet()
        return buildJsonObject {
            putChronologyHeader(from, to, measuredDates)
            number(
                "moderateIntensityMinutes",
                items.mapNotNull { it.moderateIntensityMinutes }.sumOrNull(),
            )
            number(
                "vigorousIntensityMinutes",
                items.mapNotNull { it.vigorousIntensityMinutes }.sumOrNull(),
            )
            put("daily", buildJsonArray {
                measured.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        put("relativeDay", relativeDay(item.date))
                        put("isPartialDay", item.date == todayProvider().toString())
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
        val measuredDates = measured.map { it.date }.toSet()
        return buildJsonObject {
            putChronologyHeader(from, to, measuredDates)
            put("measurementDays", measured.size)
            number("basalCaloriesDailyAverage", measured.mapNotNull {
                it.basalCaloriesKcal
            }.averageOrNull())
            put("daily", buildJsonArray {
                measured.forEach { item ->
                    add(buildJsonObject {
                        put("date", item.date)
                        put("relativeDay", relativeDay(item.date))
                        put("isPartialDay", item.date == todayProvider().toString())
                        number("basalCaloriesKcal", item.basalCaloriesKcal)
                    })
                }
            })
        }
    }

    private suspend fun nutrition(call: GeminiFunctionCall): JsonElement {
        val (from, to) = range(call)
        val items = repository.getDaily(from, to)
        val meals = repository.getNutritionMeals(from, to)
        val goal = repository.getGoal()
        val body = repository.getBody(
            from.minusDays(EnergyModel.WEIGHT_FALLBACK_DAYS),
            to,
        )
        val measured = items.filter { it.intakeCaloriesKcal != null }
        val measuredDates = measured.map { it.date }.toSet()
        val intakeAverage = measured.mapNotNull { it.intakeCaloriesKcal }.averageOrNull()
        val proteinAverage = items.mapNotNull { it.proteinGrams }.averageOrNull()
        val fatAverage = items.mapNotNull { it.totalFatGrams }.averageOrNull()
        val carbohydrateAverage = items.mapNotNull { it.carbohydrateGrams }.averageOrNull()
        return buildJsonObject {
            putChronologyHeader(from, to, measuredDates)
            put("measurementDays", measured.size)
            put("confirmedCompleteDays", items.count { it.intakeCaloriesKcal != null && it.nutritionComplete })
            put("nutritionRecordCount", meals.size)
            number("totalMealCount", NutritionDetailPolicy.recordedMealCount(meals.map { it.isDailyRecord to it.mealType }))
            put("dailyRecordCount", meals.count { it.isDailyRecord })
            put("mealTimingAvailable", false)
            put("mealDetailLimitation", NutritionDetailPolicy.LIMITATION)
            put("coverageNote", "記録の存在は全食記録済みの証明ではありません。日次形式や種別不明から食事回数は推定しません")
            put(
                "sourceNote",
                "あすけんからHealth Connectへ書き出されるのは摂取カロリー・たんぱく質・脂質・炭水化物。" +
                    "欠測日は0kcalとせず、未記録として扱う。" +
                    NutritionDetailPolicy.LIMITATION +
                    "参考収支は摂取−基礎代謝−活動消費を、歩数・記録運動モデルとデバイス値の比較レンジで計算。" +
                    "参考値で、摂取量や赤字量の指示・減量成否の判定には使わない。" +
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
                        put("relativeDay", relativeDay(item.date))
                        put("isPartialDay", item.date == todayProvider().toString())
                        put("nutritionRecordStatus", item.nutritionRecordStatus)
                        put("nutritionComplete", item.nutritionComplete)
                        number("intakeCaloriesKcal", item.intakeCaloriesKcal)
                        number("proteinGrams", item.proteinGrams)
                        number("totalFatGrams", item.totalFatGrams)
                        number("carbohydrateGrams", item.carbohydrateGrams)
                        EnergyModel.dailyBalance(
                            item,
                            EnergyModel.dailyActive(item, body, goal?.heightCm, goal?.sex),
                        )?.let {
                            number(
                                "referenceEnergyBalanceLowKcal",
                                EnergyModel.roundForDisplay(it.lowKcal),
                            )
                            number(
                                "referenceEnergyBalanceHighKcal",
                                EnergyModel.roundForDisplay(it.highKcal),
                            )
                        }
                    })
                }
            })
            put("recentMeals", buildJsonArray {
                meals.takeLast(40).forEach { meal ->
                    add(buildJsonObject {
                        put("date", meal.date)
                        put("relativeDay", relativeDay(meal.date))
                        val typed = NutritionDetailPolicy.hasMealType(meal.isDailyRecord, meal.mealType)
                        put("startTimeLocal", if (typed) JsonPrimitive(formatEpochLocalTime(meal.startEpochMillis)) else JsonNull)
                        put("startEpochMillis", if (typed) JsonPrimitive(meal.startEpochMillis) else JsonNull)
                        put("mealTimeLocal", JsonNull)
                        put("mealType", if (typed) JsonPrimitive(meal.mealType) else JsonNull)
                        put("recordStartEpochMillis", meal.startEpochMillis)
                        put("timeInterpretation", "record_interval_not_verified_eating_time")
                        put("detailStatus", if (meal.isDailyRecord) "daily_summary" else if (typed) "typed_meal_record" else "unknown")
                        put("mealLabel", meal.mealLabel)
                        put("isDailyRecord", meal.isDailyRecord)
                        number("intakeCaloriesKcal", meal.intakeCaloriesKcal)
                        number("proteinGrams", meal.proteinGrams)
                        number("totalFatGrams", meal.totalFatGrams)
                        number("carbohydrateGrams", meal.carbohydrateGrams)
                        number(
                            "proteinEnergyPercent",
                            NutritionMacros.proteinEnergyPercent(meal.proteinGrams, meal.intakeCaloriesKcal),
                        )
                        number(
                            "fatEnergyPercent",
                            NutritionMacros.fatEnergyPercent(meal.totalFatGrams, meal.intakeCaloriesKcal),
                        )
                        number(
                            "carbohydrateEnergyPercent",
                            NutritionMacros.carbohydrateEnergyPercent(meal.carbohydrateGrams, meal.intakeCaloriesKcal),
                        )
                    })
                }
            })
        }
    }

    private suspend fun goals(): JsonElement {
        val goal = repository.getGoal()
            ?: return buildJsonObject { put("status", "目標未設定") }
        val today = todayProvider()
        val latestBody = repository.getBody(today.minusDays(7), today).lastOrNull()
        val latestActivity = repository.getDaily(today.minusDays(6), today)
        return buildJsonObject {
            put("todayDate", today.toString())
            put("yesterdayDate", today.minusDays(1).toString())
            goal.age?.let { put("age", it) }
            number("heightCm", goal.heightCm)
            goal.sex?.let { put("sex", it) }
            goal.deadline?.let {
                put("deadline", it)
                put("deadlineExpired", runCatching { LocalDate.parse(it).isBefore(today) }.getOrDefault(false))
            }
            number("monthlyWeightLossKg", goal.monthlyWeightLossKg)
            number("targetFatMassKg", goal.targetFatMassKg)
            number("minimumLeanMassKg", goal.minimumLeanMassKg)
            goal.dailySteps?.let { put("dailySteps", it) }
            goal.weeklyExerciseSessions?.let {
                put("weeklyMorningRoutineTargetDays", it)
            }
            number("dailyActiveCaloriesKcal", goal.dailyActiveCaloriesKcal)
            latestBody?.date?.let {
                put("latestBodyDate", it)
                put("latestBodyRelativeDay", relativeDay(it))
            }
            number("latestFatMassKg", latestBody?.fatMassKg)
            number("latestLeanBodyMassKg", latestBody?.leanBodyMassKg)
            number("currentStepsDailyAverage", latestActivity.mapNotNull { it.steps }.averageOrNull())
            put("currentExerciseSessions", latestActivity.sumOf { it.exerciseSessionCount })
        }
    }

    private fun List<Number>.averageOrNull(): Double? =
        if (isEmpty()) null else sumOf { it.toDouble() } / size

    private fun List<Long>.sumOrNull(): Long? = if (isEmpty()) null else sum()

    private fun JsonObjectBuilder.number(name: String, value: Number?) {
        if (value == null || value.toDouble().isNaN()) put(name, JsonNull)
        else put(name, JsonPrimitive(value))
    }

    companion object {
        private const val SLEEP_BASELINE_MINUTES = 6 * 60L
        private val LOCAL_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm")
    }
}
