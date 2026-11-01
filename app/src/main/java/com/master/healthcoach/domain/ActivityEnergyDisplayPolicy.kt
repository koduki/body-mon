package com.master.healthcoach.domain

/**
 * Presentation-only choice of which observed estimate to foreground.
 *
 * An independent MET/steps/HR estimate and the Xiaomi estimate are two
 * imperfect models, NOT lower/upper confidence bounds. Do not average,
 * calibrate or prescribe intake from them without independent validation.
 */
object ActivityEnergyDisplayPolicy {
    enum class Source { INDEPENDENT, XIAOMI, MISSING }

    data class Display(
        val primaryKcal: Double?,
        val source: Source,
        val independentKcal: Double?,
        val deviceKcal: Double?,
        /** Xiaomi minus independently modelled active calories; not an error estimate. */
        val differenceKcal: Double?,
    ) {
        val hasComparison: Boolean get() = independentKcal != null && deviceKcal != null
    }

    fun compare(independentKcal: Double?, deviceKcal: Double?): Display {
        val independent = independentKcal?.takeIf { it.isFinite() && it >= 0.0 }
        val device = deviceKcal?.takeIf { it.isFinite() && it >= 0.0 }
        return Display(
            primaryKcal = independent ?: device,
            source = when {
                independent != null -> Source.INDEPENDENT
                device != null -> Source.XIAOMI
                else -> Source.MISSING
            },
            independentKcal = independent,
            deviceKcal = device,
            differenceKcal = if (independent != null && device != null) device - independent else null,
        )
    }
}
