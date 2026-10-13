package com.master.healthcoach.domain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
class NutritionObservationTest {
    @Test
    fun `an empty placeholder cannot become observed zero but explicit zero is retained`() {
        assertNull(NutritionObservation.aggregateIfObserved(listOf(null), 0.0))
        assertEquals(0.0, NutritionObservation.aggregateIfObserved(listOf(0.0), 0.0))
        assertEquals(1600.0, NutritionObservation.aggregateIfObserved(listOf(1600.0), 1600.0))
        assertNull(NutritionObservation.aggregateIfObserved(listOf(Double.NaN), 0.0))
    }
}
