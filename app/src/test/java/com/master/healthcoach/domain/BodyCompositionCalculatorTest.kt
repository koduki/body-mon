package com.master.healthcoach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyCompositionCalculatorTest {
    @Test
    fun `uses direct lean mass when available near weight measurement`() {
        val result = BodyCompositionCalculator.calculate(
            weights = listOf(TimedMeasurement(1_000_000, 80.0, "eufy")),
            bodyFatPercentages = listOf(TimedMeasurement(1_030_000, 25.0, "eufy")),
            leanMasses = listOf(TimedMeasurement(1_040_000, 61.0, "eufy")),
        )

        assertEquals(20.0, result.fatMassKg!!, 0.001)
        assertEquals(61.0, result.leanMassKg!!, 0.001)
        assertEquals("health_connect", result.leanMassSource)
    }

    @Test
    fun `calculates lean mass when direct record is missing`() {
        val result = BodyCompositionCalculator.calculate(
            weights = listOf(TimedMeasurement(1_000_000, 80.0, "eufy")),
            bodyFatPercentages = listOf(TimedMeasurement(1_000_000, 25.0, "eufy")),
            leanMasses = emptyList(),
        )

        assertEquals(20.0, result.fatMassKg!!, 0.001)
        assertEquals(60.0, result.leanMassKg!!, 0.001)
        assertEquals("calculated", result.leanMassSource)
    }

    @Test
    fun `does not combine measurements outside time window`() {
        val result = BodyCompositionCalculator.calculate(
            weights = listOf(TimedMeasurement(1_000_000, 80.0, "eufy")),
            bodyFatPercentages = listOf(TimedMeasurement(2_000_000, 25.0, "eufy")),
            leanMasses = emptyList(),
        )

        assertNull(result.bodyFatPercent)
        assertNull(result.fatMassKg)
        assertNull(result.leanMassKg)
    }

    @Test
    fun `ignores invalid body fat percentage`() {
        val result = BodyCompositionCalculator.calculate(
            weights = listOf(TimedMeasurement(1_000_000, 80.0, "eufy")),
            bodyFatPercentages = listOf(TimedMeasurement(1_000_000, 125.0, "eufy")),
            leanMasses = emptyList(),
        )

        assertNull(result.bodyFatPercent)
        assertNull(result.fatMassKg)
        assertNull(result.leanMassKg)
    }
}
