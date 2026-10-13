package com.master.healthcoach.domain

/** An empty NutritionRecord must not turn an API aggregate zero into an observed zero. */
object NutritionObservation {
    fun aggregateIfObserved(rawValues: List<Double?>, aggregated: Double?): Double? {
        if (rawValues.none { it != null && it.isFinite() && it >= 0 }) return null
        return aggregated?.takeIf { it.isFinite() && it >= 0 }
    }
}
