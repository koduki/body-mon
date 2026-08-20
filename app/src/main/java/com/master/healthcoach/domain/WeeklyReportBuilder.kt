package com.master.healthcoach.domain

import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.db.estimatedEnergyBalanceKcal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

object WeeklyReportBuilder {
    private const val TREND_DAYS = 28L
    private const val MIN_TREND_MEASUREMENTS = 8
    private const val MIN_TREND_SPAN_DAYS = 14L
    private const val SLEEP_TARGET_MINUTES = 7 * 60L

    fun build(
        today: LocalDate,
        daily: List<DailyHealthSummaryEntity>,
        body: List<BodyCompositionEntity>,
        goal: GoalEntity? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): WeeklySnapshot {
        val currentStart = today.minusDays(6)
        val previousStart = currentStart.minusDays(7)
        val previousEnd = currentStart.minusDays(1)
        val trendStart = today.minusDays(TREND_DAYS - 1)

        val currentDaily = dailyInRange(daily, currentStart, today)
        val previousDaily = dailyInRange(daily, previousStart, previousEnd)
        val currentBody = bodyInRange(body, currentStart, today)
        val previousBody = bodyInRange(body, previousStart, previousEnd)
        val trendBody = bodyInRange(body, trendStart, today)

        val currentWeight = TrendMath.median(currentBody.mapNotNull { it.weightKg })
        val previousWeight = TrendMath.median(previousBody.mapNotNull { it.weightKg })
        val currentFatMass = TrendMath.median(currentBody.mapNotNull { it.fatMassKg })
        val previousFatMass = TrendMath.median(previousBody.mapNotNull { it.fatMassKg })
        val currentLeanMass = TrendMath.median(currentBody.mapNotNull { it.leanBodyMassKg })
        val previousLeanMass = TrendMath.median(previousBody.mapNotNull { it.leanBodyMassKg })

        val weightTrend = robustTrend(trendBody) { it.weightKg }
        val fatMassTrend = robustTrend(trendBody) { it.fatMassKg }
        val leanMassTrend = robustTrend(trendBody) { it.leanBodyMassKg }
        val weightLossRate = if (weightTrend != null && currentWeight != null && currentWeight > 0) {
            -weightTrend / currentWeight * 100.0
        } else {
            null
        }

        val stepsAverage = averageLong(currentDaily.mapNotNull { it.steps })
        val previousStepsAverage = averageLong(previousDaily.mapNotNull { it.steps })
        val morningRoutineDays = currentDaily.count { it.morningRoutineMinutes > 0 }
        val previousMorningRoutineDays = previousDaily.count {
            it.morningRoutineMinutes > 0
        }
        val morningRoutineTarget = (goal?.weeklyExerciseSessions ?: 7).coerceIn(0, 7)
        val morningRoutineAdherence = morningRoutineTarget.takeIf { it > 0 }?.let {
            (morningRoutineDays * 100.0 / it).roundToInt()
        }
        val stepTargetHits = goal?.dailySteps?.let { target ->
            currentDaily.count { (it.steps ?: Long.MIN_VALUE) >= target }
        }
        val stepsBaselinePercent = calculateStepsBaselinePercent(
            daily = daily,
            currentAverage = stepsAverage,
            dietStartDate = goal?.dietStartDate,
        )

        val currentSleepValues = currentDaily.mapNotNull { it.sleepMinutes }
        val currentSleepHeartRates = currentDaily.mapNotNull {
            it.sleepHeartRateAverageBpm
        }
        val sleepHeartRateBaseline = dailyInRange(
            daily,
            currentStart.minusDays(21),
            previousEnd,
        ).mapNotNull { it.sleepHeartRateAverageBpm }
        val sleepHeartRateAverage = averageLong(currentSleepHeartRates)
        val sleepHeartRateBaselineAverage = averageLong(sleepHeartRateBaseline)
        val sleepHeartRateDelta =
            if (
                currentSleepHeartRates.size >= 3 &&
                sleepHeartRateBaseline.size >= 7 &&
                sleepHeartRateAverage != null &&
                sleepHeartRateBaselineAverage != null
            ) {
                sleepHeartRateAverage - sleepHeartRateBaselineAverage
            } else {
                null
            }
        val sleepScheduleDeviation = circularMedianDeviationMinutes(
            currentDaily.mapNotNull { it.sleepStartEpochMillis },
            zoneId,
        )
        val measurementConsistency = measurementTimeConsistency(
            trendBody.mapNotNull { it.measurementEpochMillis },
            zoneId,
        )

        val fatMassToGoal = goal?.targetFatMassKg?.let { target ->
            currentFatMass?.minus(target)
        }
        val requiredFatLoss = requiredFatLossPerWeek(
            gapKg = fatMassToGoal,
            deadline = goal?.deadline,
            today = today,
        )
        val currentMvpaEquivalent = moderateEquivalentMinutes(currentDaily)
        val previousMvpaEquivalent = moderateEquivalentMinutes(previousDaily)

        val limitations = buildList {
            if (currentBody.size < 3) {
                add("今週の体組成測定が3日未満です")
            }
            if (!hasUsableTrend(trendBody.mapNotNull { item ->
                    item.weightKg?.let { LocalDate.parse(item.date) to it }
                })
            ) {
                add("28日トレンドには8回以上・14日以上の体重データが必要です")
            }
            if (currentDaily.count { it.steps != null } < 5) {
                add("歩数データが5日未満です")
            }
            if (currentSleepValues.size < 5) {
                add("主睡眠データが5日未満です")
            }
            if (currentSleepHeartRates.size < 3) {
                add("睡眠中心拍が3日未満です")
            }
            if (currentDaily.none {
                    it.moderateIntensityMinutes != null ||
                        it.vigorousIntensityMinutes != null
                }
            ) {
                add("アクティビティ強度を取得できていません")
            }
            if (currentDaily.count { it.intakeCaloriesKcal != null } < 5) {
                add("食事記録（摂取カロリー）が5日未満です")
            }
            if (measurementConsistency != null && measurementConsistency < 70) {
                add("体組成の測定時刻がばらついています")
            }
        }

        return WeeklySnapshot(
            weekStart = currentStart.toString(),
            weekEnd = today.toString(),
            fatMassChangeKg = difference(currentFatMass, previousFatMass),
            leanMassChangeKg = difference(currentLeanMass, previousLeanMass),
            weightChangeKg = difference(currentWeight, previousWeight),
            bodyMeasurementDays = currentBody.size,
            stepsDailyAverage = stepsAverage,
            activeCaloriesDailyAverage = averageDouble(
                currentDaily.mapNotNull { it.activeCaloriesKcal },
            ),
            exerciseSessions = currentDaily.sumOf { it.exerciseSessionCount },
            exerciseMinutes = currentDaily.sumOf { it.exerciseMinutes },
            strengthMinutes = currentDaily.sumOf { it.strengthMinutes },
            cardioMinutes = currentDaily.sumOf { it.cardioMinutes },
            previousWeekStepsDailyAverage = previousStepsAverage,
            previousWeekActiveCaloriesDailyAverage = averageDouble(
                previousDaily.mapNotNull { it.activeCaloriesKcal },
            ),
            dataLimitations = limitations,
            sleepDailyAverageMinutes = averageLong(currentSleepValues),
            sleepMeasurementDays = currentSleepValues.size,
            moderateIntensityMinutes = sumLongOrNull(
                currentDaily.mapNotNull { it.moderateIntensityMinutes },
            ),
            vigorousIntensityMinutes = sumLongOrNull(
                currentDaily.mapNotNull { it.vigorousIntensityMinutes },
            ),
            heartRateAverageBpm = averageLong(
                currentDaily.mapNotNull { it.heartRateAverageBpm },
            ),
            heartRateMinimumBpm = currentDaily.mapNotNull {
                it.heartRateMinimumBpm
            }.minOrNull(),
            heartRateMaximumBpm = currentDaily.mapNotNull {
                it.heartRateMaximumBpm
            }.maxOrNull(),
            heartRateMeasurementDays = currentDaily.count {
                it.heartRateAverageBpm != null
            },
            basalCaloriesDailyAverage = averageDouble(
                currentDaily.mapNotNull { it.basalCaloriesKcal },
            ),
            basalCaloriesMeasurementDays = currentDaily.count {
                it.basalCaloriesKcal != null
            },
            previousWeekSleepDailyAverageMinutes = averageLong(
                previousDaily.mapNotNull { it.sleepMinutes },
            ),
            previousWeekModerateIntensityMinutes = sumLongOrNull(
                previousDaily.mapNotNull { it.moderateIntensityMinutes },
            ),
            previousWeekVigorousIntensityMinutes = sumLongOrNull(
                previousDaily.mapNotNull { it.vigorousIntensityMinutes },
            ),
            previousWeekHeartRateAverageBpm = averageLong(
                previousDaily.mapNotNull { it.heartRateAverageBpm },
            ),
            previousWeekBasalCaloriesDailyAverage = averageDouble(
                previousDaily.mapNotNull { it.basalCaloriesKcal },
            ),
            currentWeightMedianKg = currentWeight,
            currentFatMassMedianKg = currentFatMass,
            currentLeanMassMedianKg = currentLeanMass,
            weightTrendKgPerWeek = weightTrend,
            weightLossRatePercentPerWeek = weightLossRate,
            fatMassTrendKgPerWeek = fatMassTrend,
            leanMassTrendKgPerWeek = leanMassTrend,
            trendMeasurementDays = trendBody.size,
            morningRoutineMinutes = currentDaily.sumOf { it.morningRoutineMinutes },
            morningRoutineDays = morningRoutineDays,
            previousWeekMorningRoutineDays = previousMorningRoutineDays,
            morningRoutineTargetDays = morningRoutineTarget,
            morningRoutineAdherencePercent = morningRoutineAdherence,
            stepsTargetHitDays = stepTargetHits,
            stepsBaselinePercent = stepsBaselinePercent,
            moderateEquivalentMinutes = currentMvpaEquivalent,
            previousWeekModerateEquivalentMinutes = previousMvpaEquivalent,
            sleepTargetHitDays = currentSleepValues.count {
                it >= SLEEP_TARGET_MINUTES
            }.takeIf { currentSleepValues.isNotEmpty() },
            sleepScheduleDeviationMinutes = sleepScheduleDeviation,
            sleepHeartRateAverageBpm = sleepHeartRateAverage,
            sleepHeartRateBaselineDeltaBpm = sleepHeartRateDelta,
            measurementTimeConsistencyPercent = measurementConsistency,
            fatMassToGoalKg = fatMassToGoal,
            requiredFatLossKgPerWeek = requiredFatLoss,
            dietStartDate = goal?.dietStartDate,
            intakeCaloriesDailyAverage = averageDouble(
                currentDaily.mapNotNull { it.intakeCaloriesKcal },
            ),
            proteinDailyAverageGrams = averageDouble(
                currentDaily.mapNotNull { it.proteinGrams },
            ),
            totalFatDailyAverageGrams = averageDouble(
                currentDaily.mapNotNull { it.totalFatGrams },
            ),
            carbohydrateDailyAverageGrams = averageDouble(
                currentDaily.mapNotNull { it.carbohydrateGrams },
            ),
            nutritionMeasurementDays = currentDaily.count { it.intakeCaloriesKcal != null },
            previousWeekIntakeCaloriesDailyAverage = averageDouble(
                previousDaily.mapNotNull { it.intakeCaloriesKcal },
            ),
            previousWeekProteinDailyAverageGrams = averageDouble(
                previousDaily.mapNotNull { it.proteinGrams },
            ),
            previousWeekTotalFatDailyAverageGrams = averageDouble(
                previousDaily.mapNotNull { it.totalFatGrams },
            ),
            previousWeekCarbohydrateDailyAverageGrams = averageDouble(
                previousDaily.mapNotNull { it.carbohydrateGrams },
            ),
            estimatedEnergyBalanceDailyAverage = averageDouble(
                currentDaily.mapNotNull { it.estimatedEnergyBalanceKcal },
            ),
        )
    }

