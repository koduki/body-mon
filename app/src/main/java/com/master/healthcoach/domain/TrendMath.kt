package com.master.healthcoach.domain

import java.time.LocalDate

object TrendMath {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    /**
     * Robust trend that is resistant to a single noisy smart-scale reading.
     * Returns the median of every pairwise slope, expressed per week.
     */
    fun theilSenSlopePerWeek(points: List<Pair<LocalDate, Double>>): Double? {
        if (points.size < 2) return null
        val sorted = points.sortedBy { it.first }
        val slopes = buildList {
            sorted.forEachIndexed { index, first ->
                for (secondIndex in index + 1 until sorted.size) {
                    val second = sorted[secondIndex]
                    val days = second.first.toEpochDay() - first.first.toEpochDay()
                    if (days > 0) add((second.second - first.second) / days.toDouble())
                }
            }
        }
        return median(slopes)?.times(7.0)
    }

    fun rollingMedian(
        points: List<Pair<LocalDate, Double>>,
        windowDays: Long = 7,
    ): Map<LocalDate, Double> {
        if (points.isEmpty()) return emptyMap()
        val sorted = points.sortedBy { it.first }
        return sorted.associate { (date, _) ->
            val from = date.minusDays(windowDays - 1)
            date to checkNotNull(
                median(
                    sorted.filter { it.first in from..date }.map { it.second },
                ),
            )
        }
    }
}
