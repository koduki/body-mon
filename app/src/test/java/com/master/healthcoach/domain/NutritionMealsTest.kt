package com.master.healthcoach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class NutritionMealsTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val date = LocalDate.of(2026, 8, 20)

    @Test
    fun `clusters nearby start times into one meal and distant times into separate meals`() {
        val meals = NutritionMealClusterer.cluster(
            listOf(
                record(LocalTime.of(7, 30), LocalTime.of(7, 50), protein = 25.0, fat = 8.0, carb = 40.0, energy = 330.0),
                record(LocalTime.of(7, 40), LocalTime.of(8, 0), protein = 10.0, fat = 3.0, carb = 15.0, energy = 130.0),
                record(LocalTime.of(12, 10), LocalTime.of(12, 40), protein = 35.0, fat = 12.0, carb = 70.0, energy = 530.0),
                record(LocalTime.of(19, 0), LocalTime.of(19, 45), protein = 40.0, fat = 10.0, carb = 55.0, energy = 470.0),
            ),
            zone,
        )

        assertEquals(3, meals.size)
        assertEquals(listOf("朝", "昼", "夕"), meals.map { it.mealLabel })
        assertEquals(2, meals[0].recordCount)
        assertEquals(35.0, meals[0].proteinGrams)
        assertFalse(meals.any { it.isDailyTotal })
    }

    @Test
    fun `uses mealType when start times would otherwise be unknown`() {
        val meals = NutritionMealClusterer.cluster(
            listOf(
                record(
                    LocalTime.of(11, 0),
                    LocalTime.of(11, 20),
                    mealType = NutritionWriteShape.MEAL_TYPE_BREAKFAST,
                    protein = 20.0,
                    fat = 6.0,
                    carb = 40.0,
                    energy = 300.0,
                ),
            ),
            zone,
        )
        assertEquals("朝", meals.single().mealLabel)
    }

    @Test
    fun `treats a twelve hour interval as one daily total meal`() {
        val meals = NutritionMealClusterer.cluster(
            listOf(
                record(
                    LocalTime.MIDNIGHT,
                    LocalTime.MIDNIGHT,
                    endNextDay = true,
                    protein = 120.0,
                    fat = 45.0,
                    carb = 220.0,
                    energy = 1_800.0,
                ),
            ),
            zone,
        )
        assertEquals(1, meals.size)
        assertTrue(meals.single().isDailyTotal)
        assertEquals("日次合計", meals.single().mealLabel)
    }

    @Test
    fun `marks low-fat protein-adequate meal as on target`() {
        val meal = NutritionMealClusterer.cluster(
            listOf(
                record(
                    LocalTime.of(12, 0),
                    LocalTime.of(12, 30),
                    protein = 40.0,
                    fat = 12.0,
                    carb = 70.0,
                    energy = 552.0,
                ),
            ),
            zone,
        ).single()
        assertEquals(PfcVerdict.ON_TARGET, meal.pfc.verdict)
        assertEquals("目安内", meal.pfc.label)
        assertEquals(19.6, meal.pfc.fatEnergyPercent!!, 0.1)
    }

    @Test
    fun `flags high fat and low protein meals`() {
        val highFat = PfcBalance.from(500.0, 20.0, 30.0, 30.0)
        assertEquals(PfcVerdict.WATCH, highFat.verdict)
        assertTrue(highFat.label.contains("脂質多め"))

        val lowProtein = PfcBalance.from(500.0, 10.0, 10.0, 90.0)
        assertEquals(PfcVerdict.WATCH, lowProtein.verdict)
        assertTrue(lowProtein.label.contains("たんぱく質少なめ"))
    }

    @Test
    fun `does not treat missing macros as balanced`() {
        val missing = PfcBalance.from(null, null, null, null)
        assertEquals(PfcVerdict.HOLD, missing.verdict)
        assertEquals("判定保留", missing.label)
    }

    private fun record(
        start: LocalTime,
        end: LocalTime,
        mealType: Int = NutritionWriteShape.MEAL_TYPE_UNKNOWN,
        protein: Double? = null,
        fat: Double? = null,
        carb: Double? = null,
        energy: Double? = null,
        endNextDay: Boolean = false,
    ) = NutritionRecordSnapshot(
        startEpochMillis = date.atTime(start).atZone(zone).toInstant().toEpochMilli(),
        endEpochMillis = date.plusDays(if (endNextDay) 1 else 0)
            .atTime(end)
            .atZone(zone)
            .toInstant()
            .toEpochMilli(),
        mealType = mealType,
        energyKcal = energy,
        proteinGrams = protein,
        fatGrams = fat,
        carbohydrateGrams = carb,
        origin = "jp.asken",
    )
}
