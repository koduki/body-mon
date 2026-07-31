package com.master.healthcoach.data.llm

import com.master.healthcoach.domain.WeeklySnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthChatCoordinatorPayloadTest {
    @Test
    fun `new dashboard KPIs are excluded from existing Gemini payload`() {
        val snapshot = WeeklySnapshot(
            weekStart = "2026-07-24",
            weekEnd = "2026-07-30",
            fatMassChangeKg = -0.2,
            leanMassChangeKg = 0.0,
            weightChangeKg = -0.3,
            bodyMeasurementDays = 7,
            stepsDailyAverage = 8_000,
            activeCaloriesDailyAverage = 400.0,
            exerciseSessions = 6,
            exerciseMinutes = 60,
            strengthMinutes = 60,
            cardioMinutes = 0,
            previousWeekStepsDailyAverage = 7_500,
            previousWeekActiveCaloriesDailyAverage = 380.0,
            dataLimitations = emptyList(),
            weightLossRatePercentPerWeek = 0.5,
            fatMassTrendKgPerWeek = -0.2,
            strengthAdherencePercent = 86,
            sleepHeartRateAverageBpm = 58,
            dietStartDate = "2026-07-01",
        )

        val payload = snapshot.existingAiContract().toString()

        assertTrue(payload.contains("\"weightChangeKg\":-0.3"))
        assertFalse(payload.contains("weightLossRatePercentPerWeek"))
        assertFalse(payload.contains("fatMassTrendKgPerWeek"))
        assertFalse(payload.contains("strengthAdherencePercent"))
        assertFalse(payload.contains("sleepHeartRateAverageBpm"))
        assertFalse(payload.contains("dietStartDate"))
        assertFalse(payload.contains("2026-07-01"))
    }
}
