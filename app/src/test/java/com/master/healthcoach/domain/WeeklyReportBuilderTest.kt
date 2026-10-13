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

        val report = WeeklyReportBuilder.build(today, daily, body, goal, zoneId, includeToday = true)

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
        assertEquals(7, report.referenceActiveValidDays)
        assertNotNull(report.referenceActiveRangeLowKcal)
        assertNotNull(report.referenceActiveRangeHighKcal)
        assertTrue(report.referenceActiveRangeLowKcal!! <= report.referenceActiveRangeHighKcal!!)
        assertEquals(7, report.referenceEnergyBalanceValidDays)
        assertNotNull(report.referenceEnergyBalanceLowKcal)
        assertNotNull(report.referenceEnergyBalanceHighKcal)
        assertTrue(report.referenceEnergyBalanceLowKcal!! <= report.referenceEnergyBalanceHighKcal!!)
        assertEquals(26.0, report.proteinEnergyPercent!!, 0.001)
        assertEquals(27.0, report.fatEnergyPercent!!, 0.001)
        assertEquals(44.0, report.carbohydrateEnergyPercent!!, 0.001)
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

        val report = WeeklyReportBuilder.build(today, daily, body, zoneId = zoneId, includeToday = true)

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
         includeToday = true)

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
         includeToday = true)

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
         includeToday = true)

        assertEquals(2_000.0, report.intakeCaloriesDailyAverage!!, 0.001)
        assertEquals(120.0, report.proteinDailyAverageGrams!!, 0.001)
        assertEquals(2, report.nutritionMeasurementDays)
        assertNotNull(report.dataLimitations.find { it.contains("食事記録") })
    }

    @Test
    fun `counts sleep target hit days using 6 hour baseline`() {
        val today = LocalDate.of(2026, 7, 30)
        val sleepDurations = listOf(360L, 380L, 410L, 355L, 330L, 420L, 370L)
        val daily = sleepDurations.mapIndexed { index, mins ->
            daily(
                date = today.minusDays(index.toLong()),
                sleepMinutes = mins,
            )
        }

        val report = WeeklyReportBuilder.build(
            today = today,
            daily = daily,
            body = emptyList(),
            zoneId = zoneId,
         includeToday = true)

        // 360, 380, 410, 420, 370 are >= 360 (5 days); 355 and 330 are < 360
        assertEquals(5, report.sleepTargetHitDays)
    }

    @Test
    fun `minimum sleep opportunity uses session while sleep duration uses stages`() {
        val today = LocalDate.of(2026, 7, 30)
        val daily = (0..6).map { offset ->
            daily(today.minusDays(offset.toLong()), sleepMinutes = 330)
                .copy(sleepSessionMinutes = 390, sleepDurationSource = "sleep_stages")
        }
        val report = WeeklyReportBuilder.build(today, daily, emptyList(), zoneId = zoneId, includeToday = true)
        assertEquals(7, report.sleepTargetHitDays)
        assertEquals(330L, report.sleepDailyAverageMinutes)
    }

    @Test
    fun `legacy and partial sleep data retain their different limitations`() {
        val today = LocalDate.of(2026, 7, 30)
        val daily = (0..6).map { offset ->
            daily(today.minusDays(offset.toLong())).copy(
                sleepDurationSource = if (offset == 0) "legacy_session" else "sleep_stages_partial",
            )
        }
        val report = WeeklyReportBuilder.build(today, daily, emptyList(), zoneId = zoneId, includeToday = true)
        assertTrue(report.dataLimitations.any { it.contains("セッション枠で代用") })
        assertTrue(report.dataLimitations.any { it.contains("既知の睡眠区間合計") })
    }

    @Test
    fun `default weekly aggregation excludes current morning cumulative data`() {
        val today = LocalDate.of(2026, 8, 20)
        val days = (1..14).map { daily(today.minusDays(it.toLong()), steps = 10000) } + daily(today, steps = 100)
        val report = WeeklyReportBuilder.build(today, days, emptyList(), zoneId = zoneId)
        assertEquals(today.minusDays(1).toString(), report.weekEnd)
        assertEquals(today.minusDays(7).toString(), report.weekStart)
        assertEquals(10000L, report.stepsDailyAverage)
        assertEquals(10000L, report.previousWeekStepsDailyAverage)
    }

    @Test
    fun `unconfirmed meal records remain counted but cannot generate dietary averages`() {
        val today = LocalDate.of(2026, 8, 20)
        val days = (1..7).map { daily(today.minusDays(it.toLong())).copy(nutritionComplete = false) }
        val report = WeeklyReportBuilder.build(today, days, emptyList(), zoneId = zoneId)
        assertEquals(7, report.nutritionMeasurementDays)
        assertEquals(0, report.nutritionCompleteDays)
        assertEquals(null, report.intakeCaloriesDailyAverage)
        assertEquals(null, report.referenceEnergyBalanceLowKcal)
        assertEquals(null, report.adaptiveTdeeLowKcal)
    }

    private fun daily(
        date: LocalDate,
        steps: Long = 8_000,
        strengthMinutes: Long = 0,
        morningRoutineMinutes: Long = 5,
        sleepMinutes: Long = 450,
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
        sleepMinutes = sleepMinutes,
        sleepSessionMinutes = sleepMinutes,
        sleepDurationSource = "sleep_stages",
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
        nutritionComplete = true,
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
