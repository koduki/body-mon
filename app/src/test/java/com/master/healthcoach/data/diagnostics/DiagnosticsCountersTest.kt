package com.master.healthcoach.data.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsCountersTest {
    @Test
    fun `heart-rate sync counter is a supported diagnostics field`() {
        // HealthRepository.syncInternal records this after Room has committed.
        // Rejecting it used to turn a successful save into a failed sync.
        val syncCounters = setOf("days", "body", "sessions", "heartSamples", "meals")
        assertTrue(
            "All Health Connect synchronization metrics must be accepted by DiagnosticsStore",
            DiagnosticsStore.COUNTERS.containsAll(syncCounters),
        )
    }
}
