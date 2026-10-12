package com.master.healthcoach.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepDurationCalculatorTest {
    private val start = Instant.parse("2026-10-12T14:00:00Z")
    private fun minute(offset: Long) = start.plusSeconds(offset * 60)

    @Test
    fun `sleep stages exclude awake gaps and keep session opportunity separate`() {
        val estimate = SleepDurationCalculator.calculate(start, minute(420), listOf(
            SleepInterval(start, minute(120)),
            SleepInterval(minute(150), minute(420)),
        ))
        assertEquals(420L, estimate.sessionMinutes)
        assertEquals(390L, estimate.sleepMinutes)
        assertEquals("sleep_stages", estimate.source)
    }

    @Test
    fun `missing stages are explicitly a session fallback`() {
        val estimate = SleepDurationCalculator.calculate(start, minute(420), emptyList())
        assertNull(estimate.sleepMinutes)
        assertEquals(420L, estimate.displayMinutes)
        assertEquals("session_fallback", estimate.source)
    }

    @Test
    fun `partial known stage coverage is marked incomplete without assuming gaps asleep`() {
        val intervals = listOf(SleepInterval(start, minute(60)))
        val estimate = SleepDurationCalculator.calculate(start, minute(420), intervals, knownStageIntervals = intervals)
        assertEquals(60L, estimate.sleepMinutes)
        assertEquals("sleep_stages_partial", estimate.source)
    }

    @Test
    fun `interval union clips a cycling session crossing midnight and merges duplicates`() {
        val intervals = listOf(SleepInterval(minute(-10), minute(20)), SleepInterval(start, minute(20)))
        assertEquals(20L, IntervalDuration.unionMinutes(start, minute(1440), intervals))
    }

    @Test
    fun `known awake only session is zero sleep rather than a fallback`() {
        val estimate = SleepDurationCalculator.calculate(start, minute(420), emptyList(), hasKnownStages = true)
        assertEquals(0L, estimate.sleepMinutes)
        assertEquals("sleep_stages", estimate.source)
    }

    @Test
    fun `overlapping and out of bounds stages cannot inflate sleep duration`() {
        val estimate = SleepDurationCalculator.calculate(start, minute(420), listOf(
            SleepInterval(minute(-30), minute(120)),
            SleepInterval(minute(100), minute(150)),
            SleepInterval(minute(180), minute(450)),
        ))
        assertEquals(390L, estimate.sleepMinutes)
    }
}
