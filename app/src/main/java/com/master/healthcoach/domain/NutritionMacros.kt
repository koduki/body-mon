package com.master.healthcoach.domain

object NutritionMacros {
    const val PROTEIN_KCAL_PER_GRAM = 4.0
    const val FAT_KCAL_PER_GRAM = 9.0
    const val CARBOHYDRATE_KCAL_PER_GRAM = 4.0

    fun energyPercent(
        grams: Double?,
        kcalPerGram: Double,
        intakeKcal: Double?,
    ): Double? {
        if (grams == null || intakeKcal == null || intakeKcal <= 0) return null
        return grams * kcalPerGram / intakeKcal * 100.0
    }

    fun proteinEnergyPercent(proteinGrams: Double?, intakeKcal: Double?): Double? =
        energyPercent(proteinGrams, PROTEIN_KCAL_PER_GRAM, intakeKcal)

    fun fatEnergyPercent(fatGrams: Double?, intakeKcal: Double?): Double? =
        energyPercent(fatGrams, FAT_KCAL_PER_GRAM, intakeKcal)

    fun carbohydrateEnergyPercent(
        carbohydrateGrams: Double?,
        intakeKcal: Double?,
    ): Double? = energyPercent(carbohydrateGrams, CARBOHYDRATE_KCAL_PER_GRAM, intakeKcal)

    fun atwaterEnergyKcal(
        proteinGrams: Double?,
        fatGrams: Double?,
        carbohydrateGrams: Double?,
    ): Double? {
        val parts = listOfNotNull(
            proteinGrams?.times(PROTEIN_KCAL_PER_GRAM),
            fatGrams?.times(FAT_KCAL_PER_GRAM),
            carbohydrateGrams?.times(CARBOHYDRATE_KCAL_PER_GRAM),
        )
        if (parts.isEmpty()) return null
        val energy = parts.sum()
        return energy.takeIf { it > 0 }
    }
}
