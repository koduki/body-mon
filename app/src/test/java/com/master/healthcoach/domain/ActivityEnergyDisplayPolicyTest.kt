package com.master.healthcoach.domain

import org.junit.Assert.*
import org.junit.Test

class ActivityEnergyDisplayPolicyTest {
    @Test fun `independent estimate is primary without inventing a blended number`() {
        val display = ActivityEnergyDisplayPolicy.compare(205.0, 245.0)
        assertEquals(ActivityEnergyDisplayPolicy.Source.INDEPENDENT, display.source)
        assertEquals(205.0, display.primaryKcal!!, 0.001)
        assertEquals(40.0, display.differenceKcal!!, 0.001)
        assertTrue(display.hasComparison)
    }

    @Test fun `device estimate is explicit fallback when independent missing`() {
        val display = ActivityEnergyDisplayPolicy.compare(null, 245.0)
        assertEquals(ActivityEnergyDisplayPolicy.Source.XIAOMI, display.source)
        assertEquals(245.0, display.primaryKcal!!, 0.001)
        assertNull(display.differenceKcal)
        assertFalse(display.hasComparison)
    }

    @Test fun `missing estimates stay missing and measured zero stays zero`() {
        val missing = ActivityEnergyDisplayPolicy.compare(null, null)
        assertEquals(ActivityEnergyDisplayPolicy.Source.MISSING, missing.source)
        assertNull(missing.primaryKcal)
        assertEquals(0.0, ActivityEnergyDisplayPolicy.compare(0.0, 50.0).primaryKcal!!, 0.001)
    }

    @Test fun `invalid model outputs cannot become primary or a misleading range`() {
        val display = ActivityEnergyDisplayPolicy.compare(Double.NaN, Double.POSITIVE_INFINITY)
        assertEquals(ActivityEnergyDisplayPolicy.Source.MISSING, display.source)
        assertFalse(display.hasComparison)
        assertEquals(ActivityEnergyDisplayPolicy.Source.XIAOMI,
            ActivityEnergyDisplayPolicy.compare(-1.0, 100.0).source)
    }
}
