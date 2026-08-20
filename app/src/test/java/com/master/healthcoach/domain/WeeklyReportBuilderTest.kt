package com.master.healthcoach.domain

import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.GoalEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReportBuilderTest {
    private val zoneId = ZoneId.of("Asia/Tokyo")

    @Test
    fun `builds body recomposition KPIs from robust trends and weekly medians`() {
        val today = LocalDate.of(2026, 7, 30)
        val daily = (0L..34L).map { daysAgo ->
            val date = today.minusDays(daysAgo)
            daily(
                date = date,
                steps = when {
                    daysAgo <= 6 -> 8_000
                    daysAgo <= 21 -> 6_000
                    else -> 7_000
                },
                morningRoutineMinutes = if (daysAgo <= 6 && daysAgo != 3L) 5 else 0,
                sleepHeartRate = if (daysAgo <= 6) 70 else 68,
            )
        }
        val body = (0L..34L).map { daysAgo ->
            val date = today.minusDays(daysAgo)
            body(
                date = date,
                fat = 20.0 + daysAgo * 0.08,
                lean = 60.0 + daysAgo * 0.02,
                weight = 80.0 + daysAgo * 0.10,
            )
        }
        val goal = GoalEntity(
            dietStartDate = today.minusDays(21).toString(),
            targetFatMassKg = 18.0,
            dailySteps = 7_500,
            weeklyExerciseSessions = 7,
        )

        val report = WeeklyReportBuilder.build(today, daily, body, goal, zoneId)

        assertEquals(-0.70, report.weightChangeKg!!, 0.001)
        assertEquals(-0.56, report.fatMassChangeKg!!, 0.001)
        assertEquals(-0.14, report.leanMassChangeKg!!, 0.001)
        assertEquals(-0.70, report.weightTrendKgPerWeek!!, 0.001)
        assertEquals(-0.56, report.fatMassTrendKgPerWeek!!, 0.001)
        assertEquals(-0.14, report.leanMassTrendKgPerWeek!!, 0.001)
        assertEquals(0.872, report.weightLossRatePercentPerWeek!!, 0.001)
        assertEquals(6, report.morningRoutineDays)
        assertEquals(30L, report.morningRoutineMinutes)
        assertEquals(86, report.morningRoutineAdherencePercent)
        assertEquals(7, report.stepsTargetHitDays)
        assertEquals(114, report.stepsBaselinePercent)
        assertEquals(210L, report.moderateEquivalentMinutes)
        assertEquals(7, report.sleepTargetHitDays)
        assertEquals(0L, report.sleepScheduleDeviationMinutes)
        assertEquals(2L, report.sleepHeartRateBaselineDeltaBpm)
        assertEquals(100, report.measurementTimeConsistencyPercent)
        assertEquals(2_000.0, report.intakeCaloriesDailyAverage!!, 0.001)
        assertEquals(130.0, report.proteinDailyAverageGrams!!, 0.001)
        assertEquals(60.0, report.totalFatDailyAverageGrams!!, 0.001)
        assertEquals(220.0, report.carbohydrateDailyAverageGrams!!, 0.001)
        assertEquals(7, report.nutritionMeasurementDays)
        assertEquals(-100.0, report.estimatedEnergyBalanceDailyAverage!!, 0.001)
        assertTrue(report.dataLimitations.isEmpty())
    }

    @Test
    fun `theil sen body trend resists a single noisy smart scale value`() {
        val today = LocalDate.of(2026, 7, 30)
        val daily = (0L..27L).map { daily(today.minusDays(it)) }
        val body = (0L..27L).map { daysAgo ->
            val expected = 80.0 + daysAgo * 0.10
            body(
                date = today.minusDays(daysAgo),
                fat = 20.0,
                lean = 60.0,
                weight = if (daysAgo == 10L) 110.0 else expected,
            )
        }

        val report = WeeklyReportBuilder.build(today, daily, body, zoneId = zoneId)

        assertEquals(-0.70, report.weightTrendKgPerWeek!!, 0.001)
    }

    @Test
    fun `holds trend judgment when history is sparse`() {
        val today = LocalDate.of(2026, 7, 30)

        val report = WeeklyReportBuilder.build(
            today = today,
            daily = emptyList(),
            body = emptyList(),
            zoneId = zoneId,
        )

        assertEquals(null, report.weightLossRatePercentPerWeek)
        assertNotNull(report.dataLimitations.find { it.contains("28日トレンド") })
        assertTrue(report.dataLimitations.size >= 4)
    }

    @Test
    fun `counts morning routine days instead of ordinary strength sessions`() {
        val today = LocalDate.of(2026, 7, 30)
        val daily = listOf(
            daily(
                date = today,
                strengthMinutes = 30,
                morningRoutineMinutes = 0,
            ),
            daily(
                date = today.minusDays(1),
                strengthMinutes = 0,
                morningRoutineMinutes = 5,
            ),
        )

        val report = WeeklyReportBuilder.build(
            today = today,
            daily = daily,
            body = emptyList(),
            zoneId = zoneId,
        )

        assertEquals(1, report.morningRoutineDays)
        assertEquals(5L, report.morningRoutineMinutes)
        assertEquals(30L, report.strengthMinutes)
    }

    @Test
    fun `averages nutrition only from recorded days and does not treat gaps as zero`() {
        val today = LocalDate.of(2026, 7, 30)
        val daily = listOf(
            daily(
                date = today,
                intakeCaloriesKcal = 1_800.0,
                proteinGrams = 90.0,
                totalFatGrams = 50.0,
                carbohydrateGrams = 200.0,
            ),
            daily(
                date = today.minusDays(1),
                intakeCaloriesKcal = 2_200.0,
                proteinGrams = 150.0,
                totalFatGrams = 70.0,
                carbohydrateGrams = 240.0,
            ),
            daily(
                date = today.minusDays(2),
                intakeCaloriesKcal = null,
                proteinGrams = null,
                totalFatGrams = null,
                carbohydrateGrams = null,
            ),
        )

        val report = WeeklyReportBuilder.build(
            today = today,
            daily = daily,
            body = emptyList(),
            zoneId = zoneId,
        )

        assertEquals(2_000.0, report.intakeCaloriesDailyAverage!!, 0.001)
        assertEquals(120.0, report.proteinDailyAverageGrams!!, 0.001)
        assertEquals(2, report.nutritionMeasurementDays)
        assertNotNull(report.dataLimitations.find { it.contains("食事記録") })
    }

    private fun daily(
        date: LocalDate,
        steps: Long = 8_000,
        strengthMinutes: Long = 0,
        morningRoutineMinutes: Long = 5,
        sleepHeartRate: Long = 68,
        intakeCaloriesKcal: Double? = 2_000.0,
        proteinGrams: Double? = 130.0,
        totalFatGrams: Double? = 60.0,
        carbohydrateGrams: Double? = 220.0,
    ) = DailyHealthSummaryEntity(
        date = date.toString(),
        steps = steps,
        distanceMeters = 5_000.0,
        activeCaloriesKcal = 450.0,
        exerciseMinutes = strengthMinutes + morningRoutineMinutes,
        strengthMinutes = strengthMinutes,
        morningRoutineMinutes = morningRoutineMinutes,
        cardioMinutes = morningRoutineMinutes,
        exerciseSessionCount = if (strengthMinutes + morningRoutineMinutes > 0) 1 else 0,
        sleepMinutes = 450,
        sleepStartEpochMillis = date.minusDays(1).atTime(23, 0)
            .atZone(zoneId).toInstant().toEpochMilli(),
        sleepEndEpochMillis = date.atTime(6, 30)
            .atZone(zoneId).toInstant().toEpochMilli(),
        sleepHeartRateAverageBpm = sleepHeartRate,
        moderateIntensityMinutes = 20,
        vigorousIntensityMinutes = 5,
        heartRateAverageBpm = 72,
        heartRateMinimumBpm = 48,
        heartRateMaximumBpm = 135,
        heartRateMeasurementCount = 100,
        basalCaloriesKcal = 1_650.0,
        intakeCaloriesKcal = intakeCaloriesKcal,
        proteinGrams = proteinGrams,
        totalFatGrams = totalFatGrams,
        carbohydrateGrams = carbohydrateGrams,
        dataOrigins = "mi fitness",
        updatedAtEpochMillis = 0,
    )

    private fun body(
        date: LocalDate,
        fat: Double,
        lean: Double,
        weight: Double,
    ) = BodyCompositionEntity(
        date = date.toString(),
        weightKg = weight,
        bodyFatPercent = fat / weight * 100,
        fatMassKg = fat,
        leanBodyMassKg = lean,
        leanMassSource = "calculated",
        measurementEpochMillis = date.atTime(7, 30)
            .atZone(zoneId).toInstant().toEpochMilli(),
        dataOrigin = "eufy",
    )
}
