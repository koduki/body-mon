package com.master.healthcoach.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/** Time alignment and conservative interpretation of Xiaomi HR samples. */
object WorkoutHeartAnalytics {
    const val VERSION = "2026-11-hr-series-v1"
    const val RECOVERY_WINDOW_MS = 3 * 60_000L

    data class Workout(val id: String, val startMs: Long, val endMs: Long, val exerciseType: Int)
    data class Heart(val timeMs: Long, val bpm: Long)
    data class Association(val sessionId: String?, val phase: String)
    data class Recovery(
        val heartAverageBpm: Double?,
        val workoutSampleCount: Int,
        val coverageMinutes: Int,
        /** Difference in bpm between last 30s of exercise and about 1 minute after. */
        val drop1MinBpm: Int?,
        val drop2MinBpm: Int?,
    )

    /** Observed non-exercise points are NOT proven resting. */
    fun associate(timeMs: Long, workouts: List<Workout>): Association {
        val active = workouts.filter { timeMs >= it.startMs && timeMs < it.endMs }
            .maxByOrNull { it.startMs }
        if (active != null) return Association(active.id, "workout")
        val after = workouts.filter { timeMs >= it.endMs && timeMs < it.endMs + RECOVERY_WINDOW_MS }
            .maxByOrNull { it.endMs }
        return if (after != null) Association(after.id, "post_workout")
            else Association(null, "unclassified")
    }

    fun recovery(workout: Workout, samples: List<Heart>): Recovery {
        val within = samples.filter { it.timeMs in workout.startMs until workout.endMs && it.bpm in 40..220 }
        val duration = ((workout.endMs - workout.startMs) / 60_000.0).coerceAtLeast(0.0)
        val coverage = within.map { it.timeMs / 60_000 }.distinct().size
        val avg = within.takeIf { it.isNotEmpty() }?.map { it.bpm.toDouble() }?.average()

        fun medianInWindow(from: Long, to: Long): Double? {
            val values = samples.filter { it.timeMs in from..to && it.bpm in 40..220 }
                .map { it.bpm.toDouble() }
            // Avoid drawing recovery conclusions from a single sensor spike.
            return if (values.size >= 2) TrendMath.median(values) else null
        }
        val endBpm = medianInWindow(workout.endMs - 30_000, workout.endMs)
        // HRR1 / HRR2 use windows around +1min / +2min, NOT all-day aggregates.
        val after1 = medianInWindow(workout.endMs + 45_000, workout.endMs + 75_000)
        val after2 = medianInWindow(workout.endMs + 105_000, workout.endMs + 135_000)
        // Require coverage of at least half the workout before comparing recovery.
        val validWorkout = duration >= 3.0 && coverage >= duration * 0.5
        return Recovery(
            heartAverageBpm = avg,
            workoutSampleCount = within.size,
            coverageMinutes = coverage,
            drop1MinBpm = if (validWorkout && endBpm != null && after1 != null)
                (endBpm - after1).roundToInt() else null,
            drop2MinBpm = if (validWorkout && endBpm != null && after2 != null)
                (endBpm - after2).roundToInt() else null,
        )
    }

    data class ComparableWorkout(
        val exerciseType: Int,
        val durationMinutes: Long,
        val averageHeartBpm: Double?,
        val startMs: Long,
        val sampleCoverageMinutes: Int,
    )
    @Serializable
    data class Comparison(
        val exerciseType: Int,
        val recentCount: Int,
        val referenceCount: Int,
        val recentMeanBpm: Double,
        val referenceMeanBpm: Double,
        val differenceBpm: Double,
        val note: String = "Only exercise type and duration are matched (±20%); pace, incline, power, temperature, and recovery status are unknown. Not evidence of a change in VO2max.",
    )

    /** Comparison is descriptive only, and requires at least two workouts per side. */
    fun compare28Days(workouts: List<ComparableWorkout>, rangeEndExclusiveMs: Long): List<Comparison> {
        val periodStart = rangeEndExclusiveMs - 28L * 86_400_000L
        val recentStart = rangeEndExclusiveMs - 7L * 86_400_000L
        val valid = workouts.filter {
            it.startMs in periodStart until rangeEndExclusiveMs &&
                it.averageHeartBpm != null && it.durationMinutes >= 5 &&
                it.sampleCoverageMinutes >= it.durationMinutes * .5
        }
        return valid.groupBy { it.exerciseType }.mapNotNull { (type, all) ->
            val recent = all.filter { it.startMs >= recentStart }
            val prior = all.filter { it.startMs < recentStart }
            if (recent.size < 2 || prior.size < 2) return@mapNotNull null
            // Compare only similar duration distribution, not just a shared category.
            val base = TrendMath.median(recent.map { it.durationMinutes.toDouble() }) ?: return@mapNotNull null
            val matchedRecent = recent.filter { abs(it.durationMinutes - base) <= base * .20 }
            val matchedPrior = prior.filter { abs(it.durationMinutes - base) <= base * .20 }
            if (matchedRecent.size < 2 || matchedPrior.size < 2) return@mapNotNull null
            val now = matchedRecent.mapNotNull { it.averageHeartBpm }.average()
            val before = matchedPrior.mapNotNull { it.averageHeartBpm }.average()
            Comparison(type, matchedRecent.size, matchedPrior.size, now, before, now - before)
        }.sortedBy { it.exerciseType }
    }
}
