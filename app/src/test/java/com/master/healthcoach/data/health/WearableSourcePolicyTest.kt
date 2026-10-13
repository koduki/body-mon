package com.master.healthcoach.data.health

import androidx.health.connect.client.records.*
import org.junit.Assert.*
import org.junit.Test

class WearableSourcePolicyTest {
    @Test fun `wearable requests exclude phone and Fitbit including when Xiaomi is absent`() {
        val writers = setOf("com.xiaomi.wearable", "com.fitbit.FitbitMobile", "com.android.healthconnect.phone.example")
        for (type in listOf(StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class,
            ExerciseSessionRecord::class, SleepSessionRecord::class, HeartRateRecord::class, ActivityIntensityRecord::class, BasalMetabolicRateRecord::class)) {
            val allowed = WearableSourcePolicy.originsFor(type).map { it.packageName }.toSet()
            assertEquals(setOf("com.xiaomi.wearable"), writers.intersect(allowed))
            assertTrue((writers - "com.xiaomi.wearable").intersect(allowed).isEmpty())
        }
    }
    @Test fun `scale and nutrition keep their independent sources`() {
        for (type in listOf(WeightRecord::class, BodyFatRecord::class, NutritionRecord::class)) {
            assertTrue(WearableSourcePolicy.originsFor(type).isEmpty())
        }
    }
}
