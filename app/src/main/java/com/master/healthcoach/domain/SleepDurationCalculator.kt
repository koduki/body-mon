package com.master.healthcoach.domain

import java.time.Duration
import java.time.Instant

/** Known sleeping stages only; unknown/awake stages are not sleep measurements. */
data class SleepInterval(val start: Instant, val end: Instant)

data class SleepDurationEstimate(
    val sessionMinutes: Long,
    val sleepMinutes: Long?,
) {
    val displayMinutes: Long get() = sleepMinutes ?: sessionMinutes
    val source: String get() = if (sleepMinutes != null) "sleep_stages" else "session_fallback"
}

object SleepDurationCalculator {
    fun calculate(
        start: Instant,
        end: Instant,
        sleeping: List<SleepInterval>,
        hasKnownStages: Boolean = sleeping.isNotEmpty(),
    ): SleepDurationEstimate {
        val sessionMinutes = Duration.between(start, end).toMinutes().coerceAtLeast(0)
        val sleepMinutes = if (hasKnownStages) IntervalDuration.unionMinutes(start, end, sleeping) else null
        return SleepDurationEstimate(sessionMinutes, sleepMinutes)
    }
}

/** Clipped interval union, shared by sleep and cycling to avoid double counting. */
object IntervalDuration {
    fun unionMinutes(start: Instant, end: Instant, input: List<SleepInterval>): Long {
        val intervals = input.mapNotNull { interval ->
            val clippedStart = maxOf(start, interval.start)
            val clippedEnd = minOf(end, interval.end)
            if (clippedEnd > clippedStart) SleepInterval(clippedStart, clippedEnd) else null
        }.sortedBy { it.start }
        if (intervals.isEmpty()) return 0L
        var mergedStart = intervals.first().start
        var mergedEnd = intervals.first().end
        var total = Duration.ZERO
        for (interval in intervals.drop(1)) {
            if (interval.start <= mergedEnd) {
                mergedEnd = maxOf(mergedEnd, interval.end)
            } else {
                total = total.plus(Duration.between(mergedStart, mergedEnd))
                mergedStart = interval.start
                mergedEnd = interval.end
            }
        }
        total = total.plus(Duration.between(mergedStart, mergedEnd))
        return total.toMinutes()
    }
}
