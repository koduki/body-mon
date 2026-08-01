package com.master.healthcoach.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseClassifierTest {
    @Test
    fun `other workout is the light strength and cardio morning routine`() {
        val type = ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT

        val exerciseClass = classifyExercise(type)

        assertEquals(ExerciseClass.MORNING_ROUTINE, exerciseClass)
        assertTrue(exerciseClass.contributesToCardioMinutes())
        assertTrue(exerciseLabel(type).contains("軽い筋トレ＋有酸素"))
    }

    @Test
    fun `unknown exercise types stay separate from the configured routine`() {
        assertEquals(ExerciseClass.OTHER, classifyExercise(Int.MAX_VALUE))
    }
}
