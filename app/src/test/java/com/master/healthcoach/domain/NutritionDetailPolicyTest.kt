package com.master.healthcoach.domain

import org.junit.Assert.*
import org.junit.Test

class NutritionDetailPolicyTest {
    @Test fun `daily summary never becomes one meal even with a stale meal type`() {
        assertNull(NutritionDetailPolicy.recordedMealCount(listOf(true to 0)))
        assertNull(NutritionDetailPolicy.recordedMealCount(listOf(true to 1)))
        assertFalse(NutritionDetailPolicy.hasMealType(true, 1))
        assertFalse(NutritionDetailPolicy.timeLabel(true, 0, "00:00〜00:00").contains("00:00"))
    }
    @Test fun `unknown and mixed records cannot establish a meal count or time`() {
        assertNull(NutritionDetailPolicy.recordedMealCount(emptyList()))
        assertNull(NutritionDetailPolicy.recordedMealCount(listOf(false to 0)))
        assertNull(NutritionDetailPolicy.recordedMealCount(listOf(false to 1, true to 0)))
        assertEquals("食事時刻・区分は未取得", NutritionDetailPolicy.timeLabel(false, 0, "19:00"))
    }
    @Test fun `explicit types establish recorded meals but timestamps remain unverified`() {
        assertEquals(2, NutritionDetailPolicy.recordedMealCount(listOf(false to 1, false to 2)))
        assertTrue(NutritionDetailPolicy.timeLabel(false, 1, "08:00").contains("食事時刻は未確認"))
    }
}
