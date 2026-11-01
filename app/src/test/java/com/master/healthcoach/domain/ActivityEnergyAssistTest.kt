package com.master.healthcoach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityEnergyAssistTest {
    private val h = 3_600_000L
    private val m = 60_000L
    private fun step(start: Long, end: Long, count: Long) =
        ActivityEnergyAssist.Steps(start, end, count)
    private fun session(id: String, start: Long, end: Long, kind: ActivityEnergyAssist.Activity) =
        ActivityEnergyAssist.Session(id, start, end, kind)

    @Test fun `net walking uses stride and bodyweight without heart rate`() {
        val result = ActivityEnergyAssist.estimate(
            0, h * 2, 78.0, 172.0, "male", 41, 10_000,
            listOf(step(0, h * 2, 10_000)), emptyList(), emptyList(),
        )!!
        val expected = 10_000 * 0.7138 / 1000.0 * 78.0 * 0.5
        assertEquals(expected, result.activeKcal, 0.01)
        assertEquals(0.0, result.exerciseKcal, 0.001)
    }

    @Test fun `running steps are removed before net MET exercise is added`() {
        val s = session("run", h, h + 30 * m, ActivityEnergyAssist.Activity.RUNNING)
        val result = ActivityEnergyAssist.estimate(
            0, h * 2, 78.0, 172.0, "male", 41, 10_000,
            listOf(step(0, h, 6_000), step(h, h + 30 * m, 4_000)),
            listOf(s), emptyList(),
        )!!
        assertEquals(6_000 * .7138 / 1000.0 * 78 * .5, result.walkingKcal, .01)
        assertEquals((8.3 - 1.0) * 78 * .5, result.exerciseKcal, .01)
        assertEquals(0, result.heartAdjustedMinutes)
    }

    @Test fun `adequate workout heart coverage changes exercise only and reports observed zone minutes`() {
        val session = session("run", 0, 10 * m, ActivityEnergyAssist.Activity.RUNNING)
        val heart = (0 until 10).map { ActivityEnergyAssist.Heart(it * m, 155L) }
        val withHeart = ActivityEnergyAssist.estimate(0, h, 78.0, 172.0, "male", 41, 0,
            emptyList(), listOf(session), heart)!!
        val without = ActivityEnergyAssist.estimate(0, h, 78.0, 172.0, "male", 41, 0,
            emptyList(), listOf(session), emptyList())!!
        assertTrue(withHeart.exerciseKcal > without.exerciseKcal)
        assertEquals(10, withHeart.heartAdjustedMinutes)
        assertEquals(10, withHeart.observedZoneMinutes.sum())
        assertEquals(10, withHeart.sessions.getValue("run").heartObservedMinutes)
    }

    @Test fun `single heart artefact does not modify MET energy`() {
        val s = session("walk", 0, 20 * m, ActivityEnergyAssist.Activity.WALKING)
        val small = listOf(ActivityEnergyAssist.Heart(0, 200L))
        val result = ActivityEnergyAssist.estimate(0, h, 78.0, 172.0, "male", 41, 0,
            emptyList(), listOf(s), small)!!
        assertEquals(0, result.heartAdjustedMinutes)
        assertEquals((3.5 - 1) * 78 * (20.0 / 60), result.exerciseKcal, .01)
    }

    @Test fun `overlapping workouts never double count energy or excluded steps`() {
        val s1 = session("a", 0, 20 * m, ActivityEnergyAssist.Activity.WALKING)
        val s2 = session("b", 10 * m, 30 * m, ActivityEnergyAssist.Activity.RUNNING)
        val result = ActivityEnergyAssist.estimate(0, h, 78.0, 172.0, "male", 41, 1_000,
            listOf(step(0, h, 1_000)), listOf(s1, s2), emptyList())!!
        val exercise = (3.5 - 1.0) * 78 * (10.0 / 60) +
            (8.3 - 1.0) * 78 * (20.0 / 60)
        assertEquals(exercise, result.exerciseKcal, .01)
        assertEquals(500 * .7138 / 1000 * 78 * .5, result.walkingKcal, .01)
    }

    @Test fun `unknown steps are not treated as zero or silently double counted`() {
        val s = session("a", 0, 10 * m, ActivityEnergyAssist.Activity.RUNNING)
        assertNull(ActivityEnergyAssist.estimate(0, h, 78.0, 172.0, "male", 41, null,
            emptyList(), listOf(s), emptyList()))
        assertNull(ActivityEnergyAssist.estimate(0, h, 78.0, 172.0, "male", 41, 1_000,
            emptyList(), listOf(s), emptyList()))
    }
}
