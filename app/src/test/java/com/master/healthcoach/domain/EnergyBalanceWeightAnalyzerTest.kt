package com.master.healthcoach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyBalanceWeightAnalyzerTest {
    @Test
    fun `aligned deficit when calorie deficit matches falling weight trend`() {
        val analysis = EnergyBalanceWeightAnalyzer.analyze(
            snapshot(
                balance = -550.0,
                weightTrend = -0.45,
                nutritionDays = 7,
            ),
        )

        assertEquals(EnergyBalanceWeightAlignment.ALIGNED_DEFICIT, analysis.alignment)
        assertEquals(-0.50, analysis.impliedWeightChangeKgPerWeek!!, 0.02)
        assertTrue(analysis.summary.contains("同じ減少方向"))
    }

    @Test
    fun `flags deficit without weight loss as mismatch without prescribing deeper cut`() {
        val analysis = EnergyBalanceWeightAnalyzer.analyze(
            snapshot(
                balance = -600.0,
                weightTrend = 0.10,
                nutritionDays = 6,
            ),
        )

        assertEquals(
            EnergyBalanceWeightAlignment.MISMATCH_DEFICIT_WEIGHT_STABLE_OR_UP,
            analysis.alignment,
        )
        assertTrue(analysis.isMismatch)
        assertTrue(analysis.guidance.contains("赤字をさらに強めない"))
    }

    @Test
    fun `flags surplus with falling weight as mismatch`() {
        val analysis = EnergyBalanceWeightAnalyzer.analyze(
            snapshot(
                balance = 400.0,
                weightTrend = -0.40,
                nutritionDays = 7,
            ),
        )

        assertEquals(
            EnergyBalanceWeightAlignment.MISMATCH_SURPLUS_WEIGHT_DOWN,
            analysis.alignment,
        )
        assertTrue(analysis.guidance.contains("摂取をさらに減らす根拠にはしません"))
    }

    @Test
    fun `holds judgment when nutrition coverage is thin`() {
        val analysis = EnergyBalanceWeightAnalyzer.analyze(
            snapshot(
                balance = -500.0,
                weightTrend = -0.40,
                nutritionDays = 3,
            ),
        )

        assertEquals(EnergyBalanceWeightAlignment.INSUFFICIENT_DATA, analysis.alignment)
        assertTrue(analysis.summary.contains("揃っていません"))
    }

    @Test
    fun `falls back to week over week weight change when 28 day trend is missing`() {
        val analysis = EnergyBalanceWeightAnalyzer.analyze(
            snapshot(
                balance = -550.0,
                weightTrend = null,
                weekChange = -0.40,
                nutritionDays = 7,
            ),
        )

        assertEquals(EnergyBalanceWeightAlignment.ALIGNED_DEFICIT, analysis.alignment)
        assertEquals(-0.40, analysis.observedWeightTrendKgPerWeek!!, 0.001)
    }

    private fun snapshot(
        balance: Double?,
        weightTrend: Double?,
        weekChange: Double? = -0.20,
        nutritionDays: Int,
    ) = WeeklySnapshot(
        weekStart = "2026-08-21",
        weekEnd = "2026-08-27",
        fatMassChangeKg = null,
        leanMassChangeKg = null,
        weightChangeKg = weekChange,
        bodyMeasurementDays = 7,
        stepsDailyAverage = 8_000,
        activeCaloriesDailyAverage = 450.0,
        exerciseSessions = 5,
        exerciseMinutes = 25,
        strengthMinutes = 0,
        cardioMinutes = 25,
        previousWeekStepsDailyAverage = 8_000,
        previousWeekActiveCaloriesDailyAverage = 450.0,
        dataLimitations = emptyList(),
        basalCaloriesDailyAverage = 1_600.0,
        intakeCaloriesDailyAverage = balance?.let { it + 1_600.0 + 450.0 },
        nutritionMeasurementDays = nutritionDays,
        estimatedEnergyBalanceDailyAverage = balance,
        weightTrendKgPerWeek = weightTrend,
    )
}
