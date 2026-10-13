package com.master.healthcoach.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseClassifierTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    @Test
    fun `short morning Other is a habit candidate but long afternoon Other remains unknown`() {
        val date = LocalDate.of(2026, 8, 20)
        fun classify(hour: Int, mins: Long): ExerciseClass {
            val start = date.atTime(hour, 0).atZone(zone).toInstant()
            return classifyExerciseSession(0, start, start.plusSeconds(mins * 60), zone)
        }
        assertEquals(ExerciseClass.MORNING_ROUTINE, classify(8, 5))
        assertEquals(ExerciseClass.OTHER, classify(18, 74))
        assertEquals(ExerciseClass.OTHER, classify(8, 25))
        assertEquals(ExerciseClass.OTHER, classifyExercise(0))
    }
    @Test
    fun `hiking is cardio and HIIT does not masquerade as strength training`() {
        assertEquals(ExerciseClass.CARDIO, classifyExercise(ExerciseSessionRecord.EXERCISE_TYPE_HIKING))
        assertEquals("登山", exerciseLabel(37))
        assertEquals(ExerciseClass.INTERVAL, classifyExercise(36))
        assertTrue(ExerciseClass.INTERVAL.contributesToCardioMinutes())
        assertEquals(ExerciseClass.OTHER, classifyExercise(Int.MAX_VALUE))
    }
}
