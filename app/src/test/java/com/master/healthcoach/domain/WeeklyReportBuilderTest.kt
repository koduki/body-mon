package com.master.healthcoach.domain

import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReportBuilderTest {
    @Test
    fun `builds seven day snapshot and previous week comparison`() {
        val today = LocalDate.of(2026, 7, 30)
        val daily = (0L..13L).map { offset ->
            val date = today.minusDays(13 - offset)
            DailyHealthSummaryEntity(
                date = date.toString(),
                steps = if (date >= today.minusDays(6)) 8_000 else 6_000,
                distanceMeters = 5_000.0,
                activeCaloriesKcal = if (date >= today.minusDays(6)) 450.0 else 350.0,
                exerciseMinutes = if (offset % 3L == 0L) 30 else 0,
                strengthMinutes = if (offset % 3L == 0L) 30 else 0,
                cardioMinutes = 0,
                exerciseSessionCount = if (offset % 3L == 0L) 1 else 0,
                sleepMinutes = 420,
                moderateIntensityMinutes = 20,
                vigorousIntensityMinutes = 5,
                heartRateAverageBpm = 72,
                heartRateMinimumBpm = 48,
                heartRateMaximumBpm = 135,
                heartRateMeasurementCount = 100,
                basalCaloriesKcal = 1_650.0,
                dataOrigins = "mi fitness",
                updatedAtEpochMillis = 0,
            )
        }
        val body = listOf(
            body(today.minusDays(6), fat = 20.0, lean = 60.0, weight = 80.0),
            body(today.minusDays(3), fat = 19.8, lean = 60.1, weight = 79.9),
            body(today, fat = 19.5, lean = 60.0, weight = 79.5),
        )

        val report = WeeklyReportBuilder.build(today, daily, body)

        assertEquals(-0.5, report.fatMassChangeKg!!, 0.001)
        assertEquals(0.0, report.leanMassChangeKg!!, 0.001)
        assertEquals(8_000L, report.stepsDailyAverage)
        assertEquals(6_000L, report.previousWeekStepsDailyAverage)
        assertEquals(420L, report.sleepDailyAverageMinutes)
        assertEquals(140L, report.moderateIntensityMinutes)
        assertEquals(35L, report.vigorousIntensityMinutes)
        assertEquals(72L, report.heartRateAverageBpm)
        assertEquals(1_650.0, report.basalCaloriesDailyAverage!!, 0.001)
        assertTrue(report.dataLimitations.isEmpty())
    }

    @Test
    fun `marks sparse data as limitation`() {
        val today = LocalDate.of(2026, 7, 30)
        val report = WeeklyReportBuilder.build(today, emptyList(), emptyList())

        assertTrue(report.dataLimitations.size >= 3)
    }

    private fun body(date: LocalDate, fat: Double, lean: Double, weight: Double) =
        BodyCompositionEntity(
            date = date.toString(),
            weightKg = weight,
            bodyFatPercent = fat / weight * 100,
            fatMassKg = fat,
            leanBodyMassKg = lean,
            leanMassSource = "calculated",
            measurementEpochMillis = 0,
            dataOrigin = "eufy",
        )
}