    private fun <T> List<T>.inRange(
        from: LocalDate,
        to: LocalDate,
        date: (T) -> String,
    ): List<T> = filter { LocalDate.parse(date(it)) in from..to }

    private fun dailyInRange(
        items: List<DailyHealthSummaryEntity>,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyHealthSummaryEntity> = items.inRange(from, to) { it.date }

    private fun bodyInRange(
        items: List<BodyCompositionEntity>,
        from: LocalDate,
        to: LocalDate,
    ): List<BodyCompositionEntity> =
        items.inRange(from, to) { it.date }.sortedBy { it.date }

    private fun robustTrend(
        body: List<BodyCompositionEntity>,
        value: (BodyCompositionEntity) -> Double?,
    ): Double? {
        val points = body.mapNotNull { item ->
            value(item)?.let { LocalDate.parse(item.date) to it }
        }
        return if (hasUsableTrend(points)) {
            TrendMath.theilSenSlopePerWeek(points)
        } else {
            null
        }
    }

    private fun hasUsableTrend(points: List<Pair<LocalDate, Double>>): Boolean {
        if (points.size < MIN_TREND_MEASUREMENTS) return false
        val sorted = points.sortedBy { it.first }
        return ChronoUnit.DAYS.between(sorted.first().first, sorted.last().first) >=
            MIN_TREND_SPAN_DAYS
    }

    private fun calculateStepsBaselinePercent(
        daily: List<DailyHealthSummaryEntity>,
        currentAverage: Long?,
        dietStartDate: String?,
    ): Int? {
        val start = dietStartDate?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        } ?: return null
        val baseline = dailyInRange(daily, start.minusDays(28), start.minusDays(1))
            .mapNotNull { it.steps }
        if (baseline.size < 7 || currentAverage == null) return null
        val baselineAverage = baseline.average()
        return if (baselineAverage > 0) {
            (currentAverage / baselineAverage * 100.0).roundToInt()
        } else {
            null
        }
    }

