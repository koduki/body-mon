package com.master.healthcoach.domain

import kotlin.math.abs

object BodyCompositionCalculator {
    private const val DEFAULT_WINDOW_MILLIS = 10 * 60 * 1000L

    fun calculate(
        weights: List<TimedMeasurement>,
        bodyFatPercentages: List<TimedMeasurement>,
        leanMasses: List<TimedMeasurement>,
        maxDifferenceMillis: Long = DEFAULT_WINDOW_MILLIS,
    ): BodyCalculation {
        val weight = weights
            .filter { it.value.isFinite() && it.value > 0.0 }
            .maxByOrNull { it.epochMillis }
            ?: return BodyCalculation(null, null, null, null, null, null, null)
        val bodyFat = bodyFatPercentages
            .filter {
                it.value.isFinite() &&
                    it.value in 0.0..100.0 &&
                    abs(it.epochMillis - weight.epochMillis) <= maxDifferenceMillis
            }
            .minByOrNull { abs(it.epochMillis - weight.epochMillis) }
        val lean = leanMasses
            .filter {
                it.value.isFinite() &&
                    it.value > 0.0 &&
                    abs(it.epochMillis - weight.epochMillis) <= maxDifferenceMillis
            }
            .minByOrNull { abs(it.epochMillis - weight.epochMillis) }

        val fatMass = bodyFat?.let { weight.value * it.value / 100.0 }
        val leanMass = lean?.value ?: fatMass?.let { weight.value - it }
        val source = when {
            lean != null -> "health_connect"
            leanMass != null -> "calculated"
            else -> null
        }
        val origins = listOfNotNull(weight.origin, bodyFat?.origin, lean?.origin)
            .distinct()
            .joinToString(", ")

        return BodyCalculation(
            weightKg = weight.value,
            bodyFatPercent = bodyFat?.value,
            fatMassKg = fatMass,
            leanMassKg = leanMass,
            leanMassSource = source,
            measurementEpochMillis = weight.epochMillis,
            origin = origins.ifBlank { null },
        )
    }
}
