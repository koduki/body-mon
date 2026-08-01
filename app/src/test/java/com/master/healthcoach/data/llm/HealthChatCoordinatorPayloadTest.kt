package com.master.healthcoach.data.llm

import com.master.healthcoach.data.db.GoalEntity
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
            morningRoutineMinutes = 30,
            morningRoutineAdherencePercent = 86,
            sleepHeartRateAverageBpm = 58,
            dietStartDate = "2026-07-01",
        )

        val payload = snapshot.existingAiContract().toString()

        assertTrue(payload.contains("\"weightChangeKg\":-0.3"))
        assertFalse(payload.contains("weightLossRatePercentPerWeek"))
        assertFalse(payload.contains("fatMassTrendKgPerWeek"))
        assertFalse(payload.contains("morningRoutineMinutes"))
        assertFalse(payload.contains("morningRoutineAdherencePercent"))
        assertFalse(payload.contains("sleepHeartRateAverageBpm"))
        assertFalse(payload.contains("dietStartDate"))
        assertFalse(payload.contains("2026-07-01"))
    }

    @Test
    fun `diet start date is excluded from Gemini profile`() {
        val goal = GoalEntity(
            age = 40,
            heightCm = 172.0,
            sex = "male",
            dietStartDate = "2026-07-01",
            deadline = "2026-12-31",
            targetFatMassKg = 16.0,
            minimumLeanMassKg = 58.0,
            dailySteps = 8_000,
            weeklyExerciseSessions = 7,
        )
        val profile = goal.existingAiProfile()
        val analysisGoal = goal.existingAiGoal()

        assertTrue(profile.contains("年齢=40"))
        assertTrue(profile.contains("期限=2026-12-31"))
        assertFalse(profile.contains("dietStartDate"))
        assertFalse(profile.contains("2026-07-01"))
        assertTrue(analysisGoal.contains("1日歩数=8000"))
        assertTrue(analysisGoal.contains("週の朝トレ目標日数=7"))
        assertFalse(analysisGoal.contains("dietStartDate"))
        assertFalse(analysisGoal.contains("2026-07-01"))
    }
}
