package com.master.healthcoach.domain

import org.junit.Assert.*
import org.junit.Test

class WorkoutHeartAnalyticsTest {
    private val m = 60_000L

    @Test fun `only time-associated workout points are labeled workout`() {
        val workouts = listOf(WorkoutHeartAnalytics.Workout("run", 0, 10 * m, 1))
        assertEquals("workout", WorkoutHeartAnalytics.associate(9 * m, workouts).phase)
        assertEquals("post_workout", WorkoutHeartAnalytics.associate(10 * m, workouts).phase)
        assertEquals("run", WorkoutHeartAnalytics.associate(12 * m, workouts).sessionId)
        assertEquals("unclassified", WorkoutHeartAnalytics.associate(13 * m, workouts).phase)
        assertNull(WorkoutHeartAnalytics.associate(13 * m, workouts).sessionId)
    }

    @Test fun `recovery requires real before and after points and enough workout coverage`() {
        val workout = WorkoutHeartAnalytics.Workout("run", 0, 10 * m, 1)
        val covered = (0 until 10).flatMap { idx ->
            listOf(
                WorkoutHeartAnalytics.Heart(idx * m + 10_000, if (idx == 9) 160 else 140),
                WorkoutHeartAnalytics.Heart(idx * m + 40_000, if (idx == 9) 160 else 140),
            )
        } + listOf(
            WorkoutHeartAnalytics.Heart(10 * m + 50_000, 145),
            WorkoutHeartAnalytics.Heart(10 * m + 65_000, 145),
            WorkoutHeartAnalytics.Heart(11 * m + 50_000, 130),
            WorkoutHeartAnalytics.Heart(11 * m + 65_000, 130),
        )
        val recovery = WorkoutHeartAnalytics.recovery(workout, covered)
        assertEquals(10, recovery.coverageMinutes)
        assertEquals(15, recovery.drop1MinBpm)
        assertEquals(30, recovery.drop2MinBpm)
        assertNull(WorkoutHeartAnalytics.recovery(workout, covered.filter { it.timeMs < workout.endMs }).drop1MinBpm)
        assertNull(WorkoutHeartAnalytics.recovery(workout, listOf(WorkoutHeartAnalytics.Heart(9 * m + 45_000, 160))).drop1MinBpm)
    }

    @Test fun `28 day comparisons match type and approximate duration but not effort`() {
        val end = 28L * 86_400_000L
        fun workout(daysAgo: Int, duration: Long, hr: Double, type: Int = 1) =
            WorkoutHeartAnalytics.ComparableWorkout(type, duration, hr,
                end - daysAgo * 86_400_000L, duration.toInt())
        val data = listOf(
            workout(1,30,130.0), workout(3,32,135.0),
            workout(10,29,140.0), workout(15,31,142.0),
            workout(13,65,165.0), workout(4,30,120.0,type=2),
        )
        val result = WorkoutHeartAnalytics.compare28Days(data, end)
        assertEquals(1, result.size)
        assertEquals(1, result.single().exerciseType)
        assertEquals(-8.5, result.single().differenceBpm, 0.001)
        assertTrue(result.single().note.contains("power"))
        assertTrue(WorkoutHeartAnalytics.compare28Days(data.take(3),end).isEmpty())
    }
}
