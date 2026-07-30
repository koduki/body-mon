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
        )
    }

    private fun change(values: List<Double>): Double? =
        if (values.size < 2) null else values.last() - values.first()

    private fun averageLong(values: List<Long>): Long? =
        if (values.isEmpty()) null else values.average().roundToLong()

    private fun averageDouble(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.average()
}

