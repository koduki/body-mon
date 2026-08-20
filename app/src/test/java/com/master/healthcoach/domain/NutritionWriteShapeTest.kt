package com.master.healthcoach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class NutritionWriteShapeTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    @Test
    fun `empty records cannot infer meal count`() {
        val shape = NutritionWriteShape.inspect(emptyList(), zone)
        assertEquals(0, shape.recordDays)
        assertNull(shape.medianRecordsPerDay)
        assertFalse(shape.likelyDailyTotals)
        assertFalse(shape.looksMealScoped)
        assertTrue(shape.summary.contains("確定できない"))
    }

    @Test
    fun `one unknown record spanning the calendar day is treated as daily totals`() {
        val records = (0L until 7L).map { offset ->
            interval(
                date = LocalDate.of(2026, 8, 13).plusDays(offset),
                start = LocalTime.MIDNIGHT,
                end = LocalTime.MIDNIGHT,
                endNextDay = true,
                mealType = NutritionWriteShape.MEAL_TYPE_UNKNOWN,
            )
        }
        val shape = NutritionWriteShape.inspect(records, zone)
        assertEquals(7, shape.recordDays)
        assertEquals(1.0, shape.medianRecordsPerDay)
        assertEquals(24.0, shape.medianDurationHours)
        assertTrue(shape.likelyDailyTotals)
        assertFalse(shape.looksMealScoped)
        assertTrue(shape.summary.contains("日次合計"))
        assertTrue(shape.summary.contains("確定できない"))
    }

    @Test
    fun `single instant per day at advice-view time is still not a meal count`() {
        val records = (0L until 5L).map { offset ->
            interval(
                date = LocalDate.of(2026, 8, 13).plusDays(offset),
                start = LocalTime.of(21, 30),
                end = LocalTime.of(21, 30),
                mealType = NutritionWriteShape.MEAL_TYPE_UNKNOWN,
            )
        }
        val shape = NutritionWriteShape.inspect(records, zone)
        assertEquals(1.0, shape.medianRecordsPerDay)
        assertEquals(0.0, shape.medianDurationHours)
        assertTrue(shape.likelyDailyTotals)
        assertFalse(shape.looksMealScoped)
        assertTrue(shape.summary.contains("データ取得時刻からも件数からも確定できない"))
    }

    @Test
    fun `breakfast lunch dinner records look meal scoped but are not adopted as a count`() {
        val date = LocalDate.of(2026, 8, 13)
        val records = listOf(
            interval(date, LocalTime.of(7, 30), LocalTime.of(8, 0), mealType = NutritionWriteShape.MEAL_TYPE_BREAKFAST),
            interval(date, LocalTime.of(12, 10), LocalTime.of(12, 40), mealType = NutritionWriteShape.MEAL_TYPE_LUNCH),
            interval(date, LocalTime.of(19, 0), LocalTime.of(19, 45), mealType = NutritionWriteShape.MEAL_TYPE_DINNER),
        )
        val shape = NutritionWriteShape.inspect(records, zone)
        assertEquals(1, shape.recordDays)
        assertEquals(3.0, shape.medianRecordsPerDay)
        assertFalse(shape.likelyDailyTotals)
        assertTrue(shape.looksMealScoped)
        assertEquals(listOf("夕", "昼", "朝"), shape.mealTypeLabels)
        assertTrue(shape.summary.contains("食事単位の可能性"))
        assertTrue(shape.summary.contains("回数を採用せず"))
    }

    @Test
    fun `ambiguous two records a day stay inconclusive`() {
        val records = listOf(
            interval(
                LocalDate.of(2026, 8, 13),
                LocalTime.of(8, 0),
                LocalTime.of(8, 20),
                mealType = NutritionWriteShape.MEAL_TYPE_UNKNOWN,
            ),
            interval(
                LocalDate.of(2026, 8, 13),
                LocalTime.of(19, 0),
                LocalTime.of(19, 20),
                mealType = NutritionWriteShape.MEAL_TYPE_UNKNOWN,
            ),
        )
        val shape = NutritionWriteShape.inspect(records, zone)
        assertFalse(shape.likelyDailyTotals)
        assertFalse(shape.looksMealScoped)
        assertTrue(shape.summary.contains("粒度は未確定"))
        assertTrue(shape.summary.contains("推定しない"))
    }

    private fun interval(
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        mealType: Int,
        endNextDay: Boolean = false,
    ): NutritionInterval {
        val startInstant = date.atTime(start).atZone(zone).toInstant()
        val endInstant = date.plusDays(if (endNextDay) 1 else 0)
            .atTime(end)
            .atZone(zone)
            .toInstant()
        return NutritionInterval(
            startEpochMillis = startInstant.toEpochMilli(),
            endEpochMillis = endInstant.toEpochMilli(),
            mealType = mealType,
        )
    }
}
