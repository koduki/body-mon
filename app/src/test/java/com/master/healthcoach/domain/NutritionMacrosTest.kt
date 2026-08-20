package com.master.healthcoach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NutritionMacrosTest {
    @Test
    fun `computes pfc energy percents from recorded grams and intake`() {
        assertEquals(26.0, NutritionMacros.proteinEnergyPercent(130.0, 2_000.0)!!, 0.001)
        assertEquals(27.0, NutritionMacros.fatEnergyPercent(60.0, 2_000.0)!!, 0.001)
        assertEquals(44.0, NutritionMacros.carbohydrateEnergyPercent(220.0, 2_000.0)!!, 0.001)
    }

    @Test
    fun `does not treat missing intake as zero percent`() {
        assertNull(NutritionMacros.proteinEnergyPercent(130.0, null))
        assertNull(NutritionMacros.fatEnergyPercent(60.0, 0.0))
    }

    @Test
    fun `computes atwater energy from available macros`() {
        assertEquals(1_000.0, NutritionMacros.atwaterEnergyKcal(50.0, 40.0, 110.0)!!, 0.001)
        assertNull(NutritionMacros.atwaterEnergyKcal(null, null, null))
    }
}
