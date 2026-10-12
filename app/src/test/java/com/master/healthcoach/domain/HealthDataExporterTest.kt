package com.master.healthcoach.domain

import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.ChatMessageEntity
import com.master.healthcoach.data.db.ConversationMemoryEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.ExerciseSessionEntity
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.db.NutritionMealEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthDataExporterTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val exportedAt = ZonedDateTime.of(2026, 10, 13, 12, 0, 0, 0, zone)

    @Test
    fun `filters measured data and chat messages to 28-day window and serializes to JSON`() {
        val inRangeStart = LocalDate.of(2026, 9, 16) // 28 days including 2026-10-13
        val outOfRangeDate = LocalDate.of(2026, 9, 15)

        val daily = listOf(
            DailyHealthSummaryEntity(
                date = outOfRangeDate.toString(),
                steps = 5000L,
                distanceMeters = 3500.0,
                activeCaloriesKcal = 200.0,
                exerciseMinutes = 15L,
                strengthMinutes = 0L,
                morningRoutineMinutes = 0L,
                cardioMinutes = 15L,
                exerciseSessionCount = 0,
                sleepMinutes = 360L,
                moderateIntensityMinutes = 10L,
                vigorousIntensityMinutes = 5L,
                heartRateAverageBpm = 72L,
                heartRateMinimumBpm = 52L,
                heartRateMaximumBpm = 110L,
                heartRateMeasurementCount = 100L,
                basalCaloriesKcal = 1500.0,
                intakeCaloriesKcal = 1800.0,
                proteinGrams = 110.0,
                totalFatGrams = 45.0,
                carbohydrateGrams = 220.0,
                mealCount = 3,
                dataOrigins = "com.health",
                updatedAtEpochMillis = 1_000L,
            ),
            DailyHealthSummaryEntity(
                date = inRangeStart.toString(),
                steps = 8500L,
                distanceMeters = 6100.0,
                activeCaloriesKcal = 340.0,
                exerciseMinutes = 30L,
                strengthMinutes = 10L,
                morningRoutineMinutes = 5L,
                cardioMinutes = 15L,
                exerciseSessionCount = 1,
                sleepMinutes = 380L,
                moderateIntensityMinutes = 20L,
                vigorousIntensityMinutes = 10L,
                heartRateAverageBpm = 74L,
                heartRateMinimumBpm = 50L,
                heartRateMaximumBpm = 135L,
                heartRateMeasurementCount = 120L,
                basalCaloriesKcal = 1500.0,
                intakeCaloriesKcal = 1750.0,
                proteinGrams = 125.0,
                totalFatGrams = 40.0,
                carbohydrateGrams = 210.0,
                mealCount = 3,
                dataOrigins = "com.health",
                updatedAtEpochMillis = 2_000L,
            ),
            DailyHealthSummaryEntity(
                date = "2026-10-13",
                steps = 9200L,
                distanceMeters = 6600.0,
                activeCaloriesKcal = 370.0,
                exerciseMinutes = 40L,
                strengthMinutes = 15L,
                morningRoutineMinutes = 5L,
                cardioMinutes = 20L,
                exerciseSessionCount = 1,
                sleepMinutes = 390L,
                sleepSessionMinutes = 420L,
                sleepDurationSource = "sleep_stages",
                cyclingMinutes = 25L,
                moderateIntensityMinutes = 25L,
                vigorousIntensityMinutes = 15L,
                heartRateAverageBpm = 75L,
                heartRateMinimumBpm = 49L,
                heartRateMaximumBpm = 140L,
                heartRateMeasurementCount = 130L,
                basalCaloriesKcal = 1500.0,
                intakeCaloriesKcal = 1700.0,
                proteinGrams = 130.0,
                totalFatGrams = 38.0,
                carbohydrateGrams = 205.0,
                mealCount = 3,
                dataOrigins = "com.health",
                updatedAtEpochMillis = 3_000L,
            ),
        )

        val body = listOf(
            BodyCompositionEntity(
                date = outOfRangeDate.toString(),
                weightKg = 70.5,
                bodyFatPercent = 21.0,
                fatMassKg = 14.8,
                leanBodyMassKg = 55.7,
                leanMassSource = "direct",
                measurementEpochMillis = outOfRangeDate.atTime(7, 0).atZone(zone).toInstant().toEpochMilli(),
                dataOrigin = "com.health",
            ),
            BodyCompositionEntity(
                date = "2026-10-13",
                weightKg = 69.2,
                bodyFatPercent = 19.8,
                fatMassKg = 13.7,
                leanBodyMassKg = 55.5,
                leanMassSource = "direct",
                measurementEpochMillis = exportedAt.minusHours(4).toInstant().toEpochMilli(),
                dataOrigin = "com.health",
            ),
        )

        val oldSessionStart = outOfRangeDate.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val recentSessionStart = inRangeStart.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val sessions = listOf(
            ExerciseSessionEntity(
                recordId = "s-old",
                startEpochMillis = oldSessionStart,
                endEpochMillis = oldSessionStart + 1_800_000L,
                exerciseType = 79,
                exerciseLabel = "Old Walk",
                category = "cardio",
                durationMinutes = 30L,
                dataOrigin = "com.health",
            ),
            ExerciseSessionEntity(
                recordId = "s-recent",
                startEpochMillis = recentSessionStart,
                endEpochMillis = recentSessionStart + 2_400_000L,
                exerciseType = 79,
                exerciseLabel = "Evening Walk",
                category = "cardio",
                durationMinutes = 40L,
                dataOrigin = "com.health",
            ),
        )

        val meals = listOf(
            NutritionMealEntity(
                recordId = "m-old",
                date = outOfRangeDate.toString(),
                startEpochMillis = oldSessionStart,
                endEpochMillis = oldSessionStart + 600_000L,
                mealLabel = "Old Meal",
                mealType = 1,
                intakeCaloriesKcal = 500.0,
                proteinGrams = 30.0,
                totalFatGrams = 12.0,
                carbohydrateGrams = 65.0,
                recordCount = 1,
                dataOrigin = "com.health",
            ),
            NutritionMealEntity(
                recordId = "m-recent",
                date = "2026-10-13",
                startEpochMillis = exportedAt.minusHours(3).toInstant().toEpochMilli(),
                endEpochMillis = exportedAt.minusHours(3).toInstant().toEpochMilli() + 600_000L,
                mealLabel = "オートミールと鶏むね肉",
                mealType = 1,
                intakeCaloriesKcal = 480.0,
                proteinGrams = 38.0,
                totalFatGrams = 8.0,
                carbohydrateGrams = 62.0,
                recordCount = 1,
                dataOrigin = "com.health",
            ),
        )

        val messages = listOf(
            ChatMessageEntity(
                id = 1L,
                role = "user",
                content = "29日前の質問",
                createdAtEpochMillis = outOfRangeDate.atTime(21, 0).atZone(zone).toInstant().toEpochMilli(),
            ),
            ChatMessageEntity(
                id = 2L,
                role = "user",
                content = "今日の朝食はどう？",
                attachmentNames = "breakfast.jpg\nmenu.txt",
                createdAtEpochMillis = exportedAt.minusHours(2).toInstant().toEpochMilli(),
            ),
            ChatMessageEntity(
                id = 3L,
                role = "assistant",
                content = "高たんぱく・低脂質で素晴らしい朝食です！",
                createdAtEpochMillis = exportedAt.minusHours(2).plusSeconds(15).toInstant().toEpochMilli(),
            ),
        )

        val memory = ConversationMemoryEntity(
            summary = "・朝食はオートミール中心\n・平日は夜にウォーキング",
            summarizedThroughMessageId = 1L,
            updatedAtEpochMillis = exportedAt.minusDays(1).toInstant().toEpochMilli(),
        )

        val goal = GoalEntity(
            age = 35,
            sex = "male",
            heightCm = 172.0,
            dietStartDate = "2026-09-01",
            deadline = "2026-12-31",
            targetFatMassKg = 12.0,
            minimumLeanMassKg = 55.0,
            dailySteps = 8000L,
            weeklyExerciseSessions = 3,
            dailyActiveCaloriesKcal = 350.0,
        )

        val payload = HealthDataExporter.buildPayload(
            exportedAt = exportedAt,
            daily = daily,
            body = body,
            exerciseSessions = sessions,
            nutritionMeals = meals,
            goal = goal,
            weeklySnapshot = null,
            chatMessages = messages,
            conversationMemory = memory,
        )

        assertEquals("2026-09-16", payload.periodStart)
        assertEquals("2026-10-13", payload.periodEnd)
        assertEquals(28, payload.daysIncluded)
        assertEquals(2, payload.measuredData.dailySummaries.size)
        assertEquals(1, payload.measuredData.bodyCompositions.size)
        assertEquals(1, payload.measuredData.exerciseSessions.size)
        assertEquals(1, payload.measuredData.nutritionMeals.size)
        assertEquals(2, payload.conversationHistory.messageCount)
        assertEquals(listOf("breakfast.jpg", "menu.txt"), payload.conversationHistory.messages[0].attachmentNames)
        assertNotNull(payload.conversationHistory.conversationMemory)
        assertEquals("・朝食はオートミール中心\n・平日は夜にウォーキング", payload.conversationHistory.conversationMemory?.summary)

        val jsonString = HealthDataExporter.encodeToJson(payload)
        assertTrue(jsonString.contains("\"periodStart\": \"2026-09-16\""))
        assertTrue(jsonString.contains("\"オートミールと鶏むね肉\""))
        assertTrue(jsonString.contains("\"高たんぱく・低脂質で素晴らしい朝食です！\""))

        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<HealthDataExportPayload>(jsonString)
        assertEquals(payload, decoded)
        val latestDay = decoded.measuredData.dailySummaries.last()
        assertEquals(390L, latestDay.sleepMinutes)
        assertEquals(420L, latestDay.sleepSessionMinutes)
        assertEquals("sleep_stages", latestDay.sleepDurationSource)
        assertEquals(25L, latestDay.cyclingMinutes)
    }

    @Test
    fun `generates expected default file name`() {
        assertEquals(
            "health-coach-export-2026-10-13.json",
            HealthDataExporter.defaultFileName(LocalDate.of(2026, 10, 13)),
        )
    }
}
