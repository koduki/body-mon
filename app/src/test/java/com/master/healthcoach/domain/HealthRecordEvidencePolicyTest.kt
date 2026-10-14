package com.master.healthcoach.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HealthRecordEvidencePolicyTest {
    @Test fun `midnight session contributes only its actual minutes to each day`() {
        val midnight = Instant.parse("2026-10-13T15:00:00Z")
        val interval = SleepInterval(midnight.minusSeconds(600), midnight.plusSeconds(1200))
        assertTrue(HealthRecordEvidencePolicy.overlaps(interval.start.toEpochMilli(), interval.end.toEpochMilli(), midnight.toEpochMilli(), midnight.plusSeconds(86400).toEpochMilli()))
        assertEquals(10L, IntervalDuration.unionMinutes(midnight.minusSeconds(86400), midnight, listOf(interval)))
        assertEquals(20L, IntervalDuration.unionMinutes(midnight, midnight.plusSeconds(86400), listOf(interval)))
        assertFalse(HealthRecordEvidencePolicy.overlaps(midnight.minusSeconds(100).toEpochMilli(), midnight.toEpochMilli(), midnight.toEpochMilli(), midnight.plusSeconds(86400).toEpochMilli()))
    }
    @Test fun `source audit exposes alternate writers without adopting them`() {
        val rows = listOf(SourceIntervalEvidence("com.xiaomi.wearable", 0, 20, 25),
            SourceIntervalEvidence("com.fitbit.FitbitMobile", 0, 20, 30),
            SourceIntervalEvidence("com.fitbit.FitbitMobile", 50, 60, 65))
        val audit = HealthRecordEvidencePolicy.audit(rows, 10, 40)
        assertEquals(2, audit.size)
        assertTrue(audit.all { it.count == 1 })
        assertEquals(30L, audit.first { it.origin == "com.fitbit.FitbitMobile" }.latestModified)
    }
    @Test fun `basal aggregate cannot substitute for a missing Xiaomi record`() {
        assertNull(HealthRecordEvidencePolicy.basalRate(emptyList(), "xiaomi", 100))
        assertNull(HealthRecordEvidencePolicy.basalRate(listOf(RecordedBasalRate("phone", 0, 1643.0)), "xiaomi", 100))
        assertNull(HealthRecordEvidencePolicy.basalRate(listOf(RecordedBasalRate("xiaomi", 101, 1600.0)), "xiaomi", 100))
        assertEquals(1600.0, HealthRecordEvidencePolicy.basalRate(listOf(RecordedBasalRate("xiaomi", 0, 1500.0), RecordedBasalRate("xiaomi", 50, 1600.0)), "xiaomi", 100)!!, 0.01)
    }
}
