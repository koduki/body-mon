package com.master.healthcoach.domain

import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyModelTest {

    @Test
    fun `calculates walking net calories using health connect distance when plausible`() {
        val day = testDay(
            date = "2026-10-01",
            steps = 8_000,
            distanceMeters = 5_600.0, // 0.70 m/step -> plausible
        )
        // 5.6 km * 70 kg * 0.5 = 196.0 kcal
        val kcal = EnergyModel.stepModelActiveKcal(
            day = day,
            weightKg = 70.0,
            heightCm = 175.0,
            sex = "male",
        )
        assertNotNull(kcal)
        assertEquals(196.0, kcal!!, 0.001)
    }

    @Test
    fun `falls back to height-based stride length when distance is implausible`() {
        val day = testDay(
            date = "2026-10-01",
            steps = 10_000,
            distanceMeters = 100.0, // 0.01 m/step -> implausible, fallback
        )
        // Stride: 170.0 * 0.415 = 70.55 cm = 0.7055 m
        // Distance: 10,000 * 0.7055 / 1000 = 7.055 km
        // Net walking: 7.055 * 70.0 * 0.5 = 246.925 kcal
        val kcal = EnergyModel.stepModelActiveKcal(
            day = day,
            weightKg = 70.0,
            heightCm = 170.0,
            sex = "male",
        )
        assertNotNull(kcal)
        assertEquals(246.925, kcal!!, 0.001)
    }

    @Test
    fun `stride ratios handle female, male, and unknown sex properly`() {
        assertEquals(0.413, EnergyModel.strideRatio("female"), 0.0001)
        assertEquals(0.413, EnergyModel.strideRatio("女性"), 0.0001)
        assertEquals(0.415, EnergyModel.strideRatio("male"), 0.0001)
        assertEquals(0.415, EnergyModel.strideRatio("男性"), 0.0001)
        assertEquals(0.414, EnergyModel.strideRatio(null), 0.0001)
        assertEquals(0.414, EnergyModel.strideRatio(""), 0.0001)
    }

    @Test
    fun `adds morning routine and strength sessions with net METs without double counting walking`() {
        val day = testDay(
            date = "2026-10-01",
            steps = 10_000,
            distanceMeters = 7_000.0, // 0.70 m/step -> 7.0 km
            morningRoutineMinutes = 5, // 3.0 net MET -> 3.0 * 70 * (5/60) = 17.5 kcal
            strengthMinutes = 30, // 2.5 net MET -> 2.5 * 70 * (30/60) = 87.5 kcal
            cardioMinutes = 40, // Should NOT be added (walking already in steps)
        )
        // Walking: 7.0 km * 70 kg * 0.5 = 245.0 kcal
        // Morning routine: 17.5 kcal
        // Strength: 87.5 kcal
        // Total = 350.0 kcal
        val kcal = EnergyModel.stepModelActiveKcal(
            day = day,
            weightKg = 70.0,
            heightCm = 175.0,
            sex = "male",
        )
        assertNotNull(kcal)
        assertEquals(350.0, kcal!!, 0.001)
    }

    @Test
    fun `handles missing inputs as null, but treats zero steps as real zero`() {
        val dayZeroSteps = testDay(
            date = "2026-10-01",
            steps = 0L,
            morningRoutineMinutes = 5,
        )
        // Walking = 0.0, sessions = 17.5 kcal
        val zeroStepsResult = EnergyModel.stepModelActiveKcal(
            day = dayZeroSteps,
            weightKg = 70.0,
            heightCm = 175.0,
            sex = "male",
        )
        assertEquals(17.5, zeroStepsResult!!, 0.001)

        val dayNullSteps = testDay(
            date = "2026-10-01",
            steps = null,
        )
        assertNull(
            EnergyModel.stepModelActiveKcal(
                day = dayNullSteps,
                weightKg = 70.0,
                heightCm = 175.0,
                sex = "male",
            ),
        )

        assertNull(
            EnergyModel.stepModelActiveKcal(
                day = testDay("2026-10-01", steps = 5_000),
                weightKg = null,
                heightCm = 175.0,
                sex = "male",
            ),
        )
    }

    @Test
    fun `weightKgOn uses 7-day median prior to date and ignores future weights`() {
        val date = LocalDate.of(2026, 10, 10)
        val bodyHistory = listOf(
            testBody("2026-10-08", weight = 70.0),
            testBody("2026-10-09", weight = 71.0),
            testBody("2026-10-10", weight = 72.0),
            testBody("2026-10-12", weight = 60.0), // Future, must be ignored
        )
        val weight = EnergyModel.weightKgOn(date, bodyHistory)
        assertEquals(71.0, weight!!, 0.001)
    }

    @Test
    fun `summarizeActive requires at least 4 valid days`() {
        val days3 = (1..3).map {
            testDay(
                date = "2026-10-0$it",
                steps = 8_000,
                distanceMeters = 5_600.0,
                activeCaloriesKcal = 400.0,
            )
        }
        val body = listOf(testBody("2026-10-01", weight = 70.0))

        assertNull(EnergyModel.summarizeActive(days3, body, 175.0, "male"))

        val days4 = (1..4).map {
            testDay(
                date = "2026-10-0$it",
                steps = 8_000,
                distanceMeters = 5_600.0, // model = 196 kcal
                activeCaloriesKcal = 400.0,
            )
        }
        val summary = EnergyModel.summarizeActive(days4, body, 175.0, "male")
        assertNotNull(summary)
        assertEquals(4, summary!!.validDays)
        // 196 rounded to nearest 10 is 200.0
        assertEquals(200.0, summary.stepModelDailyAverageKcal, 0.001)
        assertEquals(400.0, summary.deviceDailyAverageKcal, 0.001)
        assertEquals(200.0, summary.range.lowKcal, 0.001)
        assertEquals(400.0, summary.range.highKcal, 0.001)
    }

    @Test
    fun `energy balance range is calculated from intake, basal, and active range`() {
        val day = testDay(
            date = "2026-10-01",
            steps = 8_000,
            distanceMeters = 5_600.0, // model active = 196 kcal
            activeCaloriesKcal = 450.0, // device active = 450 kcal
            basalCaloriesKcal = 1_500.0,
            intakeCaloriesKcal = 1_800.0,
        )
        val body = listOf(testBody("2026-10-01", weight = 70.0))
        val activeEstimate = EnergyModel.dailyActive(day, body, 175.0, "male")
        assertNotNull(activeEstimate)

        val balanceRange = EnergyModel.dailyBalance(day, activeEstimate)
        assertNotNull(balanceRange)
        // intake (1800) - basal (1500) - highActive (450) = -150
        // intake (1800) - basal (1500) - lowActive (196) = +104
        assertEquals(-150.0, balanceRange!!.lowKcal, 0.001)
        assertEquals(104.0, balanceRange.highKcal, 0.001)
    }

    @Test
    fun `rounds display values to 10 kcal increments`() {
        assertEquals(250.0, EnergyModel.roundForDisplay(246.0), 0.001)
        assertEquals(240.0, EnergyModel.roundForDisplay(244.0), 0.001)
        assertEquals(-150.0, EnergyModel.roundForDisplay(-148.0), 0.001)
    }

    @Test
    fun `adaptive TDEE check requires 21 intake days and weight trend`() {
        assertNull(
            EnergyModel.checkAdaptiveTdee(
                weightTrendKgPerWeek = -0.70,
                intakeDailyAverageKcal = 2_000.0,
                intakeDaysInTrend = 20, // < 21 days
                basalCaloriesDailyAverage = 1_500.0,
                deviceActiveDailyAverage = 450.0,
                stepModelActiveDailyAverage = 200.0,
            ),
        )

        assertNull(
            EnergyModel.checkAdaptiveTdee(
                weightTrendKgPerWeek = null,
                intakeDailyAverageKcal = 2_000.0,
                intakeDaysInTrend = 25,
                basalCaloriesDailyAverage = 1_500.0,
                deviceActiveDailyAverage = 450.0,
                stepModelActiveDailyAverage = 200.0,
            ),
        )

        val check = EnergyModel.checkAdaptiveTdee(
            weightTrendKgPerWeek = -0.70, // -0.10 kg/day
            intakeDailyAverageKcal = 2_000.0,
            intakeDaysInTrend = 24,
            basalCaloriesDailyAverage = 1_500.0,
            deviceActiveDailyAverage = 450.0,
            stepModelActiveDailyAverage = 200.0,
        )
        assertNotNull(check)
        // burn1 = 2000 - (-0.10 * 6000) = 2600.0
        // burn2 = 2000 - (-0.10 * 7700) = 2770.0
        assertEquals(2_600.0, check!!.inferredTdeeRange.lowKcal, 0.001)
        assertEquals(2_770.0, check.inferredTdeeRange.highKcal, 0.001)
        assertEquals(1_950.0, check.deviceTdeeKcal!!, 0.001) // 1500 + 450
        assertEquals(1_700.0, check.stepModelTdeeKcal!!, 0.001) // 1500 + 200
        assertEquals(24, check.intakeDays)
    }

    private fun testDay(
        date: String,
        steps: Long? = 8_000,
        distanceMeters: Double? = null,
        activeCaloriesKcal: Double? = 400.0,
        morningRoutineMinutes: Long = 0,
        strengthMinutes: Long = 0,
        cardioMinutes: Long = 0,
        basalCaloriesKcal: Double? = 1_500.0,
        intakeCaloriesKcal: Double? = 2_000.0,
    ) = DailyHealthSummaryEntity(
        date = date,
        steps = steps,
        distanceMeters = distanceMeters,
        activeCaloriesKcal = activeCaloriesKcal,
        exerciseMinutes = morningRoutineMinutes + strengthMinutes + cardioMinutes,
        strengthMinutes = strengthMinutes,
        morningRoutineMinutes = morningRoutineMinutes,
        cardioMinutes = cardioMinutes,
        exerciseSessionCount = 1,
        sleepMinutes = 480,
        moderateIntensityMinutes = 20,
        vigorousIntensityMinutes = 0,
        heartRateAverageBpm = 70,
        heartRateMinimumBpm = 50,
        heartRateMaximumBpm = 120,
        heartRateMeasurementCount = 100,
        basalCaloriesKcal = basalCaloriesKcal,
        intakeCaloriesKcal = intakeCaloriesKcal,
        proteinGrams = 120.0,
        totalFatGrams = 50.0,
        carbohydrateGrams = 200.0,
        dataOrigins = "mi fitness",
        updatedAtEpochMillis = 0,
    )

    private fun testBody(date: String, weight: Double) = BodyCompositionEntity(
        date = date,
        weightKg = weight,
        bodyFatPercent = 20.0,
        fatMassKg = weight * 0.2,
        leanBodyMassKg = weight * 0.8,
        leanMassSource = "calculated",
        measurementEpochMillis = 0,
        dataOrigin = "eufy",
    )
}
