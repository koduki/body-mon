package com.master.healthcoach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachAssessmentEngineTest {
    @Test
    fun `marks sustainable fat loss with maintained habits as on track`() {
        val assessment = CoachAssessmentEngine.assess(
            snapshot(
                weightLossRate = 0.5,
                fatTrend = -0.3,
                leanTrend = 0.0,
                routineDays = 6,
                routineAdherence = 86,
                stepsBaseline = 102,
                sleepTargetDays = 5,
            ),
        )

        assertEquals(CoachVerdict.ON_TRACK, assessment.verdict)
        assertEquals("high", assessment.confidence)
        assertTrue(assessment.signals.any { it.code == "LOSS_RATE_ON_TARGET" })
        assertTrue(assessment.signals.any { it.code == "BODY_RECOMPOSITION_DIRECTION" })
        assertTrue(assessment.signals.any { it.code == "MORNING_ROUTINE_ON_TRACK" })
        assertTrue(assessment.nextActions.isEmpty())
    }

    @Test
    fun `prioritizes fast loss and lean trend before lower priority actions`() {
        val assessment = CoachAssessmentEngine.assess(
            snapshot(
                weightLossRate = 0.9,
                fatTrend = -0.5,
                leanTrend = -0.3,
                routineDays = 3,
                routineAdherence = 43,
                stepsBaseline = 80,
                sleepTargetDays = 2,
            ),
        )

        assertEquals(CoachVerdict.ADJUST, assessment.verdict)
        assertEquals(2, assessment.nextActions.size)
        assertTrue(assessment.nextActions[0].contains("減量をさらに強めず"))
        assertTrue(assessment.nextActions[1].contains("次回パーソナル"))
        assertTrue(assessment.signals.any { it.code == "MORNING_ROUTINE_ADHERENCE_LOW" })
    }

    @Test
    fun `low measurement consistency lowers confidence before interpreting trends`() {
        val assessment = CoachAssessmentEngine.assess(
            snapshot(
                weightLossRate = 0.5,
                fatTrend = -0.2,
                leanTrend = 0.0,
                routineDays = 6,
                routineAdherence = 86,
                stepsBaseline = 100,
                sleepTargetDays = 5,
                measurementConsistency = 50,
            ),
        )

        assertEquals(CoachVerdict.WATCH, assessment.verdict)
        assertEquals("low", assessment.confidence)
        assertEquals("MEASUREMENT_CONSISTENCY_LOW", assessment.signals.first().code)
        assertTrue(assessment.nextActions.first().contains("寝起き・トイレ後"))
    }

    @Test
    fun `holds advice when trend data is unavailable`() {
        val assessment = CoachAssessmentEngine.assess(
            snapshot(
                weightLossRate = null,
                fatTrend = null,
                leanTrend = null,
                routineDays = 0,
                routineAdherence = null,
                stepsBaseline = null,
                sleepTargetDays = null,
                trendDays = 0,
                bodyDays = 0,
            ),
        )

        assertEquals(CoachVerdict.NEED_MORE_DATA, assessment.verdict)
        assertEquals("low", assessment.confidence)
        assertEquals("元の回答", CoachResponseComposer.appendToChat("元の回答", assessment))
    }

    @Test
    fun `merges local expert view without expanding actions`() {
        val assessment = CoachAssessmentEngine.assess(
            snapshot(
                weightLossRate = 0.9,
                fatTrend = -0.5,
                leanTrend = -0.3,
                routineDays = 3,
                routineAdherence = 43,
                stepsBaseline = 80,
                sleepTargetDays = 2,
            ),
        )
        val modelAdvice = AdviceResponse(
            summary = "モデルの見立て",
            nextActions = listOf("モデル提案1", "モデル提案2", "モデル提案3"),
            confidence = "high",
            clarifyingQuestions = listOf("外食は週に何回ありましたか？"),
        )

        val merged = CoachResponseComposer.mergeStructuredAdvice(modelAdvice, assessment)
        val chat = CoachResponseComposer.appendToChat("モデル回答", assessment)

        assertTrue(merged.summary.startsWith("専門家判定は「調整推奨」"))
        assertEquals(2, merged.nextActions.size)
        assertEquals("high", merged.confidence)
        assertTrue(merged.dataLimitations.any { it.contains("重量・回数") })
        assertTrue(chat.contains("専門家ビュー（端末内KPI判定）"))
        assertTrue(chat.contains("今週の一手"))
        assertFalse(chat.contains("dietStartDate"))
        assertEquals(listOf("外食は週に何回ありましたか？"), merged.clarifyingQuestions)
    }

    @Test
    fun `flags low recorded protein without treating missing meals as zero`() {
        val assessment = CoachAssessmentEngine.assess(
            snapshot(
                weightLossRate = 0.5,
                fatTrend = -0.3,
                leanTrend = 0.0,
                routineDays = 6,
                routineAdherence = 86,
                stepsBaseline = 102,
                sleepTargetDays = 5,
                currentWeightMedianKg = 80.0,
                proteinDailyAverageGrams = 80.0,
                nutritionMeasurementDays = 7,
            ),
        )

        assertEquals(CoachVerdict.WATCH, assessment.verdict)
        assertTrue(assessment.signals.any { it.code == "PROTEIN_INTAKE_LOW" })
        assertTrue(assessment.nextActions.any { it.contains("たんぱく質") })
    }

    @Test
    fun `flags high fat energy share for a low-fat diet`() {
        val assessment = CoachAssessmentEngine.assess(
            snapshot(
                weightLossRate = 0.5,
                fatTrend = -0.3,
                leanTrend = 0.0,
                routineDays = 6,
                routineAdherence = 86,
                stepsBaseline = 102,
                sleepTargetDays = 5,
                currentWeightMedianKg = 80.0,
                proteinDailyAverageGrams = 160.0,
                nutritionMeasurementDays = 7,
                fatEnergyPercent = 35.0,
            ),
        )

        assertEquals(CoachVerdict.WATCH, assessment.verdict)
        assertTrue(assessment.signals.any { it.code == "FAT_SHARE_HIGH" })
        assertTrue(assessment.nextActions.any { it.contains("脂質") })
    }

    private fun snapshot(
        weightLossRate: Double?,
        fatTrend: Double?,
        leanTrend: Double?,
        routineDays: Int,
        routineAdherence: Int?,
        stepsBaseline: Int?,
        sleepTargetDays: Int?,
        measurementConsistency: Int = 100,
        trendDays: Int = 28,
        bodyDays: Int = 7,
        currentWeightMedianKg: Double? = null,
        proteinDailyAverageGrams: Double? = null,
        nutritionMeasurementDays: Int = 0,
        fatEnergyPercent: Double? = null,
    ) = WeeklySnapshot(
        weekStart = "2026-07-24",
        weekEnd = "2026-07-30",
        fatMassChangeKg = null,
        leanMassChangeKg = null,
        weightChangeKg = null,
        bodyMeasurementDays = bodyDays,
        stepsDailyAverage = 8_000,
        activeCaloriesDailyAverage = 400.0,
        exerciseSessions = routineDays,
        exerciseMinutes = routineDays * 5L,
        strengthMinutes = 0,
        cardioMinutes = routineDays * 5L,
        previousWeekStepsDailyAverage = 8_000,
        previousWeekActiveCaloriesDailyAverage = 400.0,
        dataLimitations = emptyList(),
        sleepDailyAverageMinutes = 440,
        sleepMeasurementDays = if (sleepTargetDays == null) 0 else 7,
        weightLossRatePercentPerWeek = weightLossRate,
        fatMassTrendKgPerWeek = fatTrend,
        leanMassTrendKgPerWeek = leanTrend,
        trendMeasurementDays = trendDays,
        morningRoutineMinutes = routineDays * 5L,
        morningRoutineDays = routineDays,
        morningRoutineTargetDays = 7,
        morningRoutineAdherencePercent = routineAdherence,
        stepsBaselinePercent = stepsBaseline,
        sleepTargetHitDays = sleepTargetDays,
        sleepHeartRateBaselineDeltaBpm = 0,
        measurementTimeConsistencyPercent = measurementConsistency,
        currentWeightMedianKg = currentWeightMedianKg,
        proteinDailyAverageGrams = proteinDailyAverageGrams,
        nutritionMeasurementDays = nutritionMeasurementDays,
        fatEnergyPercent = fatEnergyPercent,
    )
}