    private fun moderateEquivalentMinutes(
        daily: List<DailyHealthSummaryEntity>,
    ): Long? {
        if (daily.none {
                it.moderateIntensityMinutes != null ||
                    it.vigorousIntensityMinutes != null
            }
        ) {
            return null
        }
        return daily.sumOf {
            (it.moderateIntensityMinutes ?: 0) +
                2 * (it.vigorousIntensityMinutes ?: 0)
        }
    }

    private fun circularMedianDeviationMinutes(
        epochMillis: List<Long>,
        zoneId: ZoneId,
    ): Long? {
        if (epochMillis.size < 3) return null
        val minutes = epochMillis.map { minuteOfDay(it, zoneId) }
        val center = circularCenterMinute(minutes) ?: return null
        return TrendMath.median(
            minutes.map { circularDistanceMinutes(it, center).toDouble() },
        )?.roundToLong()
    }

    private fun measurementTimeConsistency(
        epochMillis: List<Long>,
        zoneId: ZoneId,
    ): Int? {
        if (epochMillis.size < 5) return null
        val minutes = epochMillis.map { minuteOfDay(it, zoneId) }
        val center = circularCenterMinute(minutes) ?: return null
        return (
            minutes.count { circularDistanceMinutes(it, center) <= 90 } *
                100.0 / minutes.size
            ).roundToInt()
    }

