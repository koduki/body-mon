package com.master.healthcoach.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class TrendMathTest {
    @Test
    fun `median handles odd and even sample counts`() {
        assertEquals(3.0, TrendMath.median(listOf(5.0, 1.0, 3.0))!!, 0.001)
        assertEquals(2.5, TrendMath.median(listOf(4.0, 1.0, 3.0, 2.0))!!, 0.001)
    }

    @Test
    fun `theil sen slope ignores a single endpoint outlier`() {
        val start = LocalDate.of(2026, 7, 1)
        val points = (0L..20L).map { day ->
            start.plusDays(day) to if (day == 20L) 150.0 else 80.0 - day * 0.1
        }

        assertEquals(-0.7, TrendMath.theilSenSlopePerWeek(points)!!, 0.001)
    }

    @Test
    fun `rolling median uses calendar day window`() {
        val start = LocalDate.of(2026, 7, 1)
        val points = listOf(
            start to 80.0,
            start.plusDays(1) to 100.0,
            start.plusDays(6) to 79.0,
            start.plusDays(7) to 78.0,
        )

        val rolling = TrendMath.rollingMedian(points)

        assertEquals(80.0, rolling[start.plusDays(6)]!!, 0.001)
        assertEquals(79.0, rolling[start.plusDays(7)]!!, 0.001)
    }
}
