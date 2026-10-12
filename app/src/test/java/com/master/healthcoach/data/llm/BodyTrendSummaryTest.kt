package com.master.healthcoach.data.llm

import com.master.healthcoach.data.db.BodyCompositionEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class BodyTrendSummaryTest {
    @Test
    fun `historical trend uses requested end and ignores future measurements`() {
        val end = LocalDate.parse("2026-09-30")
        val body = (0..27).map { offset ->
            BodyCompositionEntity(
                date = end.minusDays(offset.toLong()).toString(),
                weightKg = 70.0 + offset * 0.1,
                bodyFatPercent = null,
                fatMassKg = null,
                leanBodyMassKg = null,
                leanMassSource = null,
                measurementEpochMillis = null,
                dataOrigin = null,
            )
        }
        val future = body.first().copy(date = "2026-10-13", weightKg = 99.0)
        val summary = bodyTrendSummary(end, body + future, ZoneId.of("Asia/Tokyo"))
        assertEquals("2026-09-24", summary["medianFrom"]!!.jsonPrimitive.content)
        assertEquals("2026-09-30", summary["medianTo"]!!.jsonPrimitive.content)
        assertEquals("2026-09-03", summary["trendFrom"]!!.jsonPrimitive.content)
        assertEquals("2026-09-30", summary["trendTo"]!!.jsonPrimitive.content)
        assertEquals(7, summary["medianWeightMeasurementDays"]!!.jsonPrimitive.int)
        assertEquals(0, summary["medianFatMeasurementDays"]!!.jsonPrimitive.int)
        assertEquals(70.3, summary["currentWeightMedianKg"]!!.jsonPrimitive.double, 0.001)
        assertEquals(-0.7, summary["weightTrendKgPerWeek"]!!.jsonPrimitive.double, 0.001)
    }
}
