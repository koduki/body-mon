package com.master.healthcoach.domain

import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import java.time.LocalDate
import kotlin.math.roundToLong

object WeeklyReportBuilder {
    fun build(
        today: LocalDate,
        daily: List<DailyHealthSummaryEntity>,
        body: List<BodyCompositionEntity>,
    ): WeeklySnapshot {
        val currentStart = today.minusDays(6)
        val previousStart = currentStart.minusDays(7)
        val currentDaily = daily.filter { LocalDate.parse(it.date) in currentStart..today }
        val previousDaily = daily.filter {
            LocalDate.parse(it.date) in previousStart..currentStart.minusDays(1)
        }
        val currentBody = body.filter { LocalDate.parse(it.date) in currentStart..today }
            .sortedBy { it.date }

        val limitations = buildList {
            if (currentBody.size < 3) add("体組成の測定日が週3日未満です")
            if (currentDaily.count { it.steps != null } < 5) add("歩数データが5日未満です")
            if (currentDaily.count { it.activeCaloriesKcal != null } < 5) {
                add("活動消費カロリーが5日未満です")
            }
            if (currentDaily.count { it.sleepMinutes != null } < 5) {
                add("睡眠時間が5日未満です")
            }
            if (currentDaily.count { it.heartRateAverageBpm != null } < 5) {
                add("心拍数が5日未満です")
            }
            if (currentDaily.count { it.basalCaloriesKcal != null } < 5) {
                add("基礎代謝が5日未満です")
            }
            if (currentDaily.none {
                    it.moderateIntensityMinutes != null ||
                        it.vigorousIntensityMinutes != null
                }
            ) {
                add("アクティビティ強度を取得できていません")
            }
        }

        return WeeklySnapshot(
            weekStart = currentStart.toString(),
            weekEnd = today.toString(),
            fatMassChangeKg = change(currentBody.mapNotNull { it.fatMassKg }),
            leanMassChangeKg = change(currentBody.mapNotNull { it.leanBodyMassKg }),
            weightChangeKg = change(currentBody.mapNotNull { it.weightKg }),
            bodyMeasurementDays = currentBody.size,
            stepsDailyAverage = averageLong(currentDaily.mapNotNull { it.steps }),
            activeCaloriesDailyAverage = averageDouble(
                currentDaily.mapNotNull { it.activeCaloriesKcal },
            ),
            exerciseSessions = currentDaily.sumOf { it.exerciseSessionCount },
            exerciseMinutes = currentDaily.sumOf { it.exerciseMinutes },
            strengthMinutes = currentDaily.sumOf { it.strengthMinutes },
            cardioMinutes = currentDaily.sumOf { it.cardioMinutes },
            previousWeekStepsDailyAverage = averageLong(previousDaily.mapNotNull { it.steps }),
            previousWeekActiveCaloriesDailyAverage = averageDouble(
                previousDaily.mapNotNull { it.activeCaloriesKcal },
            ),
            dataLimitations = limitations,
            sleepDailyAverageMinutes = averageLong(
                currentDaily.mapNotNull { it.sleepMinutes },
            ),
            sleepMeasurementDays = currentDaily.count { it.sleepMinutes != null },
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
        )
    }

    private fun change(values: List<Double>): Double? =
        if (values.size < 2) null else values.last() - values.first()

    private fun averageLong(values: List<Long>): Long? =
        if (values.isEmpty()) null else values.average().roundToLong()

    private fun averageDouble(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.average()

    private fun sumLongOrNull(values: List<Long>): Long? =
        if (values.isEmpty()) null else values.sum()
}