    private fun minuteOfDay(epochMillis: Long, zoneId: ZoneId): Int {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalTime()
        return time.hour * 60 + time.minute
    }

    private fun circularCenterMinute(minutes: List<Int>): Int? {
        if (minutes.isEmpty()) return null
        val angles = minutes.map { it / 1440.0 * 2.0 * PI }
        val angle = atan2(
            angles.sumOf { sin(it) } / angles.size,
            angles.sumOf { cos(it) } / angles.size,
        )
        return ((angle / (2.0 * PI) * 1440.0).roundToInt() + 1440) % 1440
    }

    private fun circularDistanceMinutes(first: Int, second: Int): Int {
        val direct = kotlin.math.abs(first - second)
        return minOf(direct, 1440 - direct)
    }

    private fun requiredFatLossPerWeek(
        gapKg: Double?,
        deadline: String?,
        today: LocalDate,
    ): Double? {
        if (gapKg == null || gapKg <= 0 || deadline == null) return null
        val end = runCatching { LocalDate.parse(deadline) }.getOrNull() ?: return null
        val days = ChronoUnit.DAYS.between(today, end)
        return if (days > 0) gapKg / (days / 7.0) else null
    }

    private fun difference(current: Double?, previous: Double?): Double? =
        if (current == null || previous == null) null else current - previous

    private fun averageLong(values: List<Long>): Long? =
        if (values.isEmpty()) null else values.average().roundToLong()

    private fun averageDouble(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.average()

    private fun sumLongOrNull(values: List<Long>): Long? =
        if (values.isEmpty()) null else values.sum()
}
